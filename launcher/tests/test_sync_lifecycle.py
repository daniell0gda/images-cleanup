"""End-to-end outcomes/index persistence plus startup reconcile & janitor."""
from __future__ import annotations

from pathlib import Path
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    trust,
    auth,
    _open_session,
    _upload_chunk,
    _write_e2e_config,
    _full_upload,
    _jpeg_bytes,
    _read_profile_tag,
    _build_lane,
    _age_session,
    _prepare_env,
)


def test_outcomes_report_synced_and_failed(tmp_path):
    """Outcomes report 'synced' for a placed file and 'failed{reason}' for a
    file whose assembled bytes do not match the declared size."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    _, ok_outcomes = _full_upload(client, token, {"person"}, name="good.jpg", data=b"abcd")
    assert ok_outcomes[0]["status"] == "synced"

    # Declared size larger than bytes uploaded -> size_mismatch failure.
    sid = _open_session(client, token)
    meta = {"name": "bad.jpg", "created_on": "2021-01-01T00:00:00", "size": 99, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"short")
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    bad = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert bad[0]["status"] == "failed"
    assert bad[0]["reason"] == "size_mismatch"


def test_force_place_flag_defaults_false_and_persists_in_session_meta(tmp_path):
    """POST /api/sync/sessions accepts an optional force_place flag (default
    false) and persists it into the on-disk session.json meta."""
    import json
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    # Default: omitted -> false.
    sid_default = client.post(
        "/api/sync/sessions", json={"profile_id": "Alice"}, headers=auth(token)
    ).json()["session_id"]
    meta_default = json.loads(
        (tmp_path / "inbox" / "dev-1" / sid_default / "session.json").read_text(encoding="utf-8")
    )
    assert meta_default.get("force_place", False) is False

    # Explicit force_place=true is persisted.
    sid_force = client.post(
        "/api/sync/sessions",
        json={"profile_id": "Alice", "force_place": True},
        headers=auth(token),
    ).json()["session_id"]
    meta_force = json.loads(
        (tmp_path / "inbox" / "dev-1" / sid_force / "session.json").read_text(encoding="utf-8")
    )
    assert meta_force["force_place"] is True


def test_force_place_skips_classification_and_places_in_primary_group(tmp_path):
    """In a force_place session, place_file never classifies an image and routes
    it to the primary tag_group destination, recorded as 'synced'."""
    called = []
    app = make_app(tmp_path, detect_tags=lambda p: called.append(p) or {"cat"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid = client.post(
        "/api/sync/sessions",
        json={"profile_id": "Alice", "force_place": True},
        headers=auth(token),
    ).json()["session_id"]
    meta = {"name": "forced.jpg", "created_on": "2021-01-01T00:00:00", "size": 4, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"abcd")
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]

    assert called == []  # classification skipped entirely
    assert outcomes[0]["status"] == "synced"
    # Placed under the primary group's destination (people).
    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    con.row_factory = sqlite3.Row
    row = con.execute("SELECT * FROM synced_files WHERE name='forced.jpg'").fetchone()
    con.close()
    assert row is not None
    from pathlib import Path as _P
    stored = _P(row["stored_path"])
    assert stored.exists()
    assert (tmp_path / "dest" / "people") in stored.parents


def test_synced_files_row_records_full_identity_and_stored_path(tmp_path):
    """After a successful move, a synced_files row records original identity,
    the post-rename stored_path, device_id and profile_id."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    _full_upload(client, token, {"person"}, name="rec.jpg", data=b"abcd")

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    con.row_factory = sqlite3.Row
    row = con.execute("SELECT * FROM synced_files WHERE name='rec.jpg'").fetchone()
    con.close()
    assert row is not None
    assert (row["name"], row["created_on"], row["size"], row["mime_type"]) == (
        "rec.jpg", "2021-01-01T00:00:00", 4, "image/jpeg")
    assert row["device_id"] == "dev-1"
    assert row["profile_id"] == "Alice"
    assert row["synced_at"]
    from pathlib import Path as _P
    assert _P(row["stored_path"]).exists()


def test_complete_streams_keepalive_while_placing_and_still_returns_complete(tmp_path, monkeypatch):
    """A slow placement streams whitespace keep-alive bytes (so Cloudflare never
    fires a 524 while the batch is being classified/placed) and still ends with a
    parseable {"status": "complete"} once placement finishes."""
    import time
    import launcher.server as srv
    # Drop the keep-alive cadence to sub-second so the test observes it without
    # waiting the production 15s.
    monkeypatch.setattr(srv, "_COMPLETE_KEEPALIVE_SECONDS", 0.01)

    def slow_detect(_path):
        time.sleep(0.1)  # hold placement well past the keep-alive interval
        return {"person"}

    app = make_app(tmp_path, detect_tags=slow_detect)
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid = _open_session(client, token)
    meta = {"name": "slow.jpg", "created_on": "2021-01-01T00:00:00", "size": 4, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"abcd")

    resp = client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    assert resp.status_code == 200, resp.text
    # Keep-alive whitespace was streamed ahead of the JSON terminator.
    assert resp.content.startswith(b" ")
    assert resp.json()["status"] == "complete"
    # The file was still placed despite the streamed response.
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert outcomes[0]["status"] == "synced"


def test_embed_profile_tag_writes_keyword_into_jpeg_losslessly(tmp_path):
    """The syncing profile is written into a JPEG's EXIF keywords without
    touching its pixels."""
    from launcher.sync import embed_profile_tag
    from PIL import Image
    p = tmp_path / "photo.jpg"
    p.write_bytes(_jpeg_bytes((200, 100, 50)))
    before = Image.open(p).tobytes()

    embed_profile_tag(p, "alice")

    assert _read_profile_tag(p) == "profile:alice"
    assert Image.open(p).tobytes() == before


def test_embed_profile_tag_never_raises_on_non_jpeg_or_garbage(tmp_path):
    """Tagging is best-effort: a non-JPEG, a corrupt JPEG, and a missing file
    are all left untouched and never raise (a backup must never fail on this)."""
    from launcher.sync import embed_profile_tag
    from PIL import Image
    png = tmp_path / "pic.png"
    Image.new("RGB", (8, 8)).save(png)
    png_bytes = png.read_bytes()
    junk = tmp_path / "fake.jpg"
    junk.write_bytes(b"not a real jpeg")

    embed_profile_tag(png, "alice")                 # unsupported format: skipped
    embed_profile_tag(junk, "alice")                # garbage payload: swallowed
    embed_profile_tag(tmp_path / "missing.jpg", "alice")  # absent file: swallowed

    assert png.read_bytes() == png_bytes            # non-JPEG untouched
    assert junk.read_bytes() == b"not a real jpeg"


def test_synced_jpeg_is_tagged_with_the_syncing_profile(tmp_path):
    """End to end: a photo backed up under a profile carries that profile's
    display name in its embedded tags."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    data = _jpeg_bytes()
    _full_upload(client, token, {"person"}, name="rec.jpg", data=data)

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    stored = con.execute(
        "SELECT stored_path FROM synced_files WHERE name='rec.jpg'"
    ).fetchone()[0]
    con.close()
    assert _read_profile_tag(Path(stored)) == "profile:Alice"


def test_profile_for_path_maps_stored_path_to_display_name(tmp_path):
    """profile_for_path resolves a stored file to its profile's display name,
    and returns None for a path no synced file claims."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    stored = str(tmp_path / "dest" / "a.jpg")
    store.record_synced("a.jpg", "2024-01-01T00:00:00", 10, "image/jpeg",
                        stored, "dev-1", "alice_groupby")

    assert store.profile_for_path(stored) == "alice"
    assert store.profile_for_path(str(tmp_path / "dest" / "unknown.jpg")) is None


def test_sqlite_data_survives_restart(tmp_path):
    """Reopening the SQLite DB at SYNC_DB returns prior devices and synced rows."""
    from launcher.sync import SyncStore
    db = tmp_path / "sync.db"
    store = SyncStore(db)
    store.register_device("dev-1", "Pixel")
    store.approve_device("dev-1")
    store.record_synced("a.jpg", "2024-01-01T00:00:00", 10, "image/jpeg",
                        str(tmp_path / "a.jpg"), "dev-1", "alice_groupby")
    del store

    reopened = SyncStore(db)
    assert reopened.get_device("dev-1")["status"] == "trusted"
    assert reopened.is_synced("a.jpg", "2024-01-01T00:00:00", 10) is True


def test_manual_job_and_sync_lanes_are_independent(tmp_path, monkeypatch):
    """A running manual /api/jobs job does not 409 a sync session, and an
    active sync does not 409 POST /api/jobs."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    # The manual /api/jobs lane still consumes the file-based config unchanged.
    (tmp_path / "configs" / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    client = TestClient(app)
    token = trust(client, "dev-1")

    fake_proc = type("FakeProc", (), {
        "poll": lambda self: None, "pid": 1, "terminate": lambda self: None,
    })()
    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: fake_proc)

    # A manual job running does not 409 a sync session.
    assert client.post("/api/jobs", json={"user": "alice", "mode": "groupby"}).status_code == 200
    assert client.post("/api/sync/sessions", json={"profile_id": "Alice"},
                       headers=auth(token)).status_code == 200

    # An active sync does not 409 POST /api/jobs: stop the manual job, then a
    # sync session being open must not block a fresh manual job.
    client.delete("/api/jobs")
    assert client.post("/api/sync/sessions", json={"profile_id": "Alice"},
                       headers=auth(token)).status_code == 200
    assert client.post("/api/jobs", json={"user": "alice", "mode": "groupby"}).status_code == 200


def test_startup_reconcile_processes_complete_keeps_progress_drops_abandoned(tmp_path):
    """On startup: complete-but-unprocessed sessions are processed, fresh
    in-progress sessions are preserved, and past-TTL incomplete ones dropped."""
    from datetime import timedelta
    make_app(tmp_path)
    _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})

    from launcher.sync import FileMeta
    # Complete but unprocessed.
    done = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(done, "f1", FileMeta("c.jpg", "2021-01-01T00:00:00", 4, "image/jpeg"), 0, b"abcd")
    sessions.complete(done)

    # In-progress and fresh.
    fresh = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(fresh, "f1", FileMeta("p.jpg", "2021-01-01T00:00:00", 8, "image/jpeg"), 0, b"abcd")

    # In-progress but abandoned past TTL.
    abandoned = sessions.create_session("dev-1", "alice_groupby")
    _age_session(tmp_path / "inbox" / "dev-1" / abandoned, 48 * 3600)

    lane.startup_reconcile(ttl=timedelta(hours=24))

    assert store.has_outcomes(done) is True
    assert sessions.find_session(fresh) is not None
    assert sessions.find_session(abandoned) is None


def test_janitor_drops_abandoned_keeps_in_progress(tmp_path):
    """The janitor removes abandoned incomplete sessions older than the TTL but
    never an in-progress (fresh) upload."""
    from datetime import timedelta
    make_app(tmp_path)
    _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: set())

    from launcher.sync import FileMeta
    fresh = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(fresh, "f1", FileMeta("p.jpg", "2021-01-01T00:00:00", 8, "image/jpeg"), 0, b"abcd")

    old = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(old, "f1", FileMeta("o.jpg", "2021-01-01T00:00:00", 8, "image/jpeg"), 0, b"abcd")
    _age_session(tmp_path / "inbox" / "dev-1" / old, 48 * 3600)

    lane.janitor(ttl=timedelta(hours=24))

    assert sessions.find_session(fresh) is not None
    assert sessions.find_session(old) is None


def test_create_app_runs_startup_reconcile_on_complete_unprocessed_session(tmp_path):
    """Building the app over an inbox that already holds a complete-but-
    unprocessed session re-processes it (outcomes recorded) without anyone
    calling startup_reconcile by hand."""
    _prepare_env(tmp_path)
    _write_e2e_config(tmp_path)

    from launcher.sync import SessionManager, FileMeta, SyncStore
    sessions = SessionManager(tmp_path / "inbox")
    sid = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(
        sid, "f1", FileMeta("c.jpg", "2021-01-01T00:00:00", 4, "image/jpeg"), 0, b"abcd"
    )
    sessions.complete(sid)

    import launcher.server as srv
    srv.create_app(srv._JobState(), sync_detect_tags=lambda p: {"person"})

    assert SyncStore(tmp_path / "sync.db").has_outcomes(sid) is True


def test_create_app_runs_janitor_at_startup_and_on_each_timer_tick(tmp_path):
    """create_app runs the sync janitor once at startup and registers a recurring
    timer for it; driving that scheduled callback deterministically fires it again.

    The app also wires other recurring janitors (e.g. errors retention), so more
    than one scheduler registration is expected; this test pins only the sync
    janitor's behaviour and does not assume it is the sole registration."""
    _prepare_env(tmp_path)
    _write_e2e_config(tmp_path)

    ticks = []

    def fake_scheduler(interval, fn):
        ticks.append(fn)

    janitor_calls = {"n": 0}
    import launcher.server as srv
    import launcher.sync as sync_mod
    orig = sync_mod.SyncLane.janitor

    def counting_janitor(self, ttl):
        janitor_calls["n"] += 1
        return orig(self, ttl)

    sync_mod.SyncLane.janitor = counting_janitor
    try:
        srv.create_app(
            srv._JobState(),
            sync_detect_tags=lambda p: set(),
            sync_scheduler=fake_scheduler,
        )
        # Startup pass ran the sync janitor once and registered at least one
        # recurring callback.
        assert janitor_calls["n"] == 1
        assert len(ticks) >= 1
        # Exactly one registered callback is the sync janitor: driving all of the
        # scheduled callbacks runs the sync janitor exactly once more (no sleeping).
        before = janitor_calls["n"]
        for fn in ticks:
            fn()
        assert janitor_calls["n"] == before + 1
    finally:
        sync_mod.SyncLane.janitor = orig
