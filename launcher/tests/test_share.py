"""Tests for the public share routes (token + membership IS the auth).

These routes carry NO device token: the share token resolves to an album and
the requested media_id must be a member. Non-member ids return 404 (not 403) so
the public surface never leaks which media ids exist. Patterns mirror
``test_media_serving.py`` / ``test_media_video.py`` (TestClient + env-var harness).
"""
from __future__ import annotations

import os
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image


def _make_image(path: Path, size=(80, 60), color=(10, 200, 50)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)


def _make_video(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def _build_app(tmp_path: Path, folders):
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    os.environ["MEDIA_DB"] = str(tmp_path / "media.db")
    os.environ["MEDIA_THUMBS_DIR"] = str(tmp_path / "thumbs")
    os.environ["MEDIA_PROXIES_DIR"] = str(tmp_path / "proxies")
    os.environ["MEDIA_PREVIEWS_DIR"] = str(tmp_path / "previews")
    (configs / "server.yaml").write_text(
        yaml.safe_dump({"media_library": {"enabled": True,
                                          "folders": [str(f) for f in folders],
                                          "schedule": "0 2 * * *"}}),
        encoding="utf-8",
    )
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=lambda p: set())


def _index(app) -> None:
    # Skip real thumbnailing on fake bytes; serving routes regenerate lazily.
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    app.state.media_indexer.build()


def _image_ids(app):
    return [r["id"] for r in app.state.media_indexer.list_all()
            if r["kind"] == "image"]


def _video_id(app):
    for r in app.state.media_indexer.list_all():
        if r["kind"] == "video":
            return r["id"]
    raise AssertionError("no video indexed")


def _shared_album(app, media_ids):
    """Create an album with ``media_ids`` and share it; return the token."""
    store = app.state.album_store
    album = store.create("Trip to the lake", created_by="Pixel")
    store.add_items(album["id"], media_ids)
    token = store.share(album["id"])
    return album["id"], token


VIDEO_BYTES = bytes(range(256)) * 8  # 2048 deterministic bytes


def test_share_page_shows_album_name_and_grid_no_creator(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    ids = _image_ids(app)
    _, token = _shared_album(app, ids)
    client = TestClient(app)

    resp = client.get(f"/share/{token}")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    body = resp.text
    assert "Trip to the lake" in body
    # Thumbnail grid present for the member media.
    for mid in ids:
        assert f"/share/{token}/media/{mid}/thumb" in body
    # No creator / device info leaks onto the public page.
    assert "Pixel" not in body
    assert "created_by" not in body


def test_share_page_uses_relative_media_urls(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    ids = _image_ids(app)
    _, token = _shared_album(app, ids)
    client = TestClient(app)

    body = client.get(f"/share/{token}").text
    mid = ids[0]
    # Internal asset URLs are relative (reverse-proxy safety, §3.12) — no host.
    assert f'"/share/{token}/media/{mid}/thumb"' in body
    assert "http://" not in body
    assert "https://" not in body
    assert "testserver" not in body


def test_share_page_unknown_token_clean_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)

    resp = client.get("/share/nope-no-such-token")
    assert resp.status_code == 404
    assert "text/html" in resp.headers["content-type"]
    # Clean friendly page, not a stack trace.
    assert "Traceback" not in resp.text
    assert "not found" in resp.text.lower()


def test_share_page_revoked_token_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    store = app.state.album_store
    album = store.create("Trip", created_by="Pixel")
    store.add_items(album["id"], _image_ids(app))
    token = store.share(album["id"])
    store.revoke(album["id"])
    client = TestClient(app)

    resp = client.get(f"/share/{token}")
    assert resp.status_code == 404
    assert "text/html" in resp.headers["content-type"]


def test_share_page_empty_album_valid_with_empty_state(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    store = app.state.album_store
    album = store.create("Empty one", created_by="Pixel")
    token = store.share(album["id"])
    client = TestClient(app)

    resp = client.get(f"/share/{token}")
    assert resp.status_code == 200
    assert "Empty one" in resp.text
    assert "no photos" in resp.text.lower()


def test_share_thumb_and_preview_serve_bytes_no_token(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(800, 400))
    app = _build_app(tmp_path, [root])
    # Real thumbnailing here so the helpers produce JPEGs.
    app.state.media_indexer.build()
    ids = _image_ids(app)
    _, token = _shared_album(app, ids)
    client = TestClient(app)
    mid = ids[0]

    thumb = client.get(f"/share/{token}/media/{mid}/thumb")
    assert thumb.status_code == 200
    assert thumb.headers["content-type"] == "image/jpeg"
    assert len(thumb.content) > 0

    preview = client.get(f"/share/{token}/media/{mid}/preview")
    assert preview.status_code == 200
    assert preview.headers["content-type"] == "image/jpeg"
    assert len(preview.content) > 0


def test_share_stream_honours_range_206(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    # A KNOWN web-safe (H.264/AAC) video is served directly, so Range applies to
    # the original bytes.
    app.state.media_indexer._conn().execute(
        "UPDATE media SET video_websafe=1 WHERE id=?", (mid,)
    )
    app.state.media_indexer._conn().commit()
    _, token = _shared_album(app, [mid])
    client = TestClient(app)

    resp = client.get(f"/share/{token}/media/{mid}/stream",
                      headers={"Range": "bytes=10-19"})
    assert resp.status_code == 206
    assert resp.content == VIDEO_BYTES[10:20]
    assert resp.headers["content-range"] == f"bytes 10-19/{len(VIDEO_BYTES)}"
    assert resp.headers["accept-ranges"] == "bytes"


def test_share_stream_not_probed_video_transcodes_for_browser(tmp_path):
    # Regression: a not-yet-probed video (video_websafe NULL — e.g. indexed when
    # ffprobe was unavailable) must be transcoded to H.264 for the browser, never
    # served raw. A browser can't decode HEVC and would play audio only; ExoPlayer
    # (the app route) can, which is why the public route is stricter.
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    assert app.state.media_indexer.get(mid)["video_websafe"] is None
    proxy_bytes = b"PROXYDATA" * 50

    def fake_transcode(src, dest):
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(proxy_bytes)

    app.state.media_indexer.set_transcoder(fake_transcode)
    _, token = _shared_album(app, [mid])
    client = TestClient(app)

    resp = client.get(f"/share/{token}/media/{mid}/stream",
                      headers={"Range": "bytes=0-8"})
    assert resp.status_code == 206
    assert resp.content == proxy_bytes[0:9]
    assert (tmp_path / "proxies" / f"{mid}.mp4").exists()


def test_share_stream_nonwebsafe_serves_transcoded_proxy(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    # Mark known-non-web-safe so the transcode-on-demand path is taken.
    app.state.media_indexer._conn().execute(
        "UPDATE media SET video_websafe=0 WHERE id=?", (mid,)
    )
    app.state.media_indexer._conn().commit()
    proxy_bytes = b"PROXYDATA" * 50

    def fake_transcode(src, dest):
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(proxy_bytes)

    app.state.media_indexer.set_transcoder(fake_transcode)
    _, token = _shared_album(app, [mid])
    client = TestClient(app)

    resp = client.get(f"/share/{token}/media/{mid}/stream",
                      headers={"Range": "bytes=0-8"})
    assert resp.status_code == 206
    assert resp.content == proxy_bytes[0:9]
    assert (tmp_path / "proxies" / f"{mid}.mp4").exists()


def test_share_non_member_media_404_not_403_on_byte_routes(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "in.jpg", size=(80, 60))
    _make_image(root / "out.jpg", size=(80, 60), color=(5, 5, 5))
    app = _build_app(tmp_path, [root])
    app.state.media_indexer.build()
    ids = _image_ids(app)
    member, non_member = ids[0], ids[1]
    # Album contains only `member`; `non_member` exists but is not a member.
    _, token = _shared_album(app, [member])
    client = TestClient(app)

    for suffix in ("thumb", "preview", "stream"):
        resp = client.get(f"/share/{token}/media/{non_member}/{suffix}")
        # 404 (not 403) so we never leak which ids exist.
        assert resp.status_code == 404, suffix


def test_share_byte_routes_unknown_token_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    app.state.media_indexer.build()
    mid = _image_ids(app)[0]
    client = TestClient(app)

    for suffix in ("thumb", "preview", "stream"):
        resp = client.get(f"/share/bogus-token/media/{mid}/{suffix}")
        assert resp.status_code == 404, suffix


def test_share_byte_routes_revoked_token_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    app.state.media_indexer.build()
    mid = _image_ids(app)[0]
    store = app.state.album_store
    album = store.create("Trip", created_by="Pixel")
    store.add_items(album["id"], [mid])
    token = store.share(album["id"])
    store.revoke(album["id"])
    client = TestClient(app)

    for suffix in ("thumb", "preview", "stream"):
        resp = client.get(f"/share/{token}/media/{mid}/{suffix}")
        assert resp.status_code == 404, suffix


# -- bot protection (Tier 1): noindex / no-referrer / robots.txt -------------

def test_share_page_carries_nobot_headers_and_meta(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    _, token = _shared_album(app, _image_ids(app))
    client = TestClient(app)

    resp = client.get(f"/share/{token}")
    assert resp.status_code == 200
    assert "noindex" in resp.headers["x-robots-tag"]
    assert "noimageindex" in resp.headers["x-robots-tag"]
    assert resp.headers["referrer-policy"] == "no-referrer"
    # Belt-and-suspenders meta tags in the HTML head.
    assert '<meta name="robots" content="noindex,nofollow">' in resp.text
    assert '<meta name="referrer" content="no-referrer">' in resp.text


def test_share_404_page_carries_nobot_headers(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)

    resp = client.get("/share/no-such-token")
    assert resp.status_code == 404
    assert "noindex" in resp.headers["x-robots-tag"]
    assert resp.headers["referrer-policy"] == "no-referrer"


def test_share_byte_routes_carry_nobot_headers(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(800, 400))
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    app.state.media_indexer.build()
    img_id = _image_ids(app)[0]
    vid_id = _video_id(app)
    app.state.media_indexer._conn().execute(
        "UPDATE media SET video_websafe=1 WHERE id=?", (vid_id,)
    )
    app.state.media_indexer._conn().commit()
    _, token = _shared_album(app, [img_id, vid_id])
    client = TestClient(app)

    for suffix in ("thumb", "preview"):
        resp = client.get(f"/share/{token}/media/{img_id}/{suffix}")
        assert resp.status_code == 200, suffix
        assert "noindex" in resp.headers["x-robots-tag"], suffix
        assert resp.headers["referrer-policy"] == "no-referrer", suffix

    stream = client.get(f"/share/{token}/media/{vid_id}/stream")
    assert stream.status_code == 200
    assert "noindex" in stream.headers["x-robots-tag"]
    assert stream.headers["referrer-policy"] == "no-referrer"


def test_share_page_has_pager_chrome_and_keyboard_handlers(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    _make_image(root / "b.jpg", color=(5, 5, 5))
    app = _build_app(tmp_path, [root])
    _index(app)
    _, token = _shared_album(app, _image_ids(app))
    client = TestClient(app)

    body = client.get(f"/share/{token}").text
    # Pager chrome: prev / next / close buttons + a position counter, all in a
    # modal dialog so the lightbox is a real pager, not a single-item viewer.
    assert 'role="dialog"' in body
    assert 'class="lb-btn lb-nav lb-prev"' in body
    assert 'class="lb-btn lb-nav lb-next"' in body
    assert 'class="lb-btn lb-close"' in body
    assert 'class="lb-counter"' in body
    # Keyboard navigation: arrows page, Escape closes.
    assert "ArrowLeft" in body
    assert "ArrowRight" in body
    assert "Escape" in body


def test_robots_txt_disallows_share(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    client = TestClient(app)

    resp = client.get("/robots.txt")
    assert resp.status_code == 200
    assert "text/plain" in resp.headers["content-type"]
    assert "Disallow: /share/" in resp.text
