"""Album CRUD + public share routes (spec §5/§6/§3.10-3.12).

Split out of ``server.py`` so the album/share surface lives in one place. The
routes close over runtime objects (the album + media stores) and a handful of
helpers created in ``server._register_sync_routes``; those are passed in
explicitly rather than captured, keeping this module import-safe and the
dependency surface obvious. Device-gated CRUD plus the unauthenticated
token-gated public share routes are mounted together.
"""
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


def register_album_routes(
    app,
    *,
    album_store,
    media_indexer,
    media_mod,
    settings_mod,
    _configs_dir,
    _require_device,
    _media_item,
    _media_row_or_404,
    _assert_within_root,
    _serve_with_range,
    _THUMB_CACHE,
    _SHARE_NOBOT_HEADERS,
) -> None:
    from fastapi import Header, HTTPException, Request
    from fastapi.responses import FileResponse, HTMLResponse
    from starlette.concurrency import run_in_threadpool
    from .api_models import (
        AlbumCreateRequest as _AlbumCreateRequest,
        AlbumItemsRequest as _AlbumItemsRequest,
        AlbumRenameRequest as _AlbumRenameRequest,
    )
    from .share_page import not_found_html as _share_404_html
    from .share_page import page_html as _share_page_html

    def _album_or_404(album_id: int) -> dict:
        row = album_store._row(album_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Unknown album")
        return row

    def _share_url(token: str, request: Request) -> str:
        base = settings_mod.load_settings(_configs_dir())["public_base_url"]
        if not base:
            base = str(request.base_url).rstrip("/")
        return f"{base}/share/{token}"

    def _album_entry(row: dict, request: Request) -> dict:
        token = row["share_token"]
        return {
            "id": row["id"],
            "name": row["name"],
            "created_by": row["created_by"],
            "created_at": row["created_at"],
            "item_count": album_store.count(row["id"]),
            "cover_media_id": album_store.cover_media_id(row["id"]),
            "shared": token is not None,
            "share_url": _share_url(token, request) if token else None,
        }

    @app.get("/api/albums")
    async def list_albums(
        request: Request, authorization: str | None = Header(default=None)
    ):
        _require_device(authorization)
        rows = album_store.list_albums()
        return [_album_entry(r, request) for r in rows]

    @app.post("/api/albums")
    async def create_album(
        req: _AlbumCreateRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        row = album_store.create(req.name, created_by=req.created_by)
        if req.media_ids:
            album_store.add_items(row["id"], req.media_ids)
        return _album_entry(album_store._row(row["id"]), request)

    @app.patch("/api/albums/{album_id}")
    async def rename_album(
        album_id: int,
        req: _AlbumRenameRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        album_store.rename(album_id, req.name)
        return _album_entry(album_store._row(album_id), request)

    @app.delete("/api/albums/{album_id}")
    async def delete_album(
        album_id: int, authorization: str | None = Header(default=None)
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        album_store.delete(album_id)
        return {"deleted": album_id}

    @app.post("/api/albums/{album_id}/items")
    async def add_album_items(
        album_id: int,
        req: _AlbumItemsRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        album_store.add_items(album_id, req.media_ids)
        return _album_entry(album_store._row(album_id), request)

    @app.delete("/api/albums/{album_id}/items")
    async def remove_album_items(
        album_id: int,
        req: _AlbumItemsRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        album_store.remove_items(album_id, req.media_ids)
        return _album_entry(album_store._row(album_id), request)

    @app.get("/api/albums/{album_id}/items")
    async def list_album_items(
        album_id: int, authorization: str | None = Header(default=None)
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        return [_media_item(r) for r in album_store.items(album_id)]

    @app.post("/api/albums/{album_id}/share")
    async def share_album(
        album_id: int,
        request: Request,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        token = album_store.share(album_id)
        return {"share_token": token, "share_url": _share_url(token, request)}

    @app.delete("/api/albums/{album_id}/share")
    async def unshare_album(
        album_id: int, authorization: str | None = Header(default=None)
    ):
        _require_device(authorization)
        _album_or_404(album_id)
        album_store.revoke(album_id)
        return {"revoked": True}

    # -- public share (no device token; token + membership IS the auth) ---
    # Spec §5.2/§6/§3.10/§3.12. These routes carry no bearer token: the share
    # token resolves to an album and the requested media_id must be a member.
    # Non-member ids return 404 (not 403) so the surface never leaks ids.
    def _album_for_token_or_404(token: str) -> dict:
        album = album_store.by_token(token)
        if album is None:
            raise HTTPException(status_code=404, detail="Share link not found")
        return album

    def _share_member_row_or_404(token: str, media_id: int):
        album = _album_for_token_or_404(token)
        if not album_store.is_member(album["id"], media_id):
            raise HTTPException(status_code=404, detail="Not in this album")
        return _media_row_or_404(media_id)

    @app.get("/share/{token}", response_class=HTMLResponse)
    async def share_page(token: str):
        album = album_store.by_token(token)
        if album is None:
            return HTMLResponse(_share_404_html(), status_code=404,
                                headers=_SHARE_NOBOT_HEADERS)
        items = album_store.items(album["id"])
        return HTMLResponse(_share_page_html(token, album["name"], items),
                            headers=_SHARE_NOBOT_HEADERS)

    @app.get("/share/{token}/media/{media_id}/thumb")
    async def share_thumb(token: str, media_id: int):
        row = _share_member_row_or_404(token, media_id)
        _assert_within_root(Path(row["path"]), row["root"])
        thumb = media_mod.media_thumbs_dir() / f"{media_id}.jpg"
        if not row["thumb_ready"] or not thumb.exists():
            try:
                media_mod.generate_thumbnail(Path(row["path"]), row["kind"], thumb)
                media_indexer.mark_thumb_ready(media_id)
            except Exception:
                raise HTTPException(status_code=404, detail="Thumbnail unavailable")
        return FileResponse(
            str(thumb), media_type="image/jpeg",
            headers={"Cache-Control": _THUMB_CACHE, **_SHARE_NOBOT_HEADERS},
        )

    @app.get("/share/{token}/media/{media_id}/preview")
    async def share_preview(token: str, media_id: int):
        row = _share_member_row_or_404(token, media_id)
        _assert_within_root(Path(row["path"]), row["root"])
        preview = media_mod.media_previews_dir() / f"{media_id}.jpg"
        if not preview.exists():
            try:
                media_mod.generate_preview(Path(row["path"]), preview)
            except Exception:
                raise HTTPException(status_code=404, detail="Preview unavailable")
        return FileResponse(
            str(preview), media_type="image/jpeg",
            headers={"Cache-Control": _THUMB_CACHE, **_SHARE_NOBOT_HEADERS},
        )

    @app.get("/share/{token}/media/{media_id}/stream")
    async def share_stream(token: str, media_id: int):
        row = _share_member_row_or_404(token, media_id)
        if row["kind"] != "video":
            raise HTTPException(status_code=404, detail="Not a video")
        _assert_within_root(Path(row["path"]), row["root"])
        # Browsers (unlike the app's ExoPlayer) cannot fall back on non-H.264
        # codecs (e.g. HEVC), so the public page serves the original ONLY when it
        # is KNOWN web-safe (video_websafe == 1); 0 or not-yet-probed NULL are
        # transcoded to an H.264/AAC proxy. Requires ffmpeg/ffprobe on the server.
        if row["video_websafe"] != 1:
            try:
                # Off the event loop: a cache miss runs a blocking ffmpeg encode,
                # which would otherwise stall the single worker for every client.
                proxy = await run_in_threadpool(
                    media_indexer.ensure_proxy, media_id, Path(row["path"])
                )
            except Exception:
                logger.warning(
                    "share stream: transcode failed for media %s (%s)",
                    media_id, row["path"], exc_info=True,
                )
                raise HTTPException(status_code=503, detail="Transcode unavailable")
            resp = _serve_with_range(proxy)
        else:
            resp = _serve_with_range(Path(row["path"]))
        resp.headers.update(_SHARE_NOBOT_HEADERS)
        return resp
