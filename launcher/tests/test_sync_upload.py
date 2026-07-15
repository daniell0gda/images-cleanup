"""Upload sessions, chunk limits, session ownership, and concurrency."""
from __future__ import annotations

import os
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    trust,
    auth,
    _write_groupby_config,
    _open_session,
    _upload_chunk,
    _write_e2e_config,
    _build_lane,
)


def test_open_session_creates_inbox_directory(tmp_path):
    """POST /api/sync/sessions returns a session_id and creates
    {INBOX_BASE}/{device_id}/{session_id}/."""
    app = make_app(tmp_path)
    _write_groupby_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    r = client.post("/api/sync/sessions", json={"profile_id": "Alice"}, headers=auth(token))
    assert r.status_code == 200, r.text
    sid = r.json()["session_id"]
    assert (tmp_path / "inbox" / "dev-1" / sid).is_dir()


def test_resumable_upload_tracks_offset_and_assembles_full_file(tmp_path):
    """An interrupted upload resumes from the tracked offset and the assembled
    part matches the declared size."""
    app = make_app(tmp_path)
    _write_groupby_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")
    sid = _open_session(client, token)

    payload = b"0123456789ABCDEF"  # 16 bytes
    meta = {"name": "p.jpg", "created_on": "2024-02-02T00:00:00",
            "size": len(payload), "mime_type": "image/jpeg"}

    r1 = _upload_chunk(client, token, sid, "f1", meta, 0, payload[:6])
    assert r1.json()["offset"] == 6

    # Resume: ask the server where we are, then send the rest.
    resume = client.get(f"/api/sync/sessions/{sid}/files/f1", headers=auth(token)).json()
    assert resume["offset"] == 6

    r2 = _upload_chunk(client, token, sid, "f1", meta, resume["offset"], payload[6:])
    assert r2.json()["offset"] == len(payload)

    part = tmp_path / "inbox" / "dev-1" / sid / "f1.part"
    assert part.read_bytes() == payload
    assert part.stat().st_size == meta["size"]


def test_complete_marker_survives_restart_and_distinguishes_sessions(tmp_path):
    """The completion marker persists across a fresh SessionManager (restart)
    and an in-progress session is not reported complete."""
    make_app(tmp_path)  # sets INBOX_BASE env
    from launcher.sync import SessionManager
    mgr = SessionManager(tmp_path / "inbox")

    done_sid = mgr.create_session("dev-1", "alice_groupby")
    open_sid = mgr.create_session("dev-1", "alice_groupby")
    mgr.complete(done_sid)

    fresh = SessionManager(tmp_path / "inbox")  # simulate restart
    assert fresh.is_complete(done_sid) is True
    assert fresh.is_complete(open_sid) is False


def test_other_device_cannot_act_on_a_session_it_does_not_own(tmp_path):
    """A session belongs to the device that opened it: a second trusted device
    cannot upload a chunk into, complete, read outcomes of, or read a file
    offset of another device's session, and no bytes are written."""
    app = make_app(tmp_path)
    _write_groupby_config(tmp_path)
    client = TestClient(app)
    token_a = trust(client, "dev-A", "A")
    token_b = trust(client, "dev-B", "B")

    sid = _open_session(client, token_a)
    meta = {"name": "p.jpg", "created_on": "2024-02-02T00:00:00",
            "size": 16, "mime_type": "image/jpeg"}

    # Device B trying to upload into A's session is rejected and writes nothing.
    r_chunk = _upload_chunk(client, token_b, sid, "f1", meta, 0, b"012345")
    assert r_chunk.status_code in (403, 404), r_chunk.text
    assert not (tmp_path / "inbox" / "dev-A" / sid / "f1.part").exists()

    # Device B cannot read the file offset of A's session.
    r_off = client.get(f"/api/sync/sessions/{sid}/files/f1", headers=auth(token_b))
    assert r_off.status_code in (403, 404), r_off.text

    # Device B cannot complete A's session.
    r_done = client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token_b))
    assert r_done.status_code in (403, 404), r_done.text
    assert not (tmp_path / "inbox" / "dev-A" / sid / ".complete").exists()

    # Device B cannot read A's outcomes.
    r_out = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token_b))
    assert r_out.status_code in (403, 404), r_out.text

    # The owner can still operate on its own session.
    assert _upload_chunk(client, token_a, sid, "f1", meta, 0, b"012345").status_code == 200


def test_complete_is_idempotent_after_session_cleanup(tmp_path):
    """Calling complete twice succeeds both times: the second call returns
    status complete (not 404) even after the first placement cleaned up the
    session folder, and the owner can still read the recorded outcomes."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid = _open_session(client, token)
    meta = {"name": "ok.jpg", "created_on": "2021-01-01T00:00:00",
            "size": 4, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"abcd")

    first = client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    assert first.status_code == 200, first.text
    assert first.json()["status"] == "complete"
    # The first placement cleaned up the on-disk session folder.
    assert not (tmp_path / "inbox" / "dev-1" / sid).exists()

    # A retried complete after cleanup still succeeds (not 404).
    second = client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    assert second.status_code == 200, second.text
    assert second.json()["status"] == "complete"

    # Outcomes remain readable by the owner.
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert outcomes[0]["status"] == "synced"


def test_back_to_back_process_session_places_each_file_once(tmp_path):
    """Calling process_session twice for the same completed session places each
    uploaded file exactly once: no duplicate destination file (no _1 rename
    artifact) and exactly one synced_files row per identity."""
    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(sid, "f1", FileMeta("rec.jpg", "2021-01-01T00:00:00", 4, "image/jpeg"), 0, b"abcd")
    sessions.complete(sid)

    first = lane.process_session(sid)
    second = lane.process_session(sid)

    assert [o["status"] for o in first] == ["synced"]
    assert second == [] or [o["status"] for o in second] == ["synced"]
    # The second run must be a no-op: it must not place the file a second time.
    placed = list((dest / "people").rglob("rec*.jpg"))
    assert len(placed) == 1, [str(p) for p in placed]

    recorded = store.outcomes_for(sid)
    assert [o["status"] for o in recorded] == ["synced"], recorded

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    rows = con.execute(
        "SELECT COUNT(*) FROM synced_files WHERE name='rec.jpg' "
        "AND created_on='2021-01-01T00:00:00' AND size=4"
    ).fetchone()[0]
    con.close()
    assert rows == 1


def test_concurrent_process_session_places_each_file_once(tmp_path):
    """Two threads entering process_session for the same completed session at the
    same time still place each file exactly once: a per-session processing guard
    serializes them and the idempotency short-circuit makes the loser a no-op."""
    import threading

    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)

    # Gate the first thread inside placement so the second enters concurrently.
    entered = threading.Event()
    release = threading.Event()
    detect_calls = {"n": 0}
    calls_lock = threading.Lock()

    def gated_detect(path):
        with calls_lock:
            detect_calls["n"] += 1
        if not entered.is_set():
            entered.set()
            release.wait(timeout=5)
        return {"person"}

    store, sessions, lane = _build_lane(tmp_path, detect_tags=gated_detect)

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(sid, "f1", FileMeta("rec.jpg", "2021-01-01T00:00:00", 4, "image/jpeg"), 0, b"abcd")
    sessions.complete(sid)

    results: list[list[dict]] = []
    lock = threading.Lock()

    def run():
        out = lane.process_session(sid)
        with lock:
            results.append(out)

    t1 = threading.Thread(target=run)
    t2 = threading.Thread(target=run)
    t1.start()
    assert entered.wait(timeout=5)
    # While thread 1 is gated mid-placement, thread 2 must not concurrently place
    # the same file: a per-session guard keeps it from entering the file loop.
    t2.start()
    t2.join(timeout=2)
    with calls_lock:
        # Thread 2 must NOT have reached classification (it is blocked on the
        # per-session guard, or short-circuited on has_outcomes before placement).
        assert detect_calls["n"] == 1, detect_calls["n"]

    release.set()
    t1.join(timeout=10)
    t2.join(timeout=10)

    placed = list((dest / "people").rglob("rec*.jpg"))
    assert len(placed) == 1, [str(p) for p in placed]

    recorded = store.outcomes_for(sid)
    assert [o["status"] for o in recorded] == ["synced"], recorded

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    rows = con.execute(
        "SELECT COUNT(*) FROM synced_files WHERE name='rec.jpg' "
        "AND created_on='2021-01-01T00:00:00' AND size=4"
    ).fetchone()[0]
    con.close()
    assert rows == 1


def test_oversize_chunk_is_rejected_with_413_and_appends_nothing(tmp_path):
    """A chunk whose body exceeds the configured max chunk size is rejected with
    HTTP 413 and no bytes are appended to the .part file (offset unchanged)."""
    os.environ["MAX_CHUNK_BYTES"] = "8"
    try:
        app = make_app(tmp_path)
        _write_groupby_config(tmp_path)
        client = TestClient(app)
        token = trust(client, "dev-1")
        sid = _open_session(client, token)

        meta = {"name": "big.jpg", "created_on": "2024-01-01T00:00:00",
                "size": 100, "mime_type": "image/jpeg"}
        oversize = b"0123456789ABCDEF"  # 16 bytes > 8 limit

        r = _upload_chunk(client, token, sid, "f1", meta, 0, oversize)
        assert r.status_code == 413, r.text

        # Nothing was appended: the .part file must not exist (or be empty).
        part = tmp_path / "inbox" / "dev-1" / sid / "f1.part"
        assert not part.exists() or part.stat().st_size == 0

        # And the tracked offset is still 0.
        off = client.get(f"/api/sync/sessions/{sid}/files/f1", headers=auth(token)).json()
        assert off["offset"] == 0
    finally:
        os.environ.pop("MAX_CHUNK_BYTES", None)


def test_chunk_within_limit_is_accepted(tmp_path):
    """A chunk at or below the configured max chunk size is accepted normally."""
    os.environ["MAX_CHUNK_BYTES"] = "8"
    try:
        app = make_app(tmp_path)
        _write_groupby_config(tmp_path)
        client = TestClient(app)
        token = trust(client, "dev-1")
        sid = _open_session(client, token)

        meta = {"name": "ok.jpg", "created_on": "2024-01-01T00:00:00",
                "size": 16, "mime_type": "image/jpeg"}
        r = _upload_chunk(client, token, sid, "f1", meta, 0, b"01234567")  # exactly 8
        assert r.status_code == 200, r.text
        assert r.json()["offset"] == 8
    finally:
        os.environ.pop("MAX_CHUNK_BYTES", None)


def test_concurrent_write_chunk_to_two_sessions_is_correct_and_not_globally_serialized(tmp_path):
    """Two different sessions can be written concurrently with correct resulting
    offsets, and write_chunk uses per-session locks (distinct sessions get
    distinct locks) rather than one global lock for every session."""
    import threading

    make_app(tmp_path)
    _write_groupby_config(tmp_path)
    from launcher.sync import SessionManager, FileMeta
    mgr = SessionManager(tmp_path / "inbox")

    s1 = mgr.create_session("dev-1", "alice_groupby")
    s2 = mgr.create_session("dev-2", "alice_groupby")

    # Per-session locking: distinct sessions resolve to distinct lock objects,
    # the same session resolves to the same lock (so its writes stay serialized).
    assert mgr._session_write_lock(s1) is mgr._session_write_lock(s1)
    assert mgr._session_write_lock(s1) is not mgr._session_write_lock(s2)

    payload = b"0123456789ABCDEF" * 64  # 1024 bytes
    m1 = FileMeta("a.jpg", "2024-01-01T00:00:00", len(payload), "image/jpeg")
    m2 = FileMeta("b.jpg", "2024-01-01T00:00:00", len(payload), "image/jpeg")

    results: dict[str, int] = {}

    def write(sid, fid, meta):
        off = 0
        for i in range(0, len(payload), 64):
            off = mgr.write_chunk(sid, fid, meta, off, payload[i:i + 64])
        results[sid] = off

    t1 = threading.Thread(target=write, args=(s1, "f1", m1))
    t2 = threading.Thread(target=write, args=(s2, "f1", m2))
    t1.start(); t2.start()
    t1.join(timeout=10); t2.join(timeout=10)

    assert results[s1] == len(payload)
    assert results[s2] == len(payload)
    assert (tmp_path / "inbox" / "dev-1" / s1 / "f1.part").read_bytes() == payload
    assert (tmp_path / "inbox" / "dev-2" / s2 / "f1.part").read_bytes() == payload
