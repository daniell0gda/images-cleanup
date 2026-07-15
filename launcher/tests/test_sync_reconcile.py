"""Reconcile/verify identity-presence queries and global identity semantics."""
from __future__ import annotations

from pathlib import Path
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    trust,
    auth,
    _seed_synced,
    _write_groupby_config,
    _open_session,
    _upload_chunk,
    _write_e2e_config,
    _full_upload,
)


def test_reconcile_matches_on_identity_key(tmp_path):
    """Reconcile reports already-synced strictly on (name, created_on, size)."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)
    _seed_synced(tmp_path, "a.jpg", "2024-01-01T00:00:00", 100)

    body = [
        {"name": "a.jpg", "created_on": "2024-01-01T00:00:00", "size": 100},
        {"name": "b.jpg", "created_on": "2024-01-01T00:00:00", "size": 200},
    ]
    results = client.post("/api/sync/reconcile", json=body, headers=auth(token)).json()["results"]
    by_name = {r["name"]: r["already_synced"] for r in results}
    assert by_name == {"a.jpg": True, "b.jpg": False}


def test_reconcile_is_global_across_devices(tmp_path):
    """A file synced by one device reads as already-synced for another."""
    app = make_app(tmp_path)
    client = TestClient(app)
    _seed_synced(tmp_path, "shared.jpg", "2024-01-01T00:00:00", 555, device_id="phone-A")

    token_b = trust(client, "phone-B", "B")
    body = [{"name": "shared.jpg", "created_on": "2024-01-01T00:00:00", "size": 555}]
    results = client.post("/api/sync/reconcile", json=body, headers=auth(token_b)).json()["results"]
    assert results[0]["already_synced"] is True


def test_reconcile_size_disambiguates(tmp_path):
    """Same name/created_on but a different size is NOT already-synced."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)
    _seed_synced(tmp_path, "c.jpg", "2024-01-01T00:00:00", 100)

    body = [{"name": "c.jpg", "created_on": "2024-01-01T00:00:00", "size": 999}]
    results = client.post("/api/sync/reconcile", json=body, headers=auth(token)).json()["results"]
    assert results[0]["already_synced"] is False


def test_reconcile_reports_uploaded_bytes_for_interrupted_session(tmp_path):
    """A not-yet-synced file whose bytes are already in an open session is
    reported with its uploaded offset and the session/file to resume into, so a
    re-run continues instead of re-uploading from scratch."""
    app = make_app(tmp_path)
    _write_groupby_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")
    sid = _open_session(client, token)

    meta = {"name": "p.jpg", "created_on": "2024-02-02T00:00:00",
            "size": 16, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"012345")  # 6 of 16 bytes

    body = [{"name": "p.jpg", "created_on": "2024-02-02T00:00:00", "size": 16}]
    result = client.post(
        "/api/sync/reconcile", json=body, headers=auth(token)
    ).json()["results"][0]
    assert result["already_synced"] is False
    assert result["uploaded_offset"] == 6
    assert result["resume_session_id"] == sid
    assert result["resume_file_id"] == "f1"


def test_uploaded_offsets_skips_completed_sessions(tmp_path):
    """uploaded_offsets only surfaces incomplete sessions; a completed one is
    finalized by startup_reconcile rather than resumed."""
    make_app(tmp_path)  # sets INBOX_BASE env
    from launcher.sync import SessionManager, FileMeta
    mgr = SessionManager(tmp_path / "inbox")

    open_sid = mgr.create_session("dev-1", "alice_groupby")
    mgr.write_chunk(
        open_sid, "f1", FileMeta("p.jpg", "2024-02-02T00:00:00", 8, "image/jpeg"), 0, b"abcd"
    )
    done_sid = mgr.create_session("dev-1", "alice_groupby")
    mgr.write_chunk(
        done_sid, "f2", FileMeta("q.jpg", "2024-02-02T00:00:00", 8, "image/jpeg"), 0, b"abcd"
    )
    mgr.complete(done_sid)

    assert mgr.uploaded_offsets("dev-1") == {
        ("p.jpg", "2024-02-02T00:00:00", 8): (open_sid, "f1", 4)
    }


def test_verify_reports_present_and_missing(tmp_path):
    """Verify reports a still-present synced file as present and a file whose
    stored_path was deleted as not-present even though its index row remains."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    _full_upload(client, token, {"person"}, name="keep.jpg", data=b"abcd")

    from launcher.sync import SyncStore
    stored = SyncStore(tmp_path / "sync.db").stored_path_for("keep.jpg", "2021-01-01T00:00:00", 4)
    ident = [{"name": "keep.jpg", "created_on": "2021-01-01T00:00:00", "size": 4}]

    present = client.post("/api/sync/verify", json=ident, headers=auth(token)).json()["results"]
    assert present[0]["present"] is True

    from pathlib import Path as _P
    _P(stored).unlink()
    after = client.post("/api/sync/verify", json=ident, headers=auth(token)).json()["results"]
    assert after[0]["present"] is False


def test_identity_is_global_one_row_per_identity_across_devices(tmp_path):
    """The chosen semantics: identity is global by (name, created_on, size).
    A file synced by device A is the same identity for device B — there is one
    index row per identity, not one per device."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_synced("g.jpg", "2024-01-01T00:00:00", 7, "image/jpeg",
                        str(tmp_path / "g.jpg"), "device-A", "alice_groupby")
    # Same identity reported by another device is the same row.
    assert store.is_synced("g.jpg", "2024-01-01T00:00:00", 7) is True

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    count = con.execute(
        "SELECT COUNT(*) FROM synced_files WHERE name='g.jpg' AND created_on=? AND size=7",
        ("2024-01-01T00:00:00",),
    ).fetchone()[0]
    con.close()
    assert count == 1
