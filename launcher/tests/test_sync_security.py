"""Path-traversal hardening and path-safety of names/ids."""
from __future__ import annotations

from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    register,
    trust,
    auth,
    _configure_sync,
    _open_session,
    _upload_chunk,
    _make_config,
    _meta,
    _write_e2e_config,
    _build_lane,
)


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
    longer places unclassified images, so a session opens successfully for a
    configured sync template and a known DB profile."""
    app = make_app(tmp_path)
    _configure_sync(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    r = client.post("/api/sync/sessions", json={"profile_id": "Alice"}, headers=auth(token))
    assert r.status_code == 200, r.text
    assert r.json()["session_id"]


def test_open_session_unknown_profile_is_404(tmp_path):
    """A configured server rejects a session for a profile_id absent from the
    profiles table with 404, and a legacy config_<user>_groupby.yaml file does
    not satisfy session-open (no auto-migration)."""
    app = make_app(tmp_path)
    _configure_sync(tmp_path)  # registers DB profile "Alice"
    (tmp_path / "configs" / "config_carol_groupby.yaml").write_text("mode: GroupByTags\n")
    client = TestClient(app)
    token = trust(client, "dev-1")

    assert client.post(
        "/api/sync/sessions", json={"profile_id": "Alice"}, headers=auth(token)
    ).status_code == 200
    assert client.post(
        "/api/sync/sessions", json={"profile_id": "Nobody"}, headers=auth(token)
    ).status_code == 404
    # Legacy file-based profile_id is not honoured.
    assert client.post(
        "/api/sync/sessions", json={"profile_id": "carol_groupby"}, headers=auth(token)
    ).status_code == 404


def test_open_session_returns_503_when_sync_unconfigured_before_404(tmp_path):
    """When server.yaml has no sync: section (or empty tag_groups), session-open
    is 503, and that 503 is returned BEFORE the unknown-profile 404 check."""
    app = make_app(tmp_path)  # no server.yaml written
    client = TestClient(app)
    token = trust(client, "dev-1")

    # Missing template + unknown profile -> 503, not 404.
    assert client.post(
        "/api/sync/sessions", json={"profile_id": "Nobody"}, headers=auth(token)
    ).status_code == 503

    # An empty tag_groups is likewise "unconfigured".
    (tmp_path / "configs" / "server.yaml").write_text("sync:\n  tag_groups: []\n")
    assert client.post(
        "/api/sync/sessions", json={"profile_id": "Nobody"}, headers=auth(token)
    ).status_code == 503


def test_server_boots_and_serves_when_sync_unconfigured(tmp_path):
    """With no sync: template, the server still boots and serves gallery and
    manual-sort routes."""
    app = make_app(tmp_path)
    (tmp_path / "configs" / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    client = TestClient(app)

    assert client.get("/api/ping").json() == {"status": "ok"}
    users = client.get("/api/users").json()
    assert any(u["user"] == "alice" for u in users)


def test_load_sync_config_ignores_profile_id_and_shares_destinations(tmp_path):
    """_load_sync_config builds the placement Config from server.yaml's sync:
    section, ignoring profile_id, so different profiles resolve to identical
    shared destinations."""
    import launcher.server as srv
    make_app(tmp_path)  # sets CONFIGS_DIR for srv._load_sync_config
    dest = _configure_sync(tmp_path)

    cfg_a = srv._load_sync_config("Alice")
    cfg_b = srv._load_sync_config("SomeoneElse")

    assert [tg.destination for tg in cfg_a.tag_groups] == [str(dest / "people")]
    assert cfg_a.tag_groups[0].destination == cfg_b.tag_groups[0].destination
    assert cfg_a.video.destination == str(dest / "videos")
    assert cfg_a.on_collision == "rename"


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
