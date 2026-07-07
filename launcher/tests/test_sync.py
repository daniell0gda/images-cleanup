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
    """Register, approve, and return the bearer token.

    The token is gated behind the pairing code, so we replay the code the
    register response handed us via the X-Pairing-Code header.
    """
    code = register(client, device_id, name).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    status = client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()
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
    code = register(client, "dev-1").json()["pairing_code"]

    before = client.get("/api/sync/devices/dev-1/status").json()
    assert before["status"] == "pending"
    assert "token" not in before

    client.post("/api/sync/devices/dev-1/approve")
    after = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
    assert after["status"] == "trusted"
    assert after["token"]

    client.post("/api/sync/devices/dev-1/revoke")
    revoked = client.get("/api/sync/devices/dev-1/status").json()
    assert revoked["status"] == "revoked"


def test_pending_status_does_not_echo_pairing_code(tmp_path):
    """The pairing code now gates token retrieval, so it is a secret and must
    not be echoed back in the status response body — the phone learns its code
    from the register response, not from status polling."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")

    status = client.get("/api/sync/devices/dev-1/status").json()
    assert status["status"] == "pending"
    assert "pairing_code" not in status


def test_status_returns_token_only_with_correct_pairing_code(tmp_path):
    """A trusted device's token is returned by status only when the caller
    presents the matching pairing code via the X-Pairing-Code header."""
    app = make_app(tmp_path)
    client = TestClient(app)
    code = register(client, "dev-1").json()["pairing_code"]
    approve = client.post("/api/sync/devices/dev-1/approve").json()
    issued = approve["token"]

    ok = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
    assert ok["status"] == "trusted"
    assert ok["token"] == issued


def test_status_omits_token_without_or_with_wrong_pairing_code(tmp_path):
    """A trusted device with a missing or wrong pairing code still reports
    trusted, but the token is withheld and the code is not echoed back."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")
    client.post("/api/sync/devices/dev-1/approve")

    missing = client.get("/api/sync/devices/dev-1/status").json()
    assert missing["status"] == "trusted"
    assert "token" not in missing
    assert "pairing_code" not in missing

    wrong = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": "000000"}
    ).json()
    assert wrong["status"] == "trusted"
    assert "token" not in wrong
    assert "pairing_code" not in wrong


def test_status_unknown_device_404_regardless_of_code(tmp_path):
    """An unknown device_id returns 404 even when a pairing code is supplied."""
    app = make_app(tmp_path)
    client = TestClient(app)
    assert client.get("/api/sync/devices/nope/status").status_code == 404
    assert (
        client.get(
            "/api/sync/devices/nope/status", headers={"X-Pairing-Code": "123456"}
        ).status_code
        == 404
    )


def test_approve_sets_approved_at_and_status_token_matches(tmp_path):
    """Approval issues a token and sets approved_at; the status endpoint
    returns the same token that approval issued."""
    app = make_app(tmp_path)
    client = TestClient(app)
    code = register(client, "dev-1").json()["pairing_code"]

    approve = client.post("/api/sync/devices/dev-1/approve").json()
    issued = approve["token"]

    status = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
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

def _make_config(tmp_path, *, video=True, on_collision="rename"):
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
        on_collision=on_collision,
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
    """By default (non-sync path), an image matching no group lands in the
    unclassified destination — the CLI sorter behavior is unchanged."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"cat"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "others" / "2021"


def test_discard_unclassified_does_not_place_and_is_distinguishable(tmp_path):
    """With discard_unclassified=True, an image matching no group is NOT moved
    into the unclassified/others destination, and the result is distinguishable
    from both a synced result (ok+path) and a failed result (reason set)."""
    from launcher.sync import place_file, DISCARDED
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(
        part, meta, cfg, lambda p: {"cat"}, discard_unclassified=True
    )

    assert ok is False  # not synced
    assert reason is None  # not failed
    assert stored is DISCARDED  # distinguishable sentinel
    assert not (tmp_path / "dest" / "others").exists()


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


def test_placement_honors_on_collision_skip_treating_clash_as_already_backed_up(tmp_path):
    """With on_collision='skip', a name clash is not renamed: the existing file is
    left untouched and the upload is reported as already backed up (ok, no _1
    duplicate), pointing the synced index at the existing destination file."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path, on_collision="skip")

    p1 = _part(tmp_path, "a.part", b"first")
    ok1, s1, _ = place_file(p1, _meta("dup.jpg", 5), cfg, lambda p: {"cat"})
    p2 = _part(tmp_path, "b.part", b"secondchunk")
    ok2, s2, reason2 = place_file(p2, _meta("dup.jpg", 11), cfg, lambda p: {"cat"})

    assert ok1 and ok2 and reason2 is None
    # No rename: the second resolves to the same existing destination path.
    assert s1 == s2
    # The existing file is preserved (not overwritten by the skipped upload).
    assert s1.read_bytes() == b"first"
    # No _1 duplicate was created.
    assert not (s1.parent / "dup_1.jpg").exists()


def test_unparseable_created_on_falls_back_to_epoch_year_bucket(tmp_path):
    """A file whose created_on cannot be parsed is placed deterministically into
    the 1970 (epoch) year bucket rather than failing the upload. Pins the
    documented fallback: _parse_created_on returns datetime.fromtimestamp(0)."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "bad-date.part", b"hello")
    meta = _meta("bad-date.jpg", 5, created="not-a-date")

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"cat"})

    assert ok and reason is None
    assert stored.exists()
    assert stored.parent == tmp_path / "dest" / "others" / "1970"


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


def test_open_session_no_longer_gated_by_unclassified_enabled(tmp_path):
    """The unclassified.enabled session-open gate is removed: the sync lane no
    longer places unclassified images, so a profile with unclassified.enabled
    false opens a session successfully instead of being rejected."""
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
    assert r.status_code == 200, r.text
    assert r.json()["session_id"]


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
        "/api/sync/sessions", json={"profile_id": "alice_groupby"}, headers=auth(token)
    ).json()["session_id"]
    meta_default = json.loads(
        (tmp_path / "inbox" / "dev-1" / sid_default / "session.json").read_text(encoding="utf-8")
    )
    assert meta_default.get("force_place", False) is False

    # Explicit force_place=true is persisted.
    sid_force = client.post(
        "/api/sync/sessions",
        json={"profile_id": "alice_groupby", "force_place": True},
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
        json={"profile_id": "alice_groupby", "force_place": True},
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
    assert row["profile_id"] == "alice_groupby"
    assert row["synced_at"]
    from pathlib import Path as _P
    assert _P(row["stored_path"]).exists()


def _jpeg_bytes(color=(10, 20, 30)):
    """A minimal real JPEG so piexif has valid metadata to work with."""
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (16, 16), color).save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def _read_profile_tag(path):
    """Read back the EXIF XPKeywords string, or None if absent/unreadable."""
    import piexif
    try:
        raw = piexif.load(str(path))["0th"].get(piexif.ImageIFD.XPKeywords)
    except Exception:
        return None
    return bytes(raw).decode("utf-16le").rstrip("\x00") if raw is not None else None


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
    assert Image.open(p).tobytes() == before  # pixels untouched


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
    assert junk.read_bytes() == b"not a real jpeg"  # not further corrupted


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
    assert _read_profile_tag(Path(stored)) == "profile:alice"


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


def test_non_matching_image_is_unclassified_not_synced_and_cleaned_up(tmp_path):
    """A sync-lane image matching no tag_group: no synced_files row, its .part
    (and any renamed copy) is gone from the session folder, and the per-file
    outcome status is 'unclassified' (persisted + returned by the outcomes API)."""
    app = make_app(tmp_path, detect_tags=lambda p: {"cat"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid, outcomes = _full_upload(client, token, {"cat"}, name="np.jpg", data=b"abcd")

    assert outcomes[0]["status"] == "unclassified"
    # No synced_files row recorded for the discarded image.
    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    assert con.execute("SELECT COUNT(*) FROM synced_files").fetchone()[0] == 0
    con.close()
    # The temp part (and any renamed copy) is gone from the session folder.
    sdir = tmp_path / "inbox" / "dev-1" / sid
    assert not sdir.exists() or (
        not list(sdir.glob("*.part")) and not (sdir / "np.jpg").exists()
    )
    # The unclassified outcome was persisted and round-trips via the API.
    again = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert again[0]["status"] == "unclassified"


def test_unclassified_outcome_row_carries_only_identity_keys(tmp_path):
    """An 'unclassified' outcome row carries only file_id, name, status — no
    reason/retryable — while failed rows still get reason/retryable."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_outcome("s1", {"file_id": "f1", "name": "np.jpg", "status": "unclassified"})
    store.record_outcome("s1", {
        "file_id": "f2", "name": "bad.jpg", "status": "failed",
        "reason": "size_mismatch", "retryable": True,
    })
    by_status = {r["status"]: r for r in store.outcomes_for("s1")}

    assert set(by_status["unclassified"].keys()) == {"file_id", "name", "status"}
    assert set(by_status["failed"].keys()) == {"file_id", "name", "status", "reason", "retryable"}


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
# Index refresh + server settings
# ---------------------------------------------------------------------------

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

    def flaky_place(part, meta, cfg, detect, **kwargs):
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

    def terminal_place(part, meta, cfg, detect, **kwargs):
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


# ---------------------------------------------------------------------------
# Path-safety
# ---------------------------------------------------------------------------

def test_failed_placement_cleanup_never_deletes_file_outside_session_dir(tmp_path):
    """When a file's stored File-Name is a traversal (not a bare basename), the
    failed-placement cleanup branch must not unlink anything outside the session
    directory."""
    import json
    make_app(tmp_path)
    _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: set())

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    sdir = sessions.find_session(sid)

    # A real, unrelated file sitting where the traversal name would resolve to.
    outside = sdir.parent.parent / "evil.txt"
    outside.write_text("precious", encoding="utf-8")
    assert outside.exists()

    # Seed the session with a .part plus a .meta whose stored name escapes sdir.
    (sdir / "f1.part").write_bytes(b"data")
    (sdir / "f1.meta").write_text(
        json.dumps({
            "name": "../../evil.txt",
            "created_on": "2021-01-01T00:00:00",
            "size": 4,
            "mime_type": "image/jpeg",
        }),
        encoding="utf-8",
    )

    outcomes = lane.process_session(sid)

    # Placement fails (non-basename name), but the outside file is untouched.
    assert outside.exists(), "cleanup deleted a file outside the session dir"
    assert outcomes and outcomes[0]["status"] == "failed"


def test_register_rejects_unsafe_device_id_and_creates_no_outside_dir(tmp_path):
    """A device_id containing path separators or '..' is rejected with HTTP 400
    and no session/inbox directory is created outside the inbox for it."""
    app = make_app(tmp_path)
    client = TestClient(app)
    inbox = tmp_path / "inbox"

    for bad in ("../evil", "a/b", "..\\evil", ".."):
        r = register(client, bad, "Pixel")
        assert r.status_code == 400, (bad, r.status_code, r.text)

    # Nothing escaped the inbox; the inbox itself holds no stray dirs.
    assert not (inbox.parent / "evil").exists()
    assert list(inbox.iterdir()) == []


# ---------------------------------------------------------------------------
# Session ownership
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Concurrency & request limits
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Re-register safety (trusted devices must not be reset)
# ---------------------------------------------------------------------------

def test_reregister_trusted_device_preserves_token_and_status(tmp_path):
    """Re-registering an already-trusted device_id must NOT null its token or
    downgrade it to pending: the store keeps the token and 'trusted' status."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    token = store.approve_device("dev-1")
    assert token

    store.register_device("dev-1", "Pixel")

    row = store.get_device("dev-1")
    assert row["status"] == "trusted"
    assert row["token"] == token


def test_reregister_trusted_device_is_noop_returning_status_without_code(tmp_path):
    """Re-registering a trusted device is a safe no-op: the store reports the
    existing trusted status and issues no fresh pairing code, and the token is
    never minted anew."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    token = store.approve_device("dev-1")

    result = store.register_device("dev-1", "Pixel")
    assert result["status"] == "trusted"
    assert result.get("pairing_code") is None
    # Token untouched.
    assert store.get_device("dev-1")["token"] == token


def test_register_absent_device_creates_pending_with_fresh_code(tmp_path):
    """Registering an unknown device_id creates it as pending with a fresh
    6-digit pairing code and a NULL token (unchanged behavior)."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")

    result = store.register_device("dev-new", "Pixel")
    assert result["status"] == "pending"
    assert re.match(r"^\d{6}$", result["pairing_code"])

    row = store.get_device("dev-new")
    assert row["status"] == "pending"
    assert row["token"] is None


def test_reregister_pending_device_still_refreshes_without_touching_trusted(tmp_path):
    """Re-registering an existing pending device still works (returns pending with
    a code) and does not affect a different, already-trusted device."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-trusted", "Galaxy")
    trusted_token = store.approve_device("dev-trusted")

    store.register_device("dev-pending", "Pixel")
    result = store.register_device("dev-pending", "Pixel")
    assert result["status"] == "pending"
    assert re.match(r"^\d{6}$", result["pairing_code"])

    # The unrelated trusted device is untouched.
    other = store.get_device("dev-trusted")
    assert other["status"] == "trusted"
    assert other["token"] == trusted_token


def test_register_device_does_not_insert_or_replace(tmp_path):
    """The trusted-preservation guard lives at the store layer: register_device
    must not blindly INSERT OR REPLACE (which would wipe a trusted token). A
    re-register of a trusted device keeps its created_at/approved_at row intact."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    store.approve_device("dev-1")
    before = store.get_device("dev-1")

    store.register_device("dev-1", "Pixel")
    after = store.get_device("dev-1")

    assert after["created_at"] == before["created_at"]
    assert after["approved_at"] == before["approved_at"]
    assert after["token"] == before["token"]


# ---------------------------------------------------------------------------
# Sync -> media index wiring
# ---------------------------------------------------------------------------

def _dated_jpeg_bytes(exif_date: str | None = None, size=(8, 8), color=(10, 20, 30)) -> bytes:
    """A real JPEG payload, optionally carrying a DateTimeOriginal in the Exif
    IFD (where a real camera writes it, so it survives the sync lane's
    profile-tag rewrite)."""
    import io
    from PIL import Image

    img = Image.new("RGB", size, color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    if exif_date is None:
        return buf.getvalue()

    import piexif

    exif = {"Exif": {piexif.ExifIFD.DateTimeOriginal: exif_date.encode("ascii")}}
    out = io.BytesIO()
    piexif.insert(piexif.dump(exif), buf.getvalue(), out)
    return out.getvalue()


def _media_indexer(tmp_path, folders):
    from launcher.media import MediaIndexer
    return MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(f) for f in folders],
    )


def _run_synced_session(tmp_path, data, created_on):
    """Place one uploaded file via the sync lane with a media index attached;
    return (indexer, dest)."""
    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})
    indexer = _media_indexer(tmp_path, [dest])
    lane.media_indexer = indexer

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(
        sid, "f1", FileMeta("shot.jpg", created_on, len(data), "image/jpeg"), 0, data
    )
    sessions.complete(sid)
    lane.process_session(sid)
    return indexer, dest


def test_process_session_indexes_synced_file_without_a_build(tmp_path):
    """A file placed by process_session is in the media index immediately: its
    timeline row is present with no build() call."""
    indexer, dest = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(), "2021-01-01T00:00:00"
    )

    rows = indexer.timeline()
    paths = [r["path"] for r in rows]
    placed = str(list((dest / "people").rglob("shot*.jpg"))[0])
    assert placed in paths


def test_synced_image_without_exif_uses_created_on_for_date_taken(tmp_path):
    """With no EXIF capture date, date_taken comes from the phone's created_on,
    not from the sync-time file mtime."""
    from datetime import datetime

    indexer, _ = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date=None), "2018-05-04T09:08:07"
    )

    row = indexer.timeline()[0]
    assert row["date_taken"] == datetime(2018, 5, 4, 9, 8, 7).isoformat()


def test_synced_image_with_exif_keeps_exif_date_over_created_on(tmp_path):
    """A present EXIF capture date wins: created_on does not override it."""
    from datetime import datetime

    indexer, _ = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date="2015:03:02 01:00:00"), "2021-01-01T00:00:00"
    )

    row = indexer.timeline()[0]
    assert row["date_taken"] == datetime(2015, 3, 2, 1, 0, 0).isoformat()


def test_synced_image_unparseable_created_on_falls_back_to_mtime(tmp_path):
    """A missing/garbage created_on indexes without error and date_taken falls
    back to the file mtime (never the 1970 epoch)."""
    from datetime import datetime

    indexer, dest = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date=None), "not-a-date"
    )

    row = indexer.timeline()[0]
    placed = list((dest / "people").rglob("shot*.jpg"))[0]
    from launcher.media import _mtime_iso
    assert row["date_taken"] == _mtime_iso(placed.stat().st_mtime)
    assert not row["date_taken"].startswith("1970")


def test_synced_photo_lands_at_chronological_position_in_timeline(tmp_path):
    """A newly synced photo is ordered by its capture date relative to
    pre-existing media, not by sync time (which is 'now')."""
    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})
    indexer = _media_indexer(tmp_path, [dest])
    lane.media_indexer = indexer

    # Pre-existing media: one older (2010), one newer (2030) than the synced photo.
    people = dest / "people"
    (people).mkdir(parents=True, exist_ok=True)
    (people / "old.jpg").write_bytes(_dated_jpeg_bytes(exif_date="2010:01:01 00:00:00"))
    (people / "new.jpg").write_bytes(_dated_jpeg_bytes(exif_date="2030:01:01 00:00:00"))
    indexer.index_path(people / "old.jpg")
    indexer.index_path(people / "new.jpg")

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    data = _dated_jpeg_bytes(exif_date=None)
    sessions.write_chunk(
        sid, "f1", FileMeta("shot.jpg", "2020-06-15T12:00:00", len(data), "image/jpeg"), 0, data
    )
    sessions.complete(sid)
    lane.process_session(sid)

    names = [Path(r["path"]).name for r in indexer.timeline()]
    # Newest-first: 2030, then the 2020 synced photo, then 2010.
    assert names.index("new.jpg") < names.index("shot.jpg") < names.index("old.jpg")
