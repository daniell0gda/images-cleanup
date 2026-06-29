"""Tests for the /api/media keyset timeline page, auth gate, and path-safety.

These cover Cluster 5: paginated lightweight timeline, the device-bearer-token
requirement on every /api/media/* content route, and the 403 when an indexed
path resolves outside the configured roots.
"""
from __future__ import annotations

import os
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image


def _make_image(path: Path, size=(40, 40), color=(10, 200, 50)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)


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


def _trust(client: TestClient, device_id="dev-1") -> str:
    """Register + approve a device and return its bearer token."""
    client.post("/api/sync/devices", json={"device_id": device_id, "name": "Pixel"})
    client.post(f"/api/sync/devices/{device_id}/approve")
    status = client.get(f"/api/sync/devices/{device_id}/status").json()
    return status["token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _index(app) -> None:
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    app.state.media_indexer.build()


# ---------------------------------------------------------------------------
# Keyset pagination
# ---------------------------------------------------------------------------

def test_timeline_first_page_newest_first_with_cursor(tmp_path):
    root = tmp_path / "lib"
    for i in range(5):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    token = _trust(client)

    resp = client.get("/api/media?limit=2", headers=_auth(token))
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 2
    assert body["next_cursor"] is not None
    item = body["items"][0]
    # Lightweight item DTO: no file bytes.
    assert set(item) >= {"id", "kind", "date_taken", "width", "height"}
    assert "path" not in item
    # Newest-first ordering (date_taken DESC, id DESC).
    full = app.state.media_indexer.timeline(limit=100)
    assert [i["id"] for i in body["items"]] == [r["id"] for r in full[:2]]


def test_timeline_pages_have_no_overlap_no_gaps(tmp_path):
    root = tmp_path / "lib"
    for i in range(5):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    token = _trust(client)

    seen = []
    cursor = None
    while True:
        url = "/api/media?limit=2"
        if cursor is not None:
            url += f"&cursor={cursor}"
        body = client.get(url, headers=_auth(token)).json()
        seen += [i["id"] for i in body["items"]]
        cursor = body["next_cursor"]
        if cursor is None:
            break

    expected = [r["id"] for r in app.state.media_indexer.timeline(limit=100)]
    assert seen == expected           # no gaps, correct order
    assert len(set(seen)) == len(seen)  # no overlap


def test_timeline_final_page_null_cursor(tmp_path):
    root = tmp_path / "lib"
    for i in range(3):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    token = _trust(client)

    body = client.get("/api/media?limit=100", headers=_auth(token)).json()
    assert len(body["items"]) == 3
    assert body["next_cursor"] is None


# ---------------------------------------------------------------------------
# Auth gate on every /api/media/* content route
# ---------------------------------------------------------------------------

def test_content_routes_401_without_token(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    mid = app.state.media_indexer.list_all()[0]["id"]

    for url in ["/api/media",
                f"/api/media/{mid}/thumb",
                f"/api/media/{mid}/preview",
                f"/api/media/{mid}/stream"]:
        assert client.get(url).status_code == 401, url


def test_content_routes_succeed_with_token(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg", size=(800, 400))
    app = _build_app(tmp_path, [root])
    app.state.media_indexer.build()
    client = TestClient(app)
    token = _trust(client)
    mid = app.state.media_indexer.list_all()[0]["id"]

    assert client.get("/api/media", headers=_auth(token)).status_code == 200
    assert client.get(f"/api/media/{mid}/thumb", headers=_auth(token)).status_code == 200
    assert client.get(f"/api/media/{mid}/preview", headers=_auth(token)).status_code == 200


# ---------------------------------------------------------------------------
# Path-safety: 403 when the resolved path is outside all configured roots
# ---------------------------------------------------------------------------

def test_serve_path_outside_roots_rejected(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    token = _trust(client)
    mid = app.state.media_indexer.list_all()[0]["id"]

    # Point the indexed row at a file outside every configured root.
    outside = tmp_path / "outside" / "evil.jpg"
    _make_image(outside)
    conn = app.state.media_indexer._conn()
    conn.execute("UPDATE media SET path=? WHERE id=?", (str(outside), mid))
    conn.commit()

    assert client.get(f"/api/media/{mid}/preview", headers=_auth(token)).status_code == 403
