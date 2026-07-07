"""Tests for bidirectional seek pagination on GET /api/media.

After a `from_date` seek the client must be able to page *upward* (toward newer
photos) as well as downward. The server exposes this via a `prev_cursor` on every
newest-first page and a `before=<cursor>` parameter that returns photos strictly
newer than the cursor, ascending (closest-to-anchor first).
"""
from __future__ import annotations

import os
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image


def _make_image(path: Path, size=(20, 20), color=(10, 120, 200)) -> None:
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
    code = client.post(
        "/api/sync/devices", json={"device_id": device_id, "name": "Pixel"}
    ).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    return client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()["token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _index(app) -> None:
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    app.state.media_indexer.build()


def _set_dates(app, dates_by_id: dict[int, str]) -> None:
    indexer = app.state.media_indexer
    ids = [r["id"] for r in indexer.list_all()]
    conn = indexer._conn()
    for idx, iso in dates_by_id.items():
        conn.execute("UPDATE media SET date_taken=? WHERE id=?", (iso, ids[idx]))
    conn.commit()


def _seed(tmp_path, dates: list[str]):
    """Build an app with one image per date; returns (app, client, token, id_by_date)."""
    root = tmp_path / "lib"
    for i in range(len(dates)):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    _set_dates(app, {i: d for i, d in enumerate(dates)})
    client = TestClient(app)
    token = _trust(client)
    indexed = {r["date_taken"]: r["id"] for r in app.state.media_indexer.list_all()}
    return app, client, token, indexed


def test_plain_first_page_has_null_prev_cursor(tmp_path):
    # The absolute-newest-first page has nothing newer above it.
    _app, client, token, _ = _seed(tmp_path, [
        "2025-06-01T10:00:00+00:00",
        "2024-01-01T10:00:00+00:00",
        "2023-01-01T10:00:00+00:00",
    ])
    body = client.get("/api/media", headers=_auth(token)).json()
    assert body["prev_cursor"] is None


def test_seek_page_exposes_prev_cursor_for_newer_photos(tmp_path):
    # Seeking below the newest leaves photos above -> prev_cursor is set.
    _app, client, token, _ = _seed(tmp_path, [
        "2025-06-01T10:00:00+00:00",
        "2023-12-31T23:30:00+00:00",
        "2022-01-01T10:00:00+00:00",
    ])
    body = client.get("/api/media?from_date=2023-12-31", headers=_auth(token)).json()
    assert body["items"][0]["date_taken"] == "2023-12-31T23:30:00+00:00"
    assert body["prev_cursor"] is not None


def test_before_returns_newer_photos_ascending_closest_first(tmp_path):
    # `before` walks upward from the seek anchor toward the newest, ascending.
    _app, client, token, _ = _seed(tmp_path, [
        "2025-06-01T10:00:00+00:00",
        "2024-09-01T10:00:00+00:00",
        "2024-03-01T10:00:00+00:00",
        "2023-01-01T10:00:00+00:00",
    ])
    seek = client.get("/api/media?from_date=2023-01-01", headers=_auth(token)).json()
    assert seek["items"][0]["date_taken"] == "2023-01-01T10:00:00+00:00"
    prev = seek["prev_cursor"]

    up = client.get(f"/api/media?before={prev}", headers=_auth(token)).json()
    dates = [i["date_taken"] for i in up["items"]]
    # Strictly newer than the anchor, closest-first (ascending).
    assert dates == [
        "2024-03-01T10:00:00+00:00",
        "2024-09-01T10:00:00+00:00",
        "2025-06-01T10:00:00+00:00",
    ]


def test_before_paginates_then_reaches_latest_with_null_prev_cursor(tmp_path):
    _app, client, token, _ = _seed(tmp_path, [
        "2025-01-05T10:00:00+00:00",
        "2025-01-04T10:00:00+00:00",
        "2025-01-03T10:00:00+00:00",
        "2025-01-02T10:00:00+00:00",
        "2025-01-01T10:00:00+00:00",
    ])
    seek = client.get("/api/media?from_date=2025-01-01", headers=_auth(token)).json()
    cursor = seek["prev_cursor"]

    seen: list[str] = []
    # Page upward two at a time until the latest is reached.
    while cursor is not None:
        page = client.get(f"/api/media?before={cursor}&limit=2", headers=_auth(token)).json()
        seen += [i["date_taken"] for i in page["items"]]
        cursor = page["prev_cursor"]

    assert seen == [
        "2025-01-02T10:00:00+00:00",
        "2025-01-03T10:00:00+00:00",
        "2025-01-04T10:00:00+00:00",
        "2025-01-05T10:00:00+00:00",
    ]


def test_before_at_latest_returns_empty(tmp_path):
    # A `before` cursor at the newest photo yields no newer items.
    _app, client, token, indexed = _seed(tmp_path, [
        "2025-06-01T10:00:00+00:00",
        "2024-01-01T10:00:00+00:00",
    ])
    import base64
    newest_id = indexed["2025-06-01T10:00:00+00:00"]
    cursor = base64.urlsafe_b64encode(
        f"2025-06-01T10:00:00+00:00|{newest_id}".encode("utf-8")
    ).decode("ascii")
    body = client.get(f"/api/media?before={cursor}", headers=_auth(token)).json()
    assert body["items"] == []
    assert body["prev_cursor"] is None
