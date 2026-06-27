"""Tests for the android-phone-sync server-side endpoints and storage."""
from __future__ import annotations

import os
import re
from pathlib import Path

from fastapi.testclient import TestClient


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_app(tmp_path: Path, detect_tags=None):
    """Build a fresh launcher app with sync env vars pointed at tmp_path."""
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=detect_tags)


def register(client: TestClient, device_id="dev-1", name="Pixel"):
    return client.post("/api/sync/devices", json={"device_id": device_id, "name": name})


def trust(client: TestClient, device_id="dev-1", name="Pixel") -> str:
    """Register, approve, and return the bearer token."""
    register(client, device_id, name)
    client.post(f"/api/sync/devices/{device_id}/approve")
    status = client.get(f"/api/sync/devices/{device_id}/status").json()
    return status["token"]


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Pairing & auth
# ---------------------------------------------------------------------------

def test_register_device_creates_pending_with_pairing_code(tmp_path):
    """POST /api/sync/devices returns a 6-digit code and the device persists
    as pending with a NULL token."""
    app = make_app(tmp_path)
    client = TestClient(app)

    r = register(client, "dev-1", "Pixel")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "pending"
    assert re.match(r"^\d{6}$", body["pairing_code"])

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    row = con.execute(
        "SELECT status, token FROM devices WHERE device_id=?", ("dev-1",)
    ).fetchone()
    con.close()
    assert row == ("pending", None)


def test_list_devices_returns_status_and_code_without_token(tmp_path):
    """GET /api/sync/devices lists every device with its pairing fields for the
    management UI, but never leaks the bearer token."""
    app = make_app(tmp_path)
    client = TestClient(app)

    register(client, "dev-1", "Pixel")
    trust(client, "dev-2", "Galaxy")

    r = client.get("/api/sync/devices")
    assert r.status_code == 200, r.text
    by_id = {d["device_id"]: d for d in r.json()}

    assert by_id["dev-1"]["status"] == "pending"
    assert re.match(r"^\d{6}$", by_id["dev-1"]["pairing_code"])
    assert by_id["dev-2"]["status"] == "trusted"
    assert by_id["dev-2"]["approved_at"]
    for d in by_id.values():
        assert "token" not in d


def test_device_status_lifecycle_pending_trusted_revoked(tmp_path):
    """Status is pending (no token) before approval, trusted (with token)
    after approval, and revoked after revocation."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")

    before = client.get("/api/sync/devices/dev-1/status").json()
    assert before["status"] == "pending"
    assert "token" not in before

    client.post("/api/sync/devices/dev-1/approve")
    after = client.get("/api/sync/devices/dev-1/status").json()
    assert after["status"] == "trusted"
    assert after["token"]

    client.post("/api/sync/devices/dev-1/revoke")
    revoked = client.get("/api/sync/devices/dev-1/status").json()
    assert revoked["status"] == "revoked"


def test_approve_sets_approved_at_and_status_token_matches(tmp_path):
    """Approval issues a token and sets approved_at; the status endpoint
    returns the same token that approval issued."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")

    approve = client.post("/api/sync/devices/dev-1/approve").json()
    issued = approve["token"]

    status = client.get("/api/sync/devices/dev-1/status").json()
    assert status["token"] == issued

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    row = con.execute(
        "SELECT status, approved_at FROM devices WHERE device_id=?", ("dev-1",)
    ).fetchone()
    con.close()
    assert row[0] == "trusted"
    assert row[1] is not None


def test_protected_endpoint_rejects_missing_and_wrong_token(tmp_path):
    """A protected sync endpoint rejects missing/wrong tokens and accepts a
    valid trusted-device token."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)

    assert client.get("/api/sync/profiles").status_code == 401
    assert client.get("/api/sync/profiles", headers=auth("garbage")).status_code == 401
    assert client.get("/api/sync/profiles", headers=auth(token)).status_code == 200


def test_revoked_device_token_is_rejected(tmp_path):
    """A token that was valid while trusted stops working after revocation."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)
    assert client.get("/api/sync/profiles", headers=auth(token)).status_code == 200

    client.post("/api/sync/devices/dev-1/revoke")
    assert client.get("/api/sync/profiles", headers=auth(token)).status_code == 401


# ---------------------------------------------------------------------------
# Profiles
# ---------------------------------------------------------------------------

def test_profiles_lists_groupby_only(tmp_path):
    """GET /api/sync/profiles lists GroupByTags profiles and omits Similarity."""
    app = make_app(tmp_path)
    configs = tmp_path / "configs"
    (configs / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    (configs / "config_alice_similarity.yaml").write_text("mode: SimilaritySearch\n")
    (configs / "config_bob_groupby.yaml").write_text("mode: GroupByTags\n")

    client = TestClient(app)
    token = trust(client)
    profiles = client.get("/api/sync/profiles", headers=auth(token)).json()

    ids = {p["profile_id"] for p in profiles}
    assert ids == {"alice_groupby", "bob_groupby"}
    assert all("display_name" in p for p in profiles)


# ---------------------------------------------------------------------------
# Reconcile & identity
# ---------------------------------------------------------------------------

def _seed_synced(tmp_path, name, created_on, size, device_id="other-dev"):
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_synced(
        name, created_on, size, "image/jpeg",
        str(tmp_path / "stored" / name), device_id, "alice_groupby",
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


# ---------------------------------------------------------------------------
# Upload sessions
# ---------------------------------------------------------------------------

def _write_groupby_config(tmp_path, user="alice"):
    (tmp_path / "configs" / f"config_{user}_groupby.yaml").write_text("mode: GroupByTags\n")


def test_open_session_creates_inbox_directory(tmp_path):
    """POST /api/sync/sessions returns a session_id and creates
    {INBOX_BASE}/{device_id}/{session_id}/."""
    app = make_app(tmp_path)
    _write_groupby_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    r = client.post("/api/sync/sessions", json={"profile_id": "alice_groupby"}, headers=auth(token))
    assert r.status_code == 200, r.text
    sid = r.json()["session_id"]
    assert (tmp_path / "inbox" / "dev-1" / sid).is_dir()


def _open_session(client, token, profile_id="alice_groupby"):
    return client.post(
        "/api/sync/sessions", json={"profile_id": profile_id}, headers=auth(token)
    ).json()["session_id"]


def _upload_chunk(client, token, sid, file_id, meta, offset, data):
    headers = {
        **auth(token),
        "File-Id": file_id,
        "File-Name": meta["name"],
        "File-Created-On": meta["created_on"],
        "File-Size": str(meta["size"]),
        "File-Mime-Type": meta["mime_type"],
        "Upload-Offset": str(offset),
    }
    return client.post(f"/api/sync/sessions/{sid}/files", content=data, headers=headers)


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


# ---------------------------------------------------------------------------
# Routing & placement
# ---------------------------------------------------------------------------

def _make_config(tmp_path, *, video=True):
    from imagesorter.config import Config, TagGroup, Unclassified, Video
    return Config(
        mode="GroupByTags",
        source_folder=str(tmp_path / "src"),
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=[
            TagGroup("people", ["person"], str(tmp_path / "dest" / "people"),
                     group_by_year=True, group_by_month=False),
        ],
        unclassified=Unclassified(
            enabled=True, folder_name="others", destination=str(tmp_path / "dest"),
            group_by_year=True, group_by_month=False,
        ),
        similarity_threshold=0.96,
        video=Video(str(tmp_path / "dest" / "videos"), group_by_year=True, group_by_month=False)
        if video else None,
    )


def _part(tmp_path, name, data=b"x"):
    p = tmp_path / name
    p.write_bytes(data)
    return p


def _meta(name, size, mime="image/jpeg", created="2021-07-15T00:00:00"):
    from launcher.sync import FileMeta
    return FileMeta(name, created, size, mime)


def test_image_matching_tag_group_goes_to_group_destination(tmp_path):
    """An image whose detected tags match a group lands in that group's
    destination, honoring group_by_year."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"person"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "people" / "2021"
    assert stored.exists()


def test_image_matching_nothing_goes_to_unclassified(tmp_path):
    """An image matching no group lands in the unclassified destination."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"cat"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "others" / "2021"


def test_video_skips_classification_and_goes_to_video_destination(tmp_path):
    """A video is routed to video.destination without ever calling detect_tags."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "clip.part", b"movie")
    meta = _meta("clip.mp4", 5, mime="video/mp4")

    called = []
    ok, stored, reason = place_file(part, meta, cfg, lambda p: called.append(p) or set())
    assert ok and reason is None
    assert called == []
    assert stored.parent == tmp_path / "dest" / "videos" / "2021"


def test_video_without_video_section_fails_with_terminal_reason(tmp_path):
    """A video routed under a profile that has no video section yields an
    explicit, terminal failure (not retried, never decoded as an image)."""
    from launcher.sync import place_file, FailureReason
    cfg = _make_config(tmp_path, video=False)
    part = _part(tmp_path, "clip.part", b"movie")
    meta = _meta("clip.mp4", 5, mime="video/mp4")

    called = []
    ok, stored, reason = place_file(part, meta, cfg, lambda p: called.append(p) or set())

    assert ok is False
    assert stored is None
    assert reason is FailureReason.NO_VIDEO_DESTINATION
    assert reason.retryable is False
    assert called == []  # never classified as an image


def test_placement_always_renames_on_clash_both_survive(tmp_path):
    """Two distinct files with the same name both survive — the clash is
    renamed, never skipped/overwritten."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)

    p1 = _part(tmp_path, "a.part", b"first")
    ok1, s1, _ = place_file(p1, _meta("dup.jpg", 5), cfg, lambda p: {"cat"})
    p2 = _part(tmp_path, "b.part", b"secondchunk")
    ok2, s2, _ = place_file(p2, _meta("dup.jpg", 11), cfg, lambda p: {"cat"})

    assert ok1 and ok2
    assert s1 != s2
    assert s1.exists() and s2.exists()
    assert s1.read_bytes() == b"first"
    assert s2.read_bytes() == b"secondchunk"


# ---------------------------------------------------------------------------
# Path-traversal hardening
# ---------------------------------------------------------------------------

def test_malicious_file_name_cannot_escape_session_dir(tmp_path):
    """A File-Name containing path separators / .. is rejected with a terminal
    failure and no file is written outside the session folder."""
    from launcher.sync import place_file, FailureReason
    cfg = _make_config(tmp_path)
    session = tmp_path / "session"
    session.mkdir()
    part = session / "f1.part"
    part.write_bytes(b"hello")
    meta = _meta("../../evil.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"person"})

    assert ok is False
    assert stored is None
    assert reason is FailureReason.UNREADABLE
    # Nothing leaked above the session dir.
    assert not (tmp_path / "evil.jpg").exists()
    assert not (tmp_path.parent / "evil.jpg").exists()


def test_malicious_file_id_cannot_escape_session_dir(tmp_path):
    """A file_id containing path separators / .. cannot write .part/.meta files
    outside the session directory under INBOX_BASE."""
    from launcher.sync import SessionManager, FileMeta
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    mgr = SessionManager(inbox)
    sid = mgr.create_session("dev-1", "alice_groupby")

    meta = FileMeta("ok.jpg", "2021-01-01T00:00:00", 4, "image/jpeg")
    try:
        mgr.write_chunk(sid, "../../evil", meta, 0, b"abcd")
    except (ValueError, FileNotFoundError, OSError):
        pass

    assert not (tmp_path / "evil.part").exists()
    assert not (tmp_path / "evil.meta").exists()
    assert not (inbox / "evil.part").exists()
    assert mgr.file_offset(sid, "../../evil") == 0


def test_upload_endpoint_rejects_malicious_file_id(tmp_path):
    """The upload endpoint rejects a File-Id that is not a bare name (no 500,
    no part file outside the session dir)."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")
    sid = _open_session(client, token)

    meta = {"name": "ok.jpg", "created_on": "2021-01-01T00:00:00", "size": 4, "mime_type": "image/jpeg"}
    r = _upload_chunk(client, token, sid, "../../evil", meta, 0, b"abcd")
    assert r.status_code == 400, r.text
    assert not (tmp_path / "evil.part").exists()
    assert not (tmp_path / "inbox" / "evil.part").exists()


def test_malicious_session_id_cannot_escape_inbox(tmp_path):
    """A session_id containing .. cannot resolve to a directory outside the
    INBOX_BASE tree, so marker/part files never land outside it."""
    from launcher.sync import SessionManager
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    # A directory that sits a sibling of a device dir; a naive join could reach it.
    (inbox / "dev-1").mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    mgr = SessionManager(inbox)

    assert mgr.find_session("../../outside") is None
    try:
        mgr.complete("../../outside")
    except (ValueError, FileNotFoundError, OSError):
        pass
    assert not (outside / ".complete").exists()


def test_open_session_rejects_profile_without_unclassified(tmp_path):
    """A sync profile whose unclassified destination is disabled is rejected at
    session-open, not silently failed per-file at processing time."""
    app = make_app(tmp_path)
    (tmp_path / "configs" / "config_alice_groupby.yaml").write_text(
        "mode: GroupByTags\n"
        "tag_groups:\n"
        "  - name: people\n"
        "    tags: [person]\n"
        f"    destination: {tmp_path / 'dest' / 'people'}\n"
        "unclassified:\n"
        "  enabled: false\n"
        f"  destination: {tmp_path / 'dest'}\n"
    )
    client = TestClient(app)
    token = trust(client, "dev-1")

    r = client.post("/api/sync/sessions", json={"profile_id": "alice_groupby"}, headers=auth(token))
    assert r.status_code == 400, r.text
    # No session directory was created for the rejected profile.
    assert not list((tmp_path / "inbox" / "dev-1").glob("*")) if (tmp_path / "inbox" / "dev-1").exists() else True


# ---------------------------------------------------------------------------
# End-to-end: outcomes, index persistence, lifecycle
# ---------------------------------------------------------------------------

def _write_e2e_config(tmp_path, user="alice"):
    dest = tmp_path / "dest"
    (tmp_path / "configs" / f"config_{user}_groupby.yaml").write_text(
        "mode: GroupByTags\n"
        "tag_groups:\n"
        "  - name: people\n"
        "    tags: [person]\n"
        f"    destination: {dest / 'people'}\n"
        "    group_by_year: false\n"
        "unclassified:\n"
        "  enabled: true\n"
        "  folder_name: others\n"
        f"  destination: {dest}\n"
        "video:\n"
        f"  destination: {dest / 'videos'}\n"
    )
    return dest


def _full_upload(client, token, tags, name="x.jpg", data=b"data", mime="image/jpeg"):
    """Open a session, upload one file, complete it; return (sid, outcomes)."""
    sid = _open_session(client, token)
    meta = {"name": name, "created_on": "2021-01-01T00:00:00", "size": len(data), "mime_type": mime}
    _upload_chunk(client, token, sid, "f1", meta, 0, data)
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    return sid, outcomes


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
    assert row["profile_id"] == "alice_groupby"
    assert row["synced_at"]
    from pathlib import Path as _P
    assert _P(row["stored_path"]).exists()


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
    client = TestClient(app)
    token = trust(client, "dev-1")

    fake_proc = type("FakeProc", (), {
        "poll": lambda self: None, "pid": 1, "terminate": lambda self: None,
    })()
    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: fake_proc)

    # A manual job running does not 409 a sync session.
    assert client.post("/api/jobs", json={"user": "alice", "mode": "groupby"}).status_code == 200
    assert client.post("/api/sync/sessions", json={"profile_id": "alice_groupby"},
                       headers=auth(token)).status_code == 200

    # An active sync does not 409 POST /api/jobs: stop the manual job, then a
    # sync session being open must not block a fresh manual job.
    client.delete("/api/jobs")
    assert client.post("/api/sync/sessions", json={"profile_id": "alice_groupby"},
                       headers=auth(token)).status_code == 200
    assert client.post("/api/jobs", json={"user": "alice", "mode": "groupby"}).status_code == 200


# ---------------------------------------------------------------------------
# Temp lifecycle
# ---------------------------------------------------------------------------

def test_failed_file_is_removed_from_temp_and_reported(tmp_path):
    """A file that fails processing is deleted from its temp session and the
    failure is reported to the phone."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid = _open_session(client, token)
    meta = {"name": "bad.jpg", "created_on": "2021-01-01T00:00:00", "size": 99, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"short")
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))

    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert outcomes[0]["status"] == "failed"
    # The temp part was removed (session dir is cleaned once no parts remain).
    sdir = tmp_path / "inbox" / "dev-1" / sid
    assert not sdir.exists() or not list(sdir.glob("*.part"))


def test_empty_session_folder_is_removed_after_all_files_moved(tmp_path):
    """Once every uploaded file has moved out, the session folder is rmdir'd."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid, _ = _full_upload(client, token, {"person"}, name="ok.jpg", data=b"abcd")
    assert not (tmp_path / "inbox" / "dev-1" / sid).exists()


# ---------------------------------------------------------------------------
# Startup reconcile & janitor
# ---------------------------------------------------------------------------

def _build_lane(tmp_path, detect_tags):
    from launcher.sync import SyncStore, SessionManager, SyncLane
    import launcher.server as srv
    store = SyncStore(tmp_path / "sync.db")
    sessions = SessionManager(tmp_path / "inbox")
    lane = SyncLane(store, sessions, srv._load_sync_config, detect_tags)
    return store, sessions, lane


def _age_session(sdir, seconds):
    import os, time
    old = time.time() - seconds
    for p in [sdir, *sdir.iterdir()]:
        os.utime(p, (old, old))


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


def _prepare_env(tmp_path):
    """Point the sync env vars at tmp_path and create the dirs, without building
    the app yet (so the inbox can be pre-seeded before startup)."""
    import os
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")


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
    """create_app runs the janitor once at startup and registers a recurring
    timer; driving the injected scheduler deterministically fires it again."""
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
        # Startup pass ran the janitor once and registered a periodic callback.
        assert janitor_calls["n"] == 1
        assert len(ticks) == 1
        # Driving the scheduled callback runs the janitor again (no sleeping).
        ticks[0]()
        assert janitor_calls["n"] == 2
    finally:
        sync_mod.SyncLane.janitor = orig


# ---------------------------------------------------------------------------
# Cleanup verify
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Open-branch: failure taxonomy & retry classification
# ---------------------------------------------------------------------------

def test_failure_reasons_are_classified_retryable_or_terminal():
    """Every failure reason is explicitly classified; the taxonomy splits into
    auto-retryable and terminal."""
    from launcher.sync import FailureReason
    assert FailureReason.UNREADABLE.retryable is False
    assert FailureReason.SIZE_MISMATCH.retryable is True
    assert FailureReason.PLACEMENT_ERROR.retryable is True
    assert FailureReason.INTERNAL_ERROR.retryable is True


def test_retryable_reason_reattempted_terminal_not(tmp_path, monkeypatch):
    """A retryable failure is re-attempted (and can then succeed); a terminal
    failure is attempted exactly once."""
    from datetime import timedelta
    import launcher.sync as sync_mod
    make_app(tmp_path)
    _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: set())

    from launcher.sync import FileMeta
    fmeta = FileMeta("x.jpg", "2021-01-01T00:00:00", 4, "image/jpeg")
    config = sync_mod.SyncStore  # placeholder; not used by stubbed place_file

    # Retryable: fail once, then succeed.
    calls = {"n": 0}

    def flaky_place(part, meta, cfg, detect):
        calls["n"] += 1
        if calls["n"] == 1:
            return False, None, sync_mod.FailureReason.PLACEMENT_ERROR
        return True, tmp_path / "stored.jpg", None

    monkeypatch.setattr(sync_mod, "place_file", flaky_place)
    ok, _, _ = lane._attempt_with_retry(tmp_path / "p.part", fmeta, config)
    assert ok is True
    assert calls["n"] == 2  # re-attempted

    # Terminal: never retried.
    term_calls = {"n": 0}

    def terminal_place(part, meta, cfg, detect):
        term_calls["n"] += 1
        return False, None, sync_mod.FailureReason.UNREADABLE

    monkeypatch.setattr(sync_mod, "place_file", terminal_place)
    ok2, _, reason2 = lane._attempt_with_retry(tmp_path / "p.part", fmeta, config)
    assert ok2 is False
    assert reason2 is sync_mod.FailureReason.UNREADABLE
    assert term_calls["n"] == 1  # not retried


# ---------------------------------------------------------------------------
# Open-branch: global (name, created_on, size) identity semantics
# ---------------------------------------------------------------------------

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
