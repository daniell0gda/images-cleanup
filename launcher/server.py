"""Launcher FastAPI server — management UI for image-sorter."""
from __future__ import annotations

import hmac
import logging
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import Request

from .api_models import DbRefreshSettings as _DbRefreshSettings
from .api_models import DeviceRequest as _DeviceRequest
from .api_models import Identity as _Identity
from .api_models import JobRequest as _JobRequest
from .api_models import MediaLibrarySettings as _MediaLibrarySettings
from .api_models import ProfileRequest as _ProfileRequest
from .api_models import SessionRequest as _SessionRequest
from .api_models import SettingsRequest as _SettingsRequest
from .media_http import assert_within_root as _assert_within_root
from .media_http import decode_cursor as _decode_cursor
from .media_http import encode_cursor as _encode_cursor
from .media_http import parse_from_date as _parse_from_date

logger = logging.getLogger(__name__)


def _configs_dir() -> Path:
    return Path(os.environ.get("CONFIGS_DIR", "./configs"))


def _inbox_base() -> Path:
    return Path(os.environ.get("INBOX_BASE", "./data/inbox"))


def _sync_db_path() -> Path:
    return Path(os.environ.get("SYNC_DB", "./data/sync.db"))


# Largest single chunk body the server will append, in bytes. Bounds per-request
# memory and rejects pathological uploads; overridable via the env for tuning.
_DEFAULT_MAX_CHUNK_BYTES = 16 * 1024 * 1024


def _max_chunk_bytes() -> int:
    return int(os.environ.get("MAX_CHUNK_BYTES", _DEFAULT_MAX_CHUNK_BYTES))


def _launcher_public_port() -> str:
    """Host port the launcher UI is reachable on (may differ from the internal
    7000 when remapped in Docker)."""
    return os.environ.get("LAUNCHER_PUBLIC_PORT", "7000")


def _sorter_public_port() -> int:
    """Host port the sorter UI is reachable on (may differ from the internal
    8080 when remapped in Docker)."""
    return int(os.environ.get("SORTER_PUBLIC_PORT", "8080"))


_CONFIG_RE = re.compile(r"^config_(.+?)_(groupby|similarity)\.yaml$")


def _scan_users(configs_dir: Path) -> list[dict]:
    users: dict[str, list[str]] = {}
    if configs_dir.is_dir():
        for f in configs_dir.iterdir():
            m = _CONFIG_RE.match(f.name)
            if m:
                user, mode = m.group(1), m.group(2)
                users.setdefault(user, []).append(mode)
    return [{"user": u, "modes": sorted(modes)} for u, modes in sorted(users.items())]


class _JobState:
    proc: subprocess.Popen | None = None
    user: str | None = None
    mode: str | None = None

    def is_running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def clear_if_done(self) -> None:
        if self.proc is not None and self.proc.poll() is not None:
            self.proc = None
            self.user = None
            self.mode = None


def shutdown_handler(state: _JobState) -> None:
    """Terminate the child subprocess if it's still running."""
    if state.proc is not None and state.proc.poll() is None:
        state.proc.terminate()
        try:
            state.proc.wait(timeout=5)
        except Exception:
            pass


# Headers a reverse proxy (e.g. Traefik) sets on every forwarded request. Their
# presence marks a request as coming from the public internet rather than a
# direct LAN connection to the published port. A client on the LAN cannot make a
# proxied request lose these, and a public client cannot strip them, so absence
# reliably means "local".
_PROXY_HEADERS = ("x-forwarded-for", "x-forwarded-host", "forwarded")


def _is_proxied(headers) -> bool:
    return any(h in headers for h in _PROXY_HEADERS)


def _is_public_path(method: str, path: str) -> bool:
    """Whether a request may be served to clients arriving through the public
    reverse proxy. Everything not listed here is management/admin and is reachable
    only from a direct (non-proxied) LAN connection.

    The public surface is exactly what the Android app and shared-album links
    need: the reachability probe, the device-token-protected media/albums/upload
    lanes, the two unauthenticated pairing steps (register + status poll), and the
    tokenized /share links. Device *listing*, *approve* and *revoke* are excluded
    on purpose — approving a device is the trust decision itself and must stay on
    the LAN, alongside the whole management UI (``/``), jobs and settings.
    """
    if path == "/api/ping":
        return True
    if path.startswith("/share/"):
        return True
    if path == "/api/albums" or path.startswith("/api/albums/"):
        return True
    # App media content, but never the admin build endpoints under the same tree.
    if path.startswith("/api/media/build"):
        return False
    if path == "/api/media" or path.startswith("/api/media/"):
        return True
    if path == "/api/sync/profiles":
        return True
    if path in ("/api/sync/reconcile", "/api/sync/verify"):
        return True
    if path == "/api/sync/sessions" or path.startswith("/api/sync/sessions/"):
        return True
    if method == "POST" and path == "/api/sync/devices":
        return True
    if method == "GET" and path.startswith("/api/sync/devices/") and path.endswith("/status"):
        return True
    return False


def create_app(
    state: _JobState | None = None,
    dist_dir: Path | None = None,
    sync_detect_tags=None,
    sync_scheduler=None,
):
    from fastapi import FastAPI, HTTPException
    from fastapi.staticfiles import StaticFiles

    if state is None:
        state = _JobState()

    app = FastAPI()

    @app.middleware("http")
    async def _guard_admin_surface(request: Request, call_next):
        # Requests forwarded by the public reverse proxy may only touch the
        # public surface; the management UI + admin API answer 404 (hidden) so
        # they are reachable only from a direct LAN connection.
        if _is_proxied(request.headers) and not _is_public_path(
            request.method, request.url.path
        ):
            from fastapi.responses import JSONResponse

            return JSONResponse(status_code=404, content={"detail": "Not Found"})
        return await call_next(request)

    @app.get("/api/ping")
    async def get_ping():
        # Unauthenticated reachability probe used by the Android app's connect
        # screen. Public on purpose; reveals nothing about the server.
        return {"status": "ok"}

    @app.get("/api/config")
    async def get_config():
        return {"sorter_port": _sorter_public_port()}

    @app.get("/api/users")
    async def get_users():
        return _scan_users(_configs_dir())

    @app.get("/api/status")
    async def get_status():
        state.clear_if_done()
        if not state.is_running():
            return {"status": "idle"}
        return {"status": "running", "user": state.user, "mode": state.mode}

    @app.post("/api/jobs")
    async def post_jobs(req: _JobRequest):
        state.clear_if_done()
        if state.is_running():
            raise HTTPException(status_code=409, detail="A job is already running")

        configs_dir = _configs_dir()
        config_file = configs_dir / f"config_{req.user}_{req.mode}.yaml"
        if not config_file.exists():
            raise HTTPException(status_code=404, detail=f"Config not found: {config_file}")

        env = {**os.environ, "LAUNCHER_PUBLIC_PORT": _launcher_public_port()}
        state.proc = subprocess.Popen(
            [sys.executable, "-m", "imagesorter", "--config", str(config_file)],
            env=env,
        )
        state.user = req.user
        state.mode = req.mode

        return {"status": "running", "user": state.user, "mode": state.mode}

    @app.delete("/api/jobs")
    async def delete_jobs():
        shutdown_handler(state)
        state.proc = None
        state.user = None
        state.mode = None
        return {"status": "idle"}

    _register_sync_routes(app, sync_detect_tags, sync_scheduler)

    @app.get("/robots.txt")
    async def robots_txt():
        # Keep crawlers off the public share surface (and the rest of the app).
        from fastapi.responses import PlainTextResponse
        return PlainTextResponse("User-agent: *\nDisallow: /share/\nDisallow: /\n")

    if dist_dir is not None and dist_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(dist_dir), html=True), name="frontend")

    return app


def _read_sync_raw() -> dict:
    """Return the ``sync:`` sub-dict from ``server.yaml`` (empty when absent)."""
    import yaml

    from . import settings as settings_mod

    path = settings_mod.settings_path(_configs_dir())
    if not path.is_file():
        return {}
    try:
        loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError):
        return {}
    if isinstance(loaded, dict) and isinstance(loaded.get("sync"), dict):
        return loaded["sync"]
    return {}


def _sync_configured() -> bool:
    """Whether the server has a usable sync template: a ``sync:`` section with a
    non-empty ``tag_groups`` list."""
    return bool(_read_sync_raw().get("tag_groups"))


def _load_sync_config(profile_id: str):
    """Build the shared placement Config from the ``sync:`` section of
    ``server.yaml``.

    ``profile_id`` is accepted for call-site compatibility but ignored: every
    profile places files into the same destinations defined by the server's
    hand-authored ``sync:`` block, so two sessions opened with different profiles
    share one placement config."""
    from imagesorter import config as config_mod

    return config_mod.from_raw(_read_sync_raw())


def _default_detect_tags():
    """Build the production tag detector backed by a warm YOLO model."""
    from ultralytics import YOLO
    model = YOLO("yolo11s.pt")

    def detect(path: Path) -> set:
        result = model([str(path)], verbose=False)[0]
        cls_tensor = result.boxes.cls if result.boxes is not None else []
        return {model.names[int(c)].lower() for c in cls_tensor}

    return detect


class _DeferredDetect:
    """Lazily builds the YOLO-backed detector on first use, so creating the app
    (and the test suite) never downloads or loads the model."""

    def __init__(self):
        self._detect = None

    def __call__(self, path: Path) -> set:
        if self._detect is None:
            self._detect = _default_detect_tags()
        return self._detect(path)


_SYNC_TTL = timedelta(hours=24)

# Timeline page sizing: keyset pages are lightweight, but cap the page so a
# client can't request an unbounded scan.
_MEDIA_PAGE_DEFAULT = 100
_MEDIA_PAGE_MAX = 200


def _register_sync_routes(app, detect_tags=None, scheduler=None) -> None:
    """Mount the android-phone-sync endpoints onto the launcher app.

    On startup the lane reconciles complete-but-unprocessed sessions and runs a
    janitor pass; ``scheduler`` (``(interval_seconds, fn) -> None``) registers a
    recurring janitor so leftovers are reaped on a timer in production.
    """
    from fastapi import Header, HTTPException
    from . import media as media_mod
    from . import settings as settings_mod
    from . import sync as sync_mod
    from .albums import AlbumStore
    from .albums_api import register_album_routes

    store = sync_mod.SyncStore(_sync_db_path())
    sessions = sync_mod.SessionManager(_inbox_base())
    lane = sync_mod.SyncLane(
        store, sessions, _load_sync_config,
        detect_tags if detect_tags is not None else _DeferredDetect(),
    )
    app.state.sync_lane = lane

    lane.startup_reconcile(_SYNC_TTL)
    lane.janitor(_SYNC_TTL)
    if scheduler is not None:
        scheduler(_SYNC_TTL.total_seconds(), lambda: lane.janitor(_SYNC_TTL))

    # Periodic synced-file index refresh: prune rows whose destination file was
    # deleted outside the app so the next reconcile re-uploads them. The cron
    # thread only runs in production (when a scheduler is wired); tests drive the
    # refresh through the endpoint instead of spinning a thread.
    app.state.db_last_refresh = None

    def _run_refresh() -> dict:
        result = store.refresh_index()
        result["at"] = datetime.now(timezone.utc).isoformat()
        app.state.db_last_refresh = result
        return result

    db_cron = _CronScheduler(_run_refresh) if scheduler is not None else None
    app.state.db_cron = db_cron
    if db_cron is not None:
        cfg = settings_mod.load_settings(_configs_dir())["db_refresh"]
        db_cron.reschedule(cfg["schedule"], cfg["enabled"])

    # Media index: backs the /api/media/* content routes. Folders come from the
    # current media_library settings; the thumbnail generator is wired so a build
    # produces the 320px cache and the endpoints can lazily fall back on it.
    media_folders = settings_mod.load_settings(_configs_dir())["media_library"]["folders"]
    media_indexer = media_mod.MediaIndexer(
        db_path=media_mod.media_db_path(),
        thumbs_dir=media_mod.media_thumbs_dir(),
        proxies_dir=media_mod.media_proxies_dir(),
        folders=media_folders,
        thumbnail=media_mod.make_thumbnail_hook(media_mod.media_thumbs_dir()),
        profile_for=store.profile_for_path,
    )
    app.state.media_indexer = media_indexer
    # Feed synced files into the gallery index the moment they are placed, so a
    # phone backup shows up without waiting for the next scheduled build.
    lane.media_indexer = media_indexer

    album_store = AlbumStore(media_mod.media_db_path())
    app.state.album_store = album_store

    # Live last-build summary surfaced by /api/settings (media_build). Null until
    # a build runs; updated by every build (scheduled, forced, or status poll).
    app.state.media_build_status = None

    def _run_media_build() -> dict:
        snapshot = media_indexer.build(force=True)
        app.state.media_build_status = snapshot
        return snapshot

    # Media gallery index: built on its own cron, reconfigured on settings save.
    media_cron = _CronScheduler(_run_media_build) if scheduler is not None else None
    app.state.media_cron = media_cron
    if media_cron is not None:
        mcfg = settings_mod.load_settings(_configs_dir())["media_library"]
        media_cron.reschedule(mcfg["schedule"], mcfg["enabled"])

    def _settings_payload(s: dict) -> dict:
        dbr = s["db_refresh"]
        nxt = settings_mod.next_run(dbr["schedule"]) if dbr["enabled"] else None
        ml = s["media_library"]
        ml_next = settings_mod.next_run(ml["schedule"]) if ml["enabled"] else None
        return {
            "db_refresh": {**dbr, "next_run": nxt},
            "last_refresh": app.state.db_last_refresh,
            "media_library": {**ml, "next_run": ml_next},
            # Last-build summary; null until the media build subsystem exists.
            "media_build": getattr(app.state, "media_build_status", None),
            "public_base_url": s["public_base_url"],
        }

    def _profile_ids() -> set[str]:
        return {p["profile_id"] for p in store.list_profiles()}

    def _require_device(authorization: str | None):
        token = ""
        if authorization and authorization.lower().startswith("bearer "):
            token = authorization[7:].strip()
        device = store.device_for_token(token)
        if device is None:
            raise HTTPException(status_code=401, detail="Invalid or missing token")
        return device

    def _require_owned_session(device, session_id: str) -> None:
        """Reject access to a session the authed device does not own.

        Ownership is read from the durable session registry (not the on-disk
        folder) so the check survives session cleanup. An unknown session is a
        404; a known session owned by another device is a 403, and neither
        performs any write to the session.
        """
        owner = store.session_owner(session_id)
        if owner is None:
            raise HTTPException(status_code=404, detail="Unknown session")
        if owner != device["device_id"]:
            raise HTTPException(status_code=403, detail="Session belongs to another device")

    @app.get("/api/sync/devices")
    async def sync_list_devices():
        return store.list_devices()

    @app.post("/api/sync/devices")
    async def sync_register(req: _DeviceRequest):
        try:
            return store.register_device(req.device_id, req.name)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid device_id")

    @app.get("/api/sync/devices/{device_id}/status")
    async def sync_device_status(
        device_id: str,
        x_pairing_code: str | None = Header(default=None),
    ):
        device = store.get_device(device_id)
        if device is None:
            raise HTTPException(status_code=404, detail="Unknown device")
        result = {"status": device["status"]}
        # The token is a credential: only hand it back to a caller who proves
        # possession of the device's pairing code. The code is compared with a
        # constant-time check to avoid a timing side-channel, and is never
        # echoed back in the response body (it is a secret).
        if device["status"] == "trusted":
            stored_code = device["pairing_code"]
            if (
                x_pairing_code is not None
                and stored_code is not None
                and hmac.compare_digest(x_pairing_code, stored_code)
            ):
                result["token"] = device["token"]
        return result

    @app.post("/api/sync/devices/{device_id}/approve")
    async def sync_device_approve(device_id: str):
        token = store.approve_device(device_id)
        if token is None:
            raise HTTPException(status_code=404, detail="Unknown device")
        return {"status": "trusted", "token": token}

    @app.post("/api/sync/devices/{device_id}/revoke")
    async def sync_device_revoke(device_id: str):
        store.revoke_device(device_id)
        return {"status": "revoked"}

    @app.get("/api/sync/profiles")
    async def sync_profiles():
        return store.list_profiles()

    @app.post("/api/sync/profiles", status_code=201)
    async def sync_create_profile(req: _ProfileRequest):
        try:
            return store.create_profile(req.name)
        except sync_mod.DuplicateProfileError as exc:
            raise HTTPException(status_code=409, detail=str(exc))
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))

    @app.delete("/api/sync/profiles/{profile_id}")
    async def sync_delete_profile(profile_id: str):
        store.delete_profile(profile_id)
        return {"status": "deleted"}

    @app.post("/api/sync/reconcile")
    async def sync_reconcile(
        items: list[_Identity],
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        uploaded = sessions.uploaded_offsets(device["device_id"])
        results = []
        for i in items:
            synced = store.is_synced(i.name, i.created_on, i.size)
            row = {
                "name": i.name,
                "created_on": i.created_on,
                "size": i.size,
                "already_synced": synced,
                "uploaded_offset": 0,
                "resume_session_id": None,
                "resume_file_id": None,
            }
            if not synced:
                resume = uploaded.get((i.name, i.created_on, i.size))
                if resume is not None:
                    session_id, file_id, offset = resume
                    row["resume_session_id"] = session_id
                    row["resume_file_id"] = file_id
                    row["uploaded_offset"] = offset
            results.append(row)
        return {"results": results}

    @app.post("/api/sync/sessions")
    async def sync_open_session(
        req: _SessionRequest,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        # Sync unconfigured (no template) is a 503 and must be reported BEFORE
        # the unknown-profile 404, so a phone gets "not configured" rather than
        # a misleading "unknown profile" when the server has no sync: block.
        if not _sync_configured():
            raise HTTPException(status_code=503, detail="Sync is not configured")
        if req.profile_id not in _profile_ids():
            raise HTTPException(status_code=404, detail="Unknown profile")
        # The sync lane never places unclassified images (they are discarded to
        # the phone's Not People review), so the profile's unclassified.enabled
        # setting no longer gates session-open.
        session_id = sessions.create_session(
            device["device_id"], req.profile_id, req.force_place
        )
        store.record_session(session_id, device["device_id"], req.profile_id)
        return {"session_id": session_id}

    @app.get("/api/sync/sessions/{session_id}/files/{file_id}")
    async def sync_file_offset(
        session_id: str,
        file_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        return {"offset": sessions.file_offset(session_id, file_id)}

    @app.post("/api/sync/sessions/{session_id}/files")
    async def sync_upload_chunk(
        session_id: str,
        request: Request,
        authorization: str | None = Header(default=None),
        file_id: str = Header(alias="File-Id"),
        file_name: str = Header(alias="File-Name"),
        file_created_on: str = Header(alias="File-Created-On"),
        file_size: int = Header(alias="File-Size"),
        file_mime_type: str = Header(alias="File-Mime-Type"),
        upload_offset: int = Header(alias="Upload-Offset", default=0),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        meta = sync_mod.FileMeta(file_name, file_created_on, file_size, file_mime_type)
        # Reject an oversize chunk BEFORE appending any bytes. Check the declared
        # Content-Length first so we can refuse without buffering the body, then
        # guard the actual length in case the header lied.
        max_chunk = _max_chunk_bytes()
        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > max_chunk:
            raise HTTPException(status_code=413, detail="Chunk too large")
        data = await request.body()
        if len(data) > max_chunk:
            raise HTTPException(status_code=413, detail="Chunk too large")
        try:
            new_offset = sessions.write_chunk(session_id, file_id, meta, upload_offset, data)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail="Unknown session")
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid file_id")
        return {"offset": new_offset, "length": file_size}

    @app.post("/api/sync/sessions/{session_id}/complete")
    async def sync_complete_session(
        session_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        # Idempotent: a session already processed (its folder cleaned up by the
        # first placement) reports complete on retry instead of 404.
        if store.has_outcomes(session_id):
            return {"status": "complete"}
        try:
            sessions.complete(session_id)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail="Unknown session")
        lane.process_session(session_id)
        return {"status": "complete"}

    @app.get("/api/sync/sessions/{session_id}/outcomes")
    async def sync_outcomes(
        session_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        return {"outcomes": lane.read_outcomes(session_id)}

    @app.post("/api/sync/verify")
    async def sync_verify(
        items: list[_Identity],
        authorization: str | None = Header(default=None),
    ):
        # Accepted trust boundary (wontfix): verify exposes per-identity presence
        # to any trusted device. This is not narrowed because identity presence is
        # already inferable through global dedup — is_synced and reconcile's
        # already_synced are global across all devices (PK is (name, created_on,
        # size)), so a trusted device can already learn presence via reconcile.
        # Hiding it here would not change that same trust boundary.
        _require_device(authorization)
        results = []
        for i in items:
            stored = store.stored_path_for(i.name, i.created_on, i.size)
            present = bool(stored) and Path(stored).exists()
            results.append({
                "name": i.name,
                "created_on": i.created_on,
                "size": i.size,
                "present": present,
            })
        return {"results": results}

    # -- media gallery content (device-facing) ---------------------------
    # Auth (device bearer token, §4.9/Decision 14) is wired in Cluster 5; these
    # handlers serve the correct bytes/format/404 so the gate can wrap them.

    from fastapi.responses import FileResponse, Response
    from starlette.concurrency import run_in_threadpool

    _THUMB_CACHE = "public, max-age=31536000, immutable"

    # Public share surface is token-gated but otherwise unauthenticated, so keep
    # well-behaved bots out: noindex/noimageindex stops search engines indexing
    # the page or its JPEGs, and no-referrer prevents the secret token leaking in
    # the Referer header when a viewer clicks an outbound link. These cover the
    # HTML page AND the image byte routes (an image URL can leak on its own).
    _SHARE_NOBOT_HEADERS = {
        "X-Robots-Tag": "noindex, nofollow, noarchive, noimageindex",
        "Referrer-Policy": "no-referrer",
    }

    def _media_row_or_404(media_id: int):
        row = media_indexer.get(media_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Unknown media")
        return row

    def _media_item(row: dict) -> dict:
        """Lightweight timeline item (no file bytes) for the /api/media page."""
        return {
            "id": row["id"],
            "kind": row["kind"],
            "date_taken": row["date_taken"],
            "width": row["width"],
            "height": row["height"],
            "profile": row["profile"],
        }

    @app.get("/api/media")
    async def media_timeline(
        cursor: str | None = None,
        limit: int = _MEDIA_PAGE_DEFAULT,
        from_date: str | None = None,
        before: str | None = None,
        profile: str | None = None,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        limit = max(1, min(limit, _MEDIA_PAGE_MAX))

        if before is not None:
            # Upward (prepend) page for bidirectional seek: photos strictly newer
            # than the anchor, ascending so the closest-newer photo comes first.
            before_key = _decode_cursor(before)
            rows = media_indexer.timeline(limit=limit + 1, before=before_key, profile=profile)
            has_more = len(rows) > limit
            page = rows[:limit]
            prev_cursor = (
                _encode_cursor(page[-1]["date_taken"], page[-1]["id"])
                if has_more and page else None
            )
            return {
                "items": [_media_item(r) for r in page],
                "next_cursor": None,
                "prev_cursor": prev_cursor,
            }

        on_or_before = _parse_from_date(from_date) if from_date is not None else None
        after = _decode_cursor(cursor) if cursor else None
        # Fetch one extra row to know whether a further (older) page exists.
        rows = media_indexer.timeline(limit=limit + 1, after=after, on_or_before=on_or_before,
                                      profile=profile)
        has_more = len(rows) > limit
        page = rows[:limit]
        next_cursor = (
            _encode_cursor(page[-1]["date_taken"], page[-1]["id"])
            if has_more and page else None
        )
        # On an initial/seek load (no append cursor), expose a prev_cursor when
        # newer photos exist above the first item so the client can page upward.
        prev_cursor = None
        if after is None and page:
            newer = media_indexer.timeline(
                limit=1, before=(page[0]["date_taken"], page[0]["id"]), profile=profile
            )
            if newer:
                prev_cursor = _encode_cursor(page[0]["date_taken"], page[0]["id"])
        return {
            "items": [_media_item(r) for r in page],
            "next_cursor": next_cursor,
            "prev_cursor": prev_cursor,
        }

    @app.get("/api/media/dates")
    async def media_dates(authorization: str | None = Header(default=None)):
        _require_device(authorization)
        return media_indexer.available_dates()

    def _serve_with_range(path: Path, media_type: str = "video/mp4") -> Response:
        """Serve ``path`` as a Range-aware, chunked stream (200 full / 206 partial).

        Starlette's ``FileResponse`` reads the request's ``Range`` header from the
        ASGI scope, answers 206/partial or 200/full, and streams the file in 64 KiB
        chunks off the event loop. Playback therefore starts on the first chunk
        instead of after the whole file is buffered into memory — the old behaviour
        that made a large clip sit on a spinner even over local wifi.
        """
        return FileResponse(str(path), media_type=media_type)

    @app.get("/api/media/{media_id}/thumb")
    async def media_thumb(media_id: int, authorization: str | None = Header(default=None)):
        _require_device(authorization)
        row = _media_row_or_404(media_id)
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
            headers={"Cache-Control": _THUMB_CACHE},
        )

    @app.get("/api/media/{media_id}/preview")
    async def media_preview(media_id: int, authorization: str | None = Header(default=None)):
        _require_device(authorization)
        row = _media_row_or_404(media_id)
        _assert_within_root(Path(row["path"]), row["root"])
        preview = media_mod.media_previews_dir() / f"{media_id}.jpg"
        if not preview.exists():
            try:
                media_mod.generate_preview(Path(row["path"]), preview)
            except Exception:
                raise HTTPException(status_code=404, detail="Preview unavailable")
        return FileResponse(
            str(preview), media_type="image/jpeg",
            headers={"Cache-Control": _THUMB_CACHE},
        )

    @app.get("/api/media/{media_id}/stream")
    async def media_stream(
        media_id: int,
        authorization: str | None = Header(default=None),
    ):
        _require_device(authorization)
        row = _media_row_or_404(media_id)
        if row["kind"] != "video":
            raise HTTPException(status_code=404, detail="Not a video")
        _assert_within_root(Path(row["path"]), row["root"])
        # Web-safe (or not-yet-probed NULL) -> serve the original with Range.
        # Only a KNOWN-non-web-safe (video_websafe == 0) video is transcoded.
        if row["video_websafe"] == 0:
            try:
                # Off the event loop: a cache miss runs a blocking ffmpeg encode,
                # which would otherwise stall the single worker for every client.
                proxy = await run_in_threadpool(
                    media_indexer.ensure_proxy, media_id, Path(row["path"])
                )
            except Exception:
                logger.warning(
                    "stream: transcode failed for media %s (%s)",
                    media_id, row["path"], exc_info=True,
                )
                raise HTTPException(status_code=503, detail="Transcode unavailable")
            return _serve_with_range(proxy)
        return _serve_with_range(Path(row["path"]))

    # -- settings + manual index refresh (local management UI) -----------
    # These mirror the device-management routes: reachable from the launcher UI
    # on the local network, no device bearer token required.

    @app.get("/api/settings")
    async def get_settings():
        return _settings_payload(settings_mod.load_settings(_configs_dir()))

    @app.post("/api/settings")
    async def post_settings(req: _SettingsRequest):
        ml_in = req.media_library.model_dump() if req.media_library is not None else None
        # db_refresh is optional: a media-only save (the React media settings
        # page) keeps the stored db_refresh values and leaves its cron alone.
        if req.db_refresh is not None:
            db_enabled = req.db_refresh.enabled
            db_schedule = req.db_refresh.schedule
        else:
            stored_db = settings_mod.load_settings(_configs_dir())["db_refresh"]
            db_enabled = stored_db["enabled"]
            db_schedule = stored_db["schedule"]
        try:
            saved = settings_mod.save_settings(
                _configs_dir(),
                db_enabled,
                db_schedule,
                media_library=ml_in,
                public_base_url=req.public_base_url,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        if req.db_refresh is not None and app.state.db_cron is not None:
            app.state.db_cron.reschedule(
                saved["db_refresh"]["schedule"], saved["db_refresh"]["enabled"]
            )
        # save_settings only writes media_library when it was provided; merge in
        # the stored config so the payload (and cron) always see a full block.
        full = settings_mod.load_settings(_configs_dir())
        # Push the (possibly changed) folder list into the live indexer so a build
        # triggered after this save scans the new roots without a restart.
        media_indexer.set_folders(full["media_library"]["folders"])
        if app.state.media_cron is not None:
            app.state.media_cron.reschedule(
                full["media_library"]["schedule"], full["media_library"]["enabled"]
            )
        return _settings_payload(full)

    @app.post("/api/settings/refresh")
    async def post_refresh():
        return _run_refresh()

    @app.get("/api/media/build/status")
    async def get_media_build_status():
        snapshot = media_indexer.status()
        app.state.media_build_status = snapshot
        return snapshot

    @app.post("/api/media/build")
    async def post_media_build():
        # Force a build in a background daemon thread and return immediately
        # (after the build has entered, so the reported state is deterministic).
        # The indexer's single in-process lock makes a second trigger while a
        # build is in flight a no-op that returns the running status without
        # starting a second build.
        import threading

        if media_indexer.is_building():
            return media_indexer.status()

        done = threading.Event()

        def _run() -> None:
            try:
                _run_media_build()
            finally:
                done.set()

        threading.Thread(target=_run, daemon=True).start()
        # Spin briefly until the background build has entered (acquired the lock)
        # or already finished, so the reported state is deterministic. This waits
        # on the build *start*, never blocking on a long scan.
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            if media_indexer.is_building() or done.is_set():
                break
            time.sleep(0.001)
        return media_indexer.status()

    # -- albums + public share -------------------------------------------
    # Mounted from albums_api so the album/share surface lives in one module;
    # the shared helpers + stores it needs are passed in explicitly.
    register_album_routes(
        app,
        album_store=album_store,
        media_indexer=media_indexer,
        media_mod=media_mod,
        settings_mod=settings_mod,
        _configs_dir=_configs_dir,
        _require_device=_require_device,
        _media_item=_media_item,
        _media_row_or_404=_media_row_or_404,
        _assert_within_root=_assert_within_root,
        _serve_with_range=_serve_with_range,
        _THUMB_CACHE=_THUMB_CACHE,
        _SHARE_NOBOT_HEADERS=_SHARE_NOBOT_HEADERS,
    )


def _check_port(port: int = 7000) -> None:
    """Raise SystemExit if port is already in use."""
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind(("0.0.0.0", port))
        except OSError:
            print(
                f"Error: port {port} is already in use. "
                "Stop the process using that port and try again.",
                file=sys.stderr,
            )
            sys.exit(1)


class _CronScheduler:
    """Run ``fn`` on a cron schedule via a daemon thread.

    Rescheduling stops the running thread and starts a fresh one for the new
    expression, so a settings change from the UI takes effect without a restart.
    Disabling stops the thread entirely.
    """

    def __init__(self, fn):
        import threading

        self._fn = fn
        self._lock = threading.Lock()
        self._stop = None
        self.schedule = None
        self.enabled = False

    def reschedule(self, schedule: str, enabled: bool) -> None:
        import threading

        with self._lock:
            if self._stop is not None:
                self._stop.set()
                self._stop = None
            self.schedule = schedule
            self.enabled = enabled
            if not enabled:
                return
            stop = threading.Event()
            self._stop = stop
            threading.Thread(
                target=self._loop, args=(schedule, stop), daemon=True
            ).start()

    def _loop(self, schedule: str, stop) -> None:
        from croniter import croniter

        itr = croniter(schedule, datetime.now())
        while not stop.is_set():
            delay = (itr.get_next(datetime) - datetime.now()).total_seconds()
            if stop.wait(max(delay, 0.0)):
                return
            try:
                self._fn()
            except Exception:
                pass


def _recurring_timer(interval: float, fn) -> None:
    """Run ``fn`` every ``interval`` seconds on a daemon timer that reschedules
    itself after each tick."""
    import threading

    def tick() -> None:
        try:
            fn()
        finally:
            t = threading.Timer(interval, tick)
            t.daemon = True
            t.start()

    t = threading.Timer(interval, tick)
    t.daemon = True
    t.start()


def serve() -> None:
    """Start the launcher server on port 7000."""
    import atexit
    import uvicorn

    _check_port(7000)
    state = _JobState()
    atexit.register(shutdown_handler, state)
    dist_dir = Path(__file__).parent / "dist"
    app = create_app(state, dist_dir=dist_dir, sync_scheduler=_recurring_timer)
    uvicorn.run(app, host="0.0.0.0", port=7000, log_level="info")
