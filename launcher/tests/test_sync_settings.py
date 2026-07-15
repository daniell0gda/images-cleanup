"""Index refresh and server sync settings."""
from __future__ import annotations

from pathlib import Path
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    trust,
    auth,
    _write_e2e_config,
    _full_upload,
)


def test_refresh_index_prunes_missing_keeps_present(tmp_path):
    """refresh_index drops rows whose stored file is gone and keeps the rest."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    keep = tmp_path / "keep.jpg"
    keep.write_bytes(b"abcd")
    store.record_synced("keep.jpg", "2021-01-01T00:00:00", 4, "image/jpeg",
                        str(keep), "dev-1", "alice_groupby")
    store.record_synced("gone.jpg", "2021-01-01T00:00:00", 4, "image/jpeg",
                        str(tmp_path / "gone.jpg"), "dev-1", "alice_groupby")

    result = store.refresh_index()
    assert result == {"checked": 2, "removed": 1}
    assert store.is_synced("keep.jpg", "2021-01-01T00:00:00", 4) is True
    assert store.is_synced("gone.jpg", "2021-01-01T00:00:00", 4) is False


def test_refresh_endpoint_requeues_deleted_destination(tmp_path):
    """After a synced file is deleted from the destination, POST /api/sync/refresh
    removes its index row so the next reconcile reports it as not-synced."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    _full_upload(client, token, {"person"}, name="keep.jpg", data=b"abcd")
    from launcher.sync import SyncStore
    stored = SyncStore(tmp_path / "sync.db").stored_path_for("keep.jpg", "2021-01-01T00:00:00", 4)
    ident = [{"name": "keep.jpg", "created_on": "2021-01-01T00:00:00", "size": 4}]

    assert client.post("/api/sync/reconcile", json=ident, headers=auth(token)).json()["results"][0]["already_synced"] is True

    from pathlib import Path as _P
    _P(stored).unlink()
    refreshed = client.post("/api/settings/refresh").json()
    assert refreshed["removed"] == 1

    after = client.post("/api/sync/reconcile", json=ident, headers=auth(token)).json()["results"]
    assert after[0]["already_synced"] is False


def test_settings_default_to_daily_1am(tmp_path):
    """With no server.yaml, settings default to the index refresh enabled at 1am
    and report a concrete next_run."""
    app = make_app(tmp_path)
    client = TestClient(app)

    body = client.get("/api/settings").json()
    assert body["db_refresh"]["enabled"] is True
    assert body["db_refresh"]["schedule"] == "0 1 * * *"
    assert body["db_refresh"]["next_run"] is not None
    assert body["last_refresh"] is None


def test_settings_update_persists_and_validates(tmp_path):
    """POST /api/settings writes server.yaml; a re-read reflects it, and an
    invalid cron expression is rejected with 400 without persisting."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/settings", json={"db_refresh": {"enabled": False, "schedule": "30 3 * * *"}})
    assert ok.status_code == 200
    assert ok.json()["db_refresh"] == {"enabled": False, "schedule": "30 3 * * *", "next_run": None}
    assert (tmp_path / "configs" / "server.yaml").is_file()
    assert client.get("/api/settings").json()["db_refresh"]["schedule"] == "30 3 * * *"

    bad = client.post("/api/settings", json={"db_refresh": {"enabled": True, "schedule": "not a cron"}})
    assert bad.status_code == 400
    # The rejected value did not overwrite the previously saved one.
    assert client.get("/api/settings").json()["db_refresh"]["schedule"] == "30 3 * * *"


def test_settings_last_refresh_reported_after_run(tmp_path):
    """A manual refresh is reflected in the settings payload's last_refresh."""
    app = make_app(tmp_path)
    client = TestClient(app)

    client.post("/api/settings/refresh")
    last = client.get("/api/settings").json()["last_refresh"]
    assert last["checked"] == 0 and last["removed"] == 0
    assert "at" in last
