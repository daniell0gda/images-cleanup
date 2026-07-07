"""Tests for the GET /api/media/dates available-dates tree (Cluster 1).

Covers the year -> month -> day JSON tree derived from the live SQLite media
index, omission of empty branches, and the device-bearer-token auth gate.
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
    """Overwrite the date_taken of indexed rows in id order with ISO strings."""
    indexer = app.state.media_indexer
    ids = [r["id"] for r in indexer.list_all()]
    conn = indexer._conn()
    for idx, iso in dates_by_id.items():
        conn.execute("UPDATE media SET date_taken=? WHERE id=?", (iso, ids[idx]))
    conn.commit()


def test_dates_returns_structured_tree(tmp_path):
    root = tmp_path / "lib"
    for i in range(4):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    _set_dates(app, {
        0: "2024-03-01T10:00:00+00:00",
        1: "2024-03-05T10:00:00+00:00",
        2: "2024-12-25T10:00:00+00:00",
        3: "2023-07-09T10:00:00+00:00",
    })
    client = TestClient(app)
    token = _trust(client)

    body = client.get("/api/media/dates", headers=_auth(token)).json()
    assert body == {
        "2023": {"07": [9]},
        "2024": {"03": [1, 5], "12": [25]},
    }


def test_dates_omits_empty_and_sorts_days(tmp_path):
    root = tmp_path / "lib"
    for i in range(3):
        _make_image(root / f"a{i}.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    _set_dates(app, {
        0: "2024-03-12T10:00:00+00:00",
        1: "2024-03-01T10:00:00+00:00",
        2: "2024-03-05T10:00:00+00:00",
    })
    client = TestClient(app)
    token = _trust(client)

    body = client.get("/api/media/dates", headers=_auth(token)).json()
    # Only the one month with photos, days sorted ascending, no duplicate.
    assert list(body.keys()) == ["2024"]
    assert list(body["2024"].keys()) == ["03"]
    assert body["2024"]["03"] == [1, 5, 12]


def test_dates_requires_auth(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)

    assert client.get("/api/media/dates").status_code == 401


def test_dates_derived_from_live_index(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    _set_dates(app, {0: "2022-01-02T10:00:00+00:00"})
    client = TestClient(app)
    token = _trust(client)

    body = client.get("/api/media/dates", headers=_auth(token)).json()
    assert body == {"2022": {"01": [2]}}
