"""Tests for the media content endpoints (thumb / preview) on the launcher app."""
from __future__ import annotations

import io
import os
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image


def _make_image(path: Path, size=(800, 400), color=(10, 200, 50)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)


def _build_app(tmp_path: Path, folders):
    """Build the launcher app with media env vars under tmp_path and a media
    library configured for ``folders``."""
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


def _build_index(app) -> None:
    app.state.media_indexer.build()


def _ids(app):
    return [r["id"] for r in app.state.media_indexer.list_all()]


def _trust(client: TestClient, device_id="dev-1") -> str:
    """Register + approve a device and return its bearer token."""
    code = client.post(
        "/api/sync/devices", json={"device_id": device_id, "name": "Pixel"}
    ).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    return client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()["token"]


def _auth(client: TestClient) -> dict:
    return {"Authorization": f"Bearer {_trust(client)}"}


def test_thumb_returns_cached_jpeg(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(40, 40))
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)

    media_id = _ids(app)[0]
    resp = client.get(f"/api/media/{media_id}/thumb", headers=_auth(client))

    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/jpeg"
    with Image.open(io.BytesIO(resp.content)) as img:
        assert img.format == "JPEG"
        assert img.size == (320, 320)


def test_thumb_lazy_regenerates_when_cache_missing(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(40, 40))
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)
    media_id = _ids(app)[0]

    # Delete the cached thumb to force the lazy fallback.
    (tmp_path / "thumbs" / f"{media_id}.jpg").unlink()

    resp = client.get(f"/api/media/{media_id}/thumb", headers=_auth(client))
    assert resp.status_code == 200
    assert (tmp_path / "thumbs" / f"{media_id}.jpg").exists()
    with Image.open(io.BytesIO(resp.content)) as img:
        assert img.size == (320, 320)


def test_thumb_lazy_generates_when_thumb_not_ready(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(40, 40))
    app = _build_app(tmp_path, [root])
    # Index without thumbnails: clear the hook so thumb_ready stays 0.
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    _build_index(app)
    client = TestClient(app)
    media_id = _ids(app)[0]

    assert not (tmp_path / "thumbs" / f"{media_id}.jpg").exists()
    resp = client.get(f"/api/media/{media_id}/thumb", headers=_auth(client))
    assert resp.status_code == 200
    assert (tmp_path / "thumbs" / f"{media_id}.jpg").exists()


def test_thumb_unknown_id_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)

    resp = client.get("/api/media/999999/thumb", headers=_auth(client))
    assert resp.status_code == 404


def test_preview_returns_1600px_long_edge_jpeg(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(4000, 2000))
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)
    media_id = _ids(app)[0]

    resp = client.get(f"/api/media/{media_id}/preview", headers=_auth(client))
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/jpeg"
    with Image.open(io.BytesIO(resp.content)) as img:
        assert img.format == "JPEG"
        assert max(img.size) == 1600
    # Cached for the next request.
    assert (tmp_path / "previews" / f"{media_id}.jpg").exists()


def test_preview_cached_on_second_request(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(4000, 2000))
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)
    media_id = _ids(app)[0]

    headers = _auth(client)
    client.get(f"/api/media/{media_id}/preview", headers=headers)
    cached = tmp_path / "previews" / f"{media_id}.jpg"
    mtime = cached.stat().st_mtime_ns

    resp = client.get(f"/api/media/{media_id}/preview", headers=headers)
    assert resp.status_code == 200
    # Not regenerated: same cache file, untouched.
    assert cached.stat().st_mtime_ns == mtime


def test_preview_unknown_id_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)

    resp = client.get("/api/media/999999/preview", headers=_auth(client))
    assert resp.status_code == 404


def test_delete_removes_file_index_and_caches(tmp_path):
    root = tmp_path / "lib"
    src = root / "a.jpg"
    _make_image(src, size=(4000, 2000))
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)
    headers = _auth(client)
    media_id = _ids(app)[0]

    # Materialise the preview cache so the delete has one to clean up.
    client.get(f"/api/media/{media_id}/preview", headers=headers)
    thumb = tmp_path / "thumbs" / f"{media_id}.jpg"
    preview = tmp_path / "previews" / f"{media_id}.jpg"
    assert thumb.exists() and preview.exists()

    resp = client.delete(f"/api/media/{media_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == {"deleted": True}

    # File gone from disk, row gone from the index, caches cleaned up.
    assert not src.exists()
    assert _ids(app) == []
    assert not thumb.exists()
    assert not preview.exists()
    # And the item no longer resolves.
    assert client.get(f"/api/media/{media_id}/thumb", headers=headers).status_code == 404


def test_delete_unknown_id_404(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)

    resp = client.delete("/api/media/999999", headers=_auth(client))
    assert resp.status_code == 404


def test_delete_requires_auth(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _build_index(app)
    client = TestClient(app)
    media_id = _ids(app)[0]

    resp = client.delete(f"/api/media/{media_id}")
    assert resp.status_code == 401
    # The file is untouched by an unauthorized request.
    assert (root / "a.jpg").exists()
