"""Tests for the launcher FastAPI server."""
from __future__ import annotations

import os
import pytest
from pathlib import Path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_app(configs_dir: Path):
    """Build a fresh FastAPI app with isolated state pointed at configs_dir."""
    os.environ["CONFIGS_DIR"] = str(configs_dir)
    import launcher.server as srv
    return srv.create_app(srv._JobState())


# ---------------------------------------------------------------------------
# Criterion: GET /api/users — both modes for alice
# ---------------------------------------------------------------------------

def test_get_users_returns_alice_with_both_modes(tmp_path):
    """When CONFIGS_DIR has config_alice_groupby.yaml and config_alice_similarity.yaml,
    GET /api/users returns a list including alice with both modes available."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    (tmp_path / "config_alice_similarity.yaml").write_text("mode: SimilaritySearch\n")

    from fastapi.testclient import TestClient
    app = make_app(tmp_path)
    client = TestClient(app)
    response = client.get("/api/users")

    assert response.status_code == 200
    users = response.json()
    alice = next((u for u in users if u["user"] == "alice"), None)
    assert alice is not None, f"alice not found in {users}"
    assert "groupby" in alice["modes"]
    assert "similarity" in alice["modes"]


# ---------------------------------------------------------------------------
# Criterion: GET /api/users — bob with groupby only
# ---------------------------------------------------------------------------

def test_get_users_bob_groupby_only(tmp_path):
    """When CONFIGS_DIR contains only config_bob_groupby.yaml, bob has only groupby mode."""
    (tmp_path / "config_bob_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient
    app = make_app(tmp_path)
    client = TestClient(app)
    response = client.get("/api/users")

    assert response.status_code == 200
    users = response.json()
    bob = next((u for u in users if u["user"] == "bob"), None)
    assert bob is not None, f"bob not found in {users}"
    assert "groupby" in bob["modes"]
    assert "similarity" not in bob["modes"]


# ---------------------------------------------------------------------------
# Criterion: GET /api/users — empty CONFIGS_DIR returns empty list
# ---------------------------------------------------------------------------

def test_get_users_empty_dir_returns_empty_list(tmp_path):
    """When CONFIGS_DIR is empty, GET /api/users returns an empty list without error."""
    from fastapi.testclient import TestClient
    app = make_app(tmp_path)
    client = TestClient(app)
    response = client.get("/api/users")

    assert response.status_code == 200
    assert response.json() == []


# ---------------------------------------------------------------------------
# Criterion: POST /api/jobs spawns subprocess and 409 on second call
# ---------------------------------------------------------------------------

def test_post_jobs_spawns_subprocess_and_returns_200(tmp_path, monkeypatch):
    """POST /api/jobs with valid {user, mode} spawns subprocess and returns 200."""
    config_file = tmp_path / "config_alice_groupby.yaml"
    config_file.write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient

    app = make_app(tmp_path)

    # Mock subprocess so we don't actually spawn a sorter
    fake_proc = type("FakeProc", (), {
        "poll": lambda self: None,  # still running
        "pid": 12345,
        "terminate": lambda self: None,
    })()

    spawned = []

    def fake_popen(cmd, **kwargs):
        spawned.append((cmd, kwargs))
        return fake_proc

    monkeypatch.setattr("subprocess.Popen", fake_popen)

    client = TestClient(app)
    response = client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    assert len(spawned) == 1
    body = response.json()
    assert body["status"] == "running"
    assert body["user"] == "alice"
    assert body["mode"] == "groupby"


def test_post_jobs_returns_409_when_job_already_running(tmp_path, monkeypatch):
    """A second POST /api/jobs while a job is running returns 409 (conflict)."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    (tmp_path / "config_alice_similarity.yaml").write_text("mode: SimilaritySearch\n")

    from fastapi.testclient import TestClient

    app = make_app(tmp_path)

    fake_proc = type("FakeProc", (), {
        "poll": lambda self: None,
        "pid": 12345,
        "terminate": lambda self: None,
    })()

    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: fake_proc)

    client = TestClient(app)
    r1 = client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})
    assert r1.status_code == 200

    r2 = client.post("/api/jobs", json={"user": "alice", "mode": "similarity"})
    assert r2.status_code == 409


# ---------------------------------------------------------------------------
# Criterion: subprocess receives LAUNCHER_PUBLIC_PORT env var
# ---------------------------------------------------------------------------

def test_subprocess_receives_launcher_url_env(tmp_path, monkeypatch):
    """The spawned subprocess gets LAUNCHER_PUBLIC_PORT in its environment so the
    sorter can build a 'Back to launcher' link on the correct host port."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient
    app = make_app(tmp_path)

    captured_env = {}

    def fake_popen(cmd, **kwargs):
        captured_env.update(kwargs.get("env", {}))
        return type("FakeProc", (), {
            "poll": lambda self: None,
            "pid": 99,
            "terminate": lambda self: None,
        })()

    monkeypatch.setattr("subprocess.Popen", fake_popen)

    client = TestClient(app)
    client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    assert "LAUNCHER_PUBLIC_PORT" in captured_env, f"LAUNCHER_PUBLIC_PORT not in env: {captured_env}"
    assert captured_env["LAUNCHER_PUBLIC_PORT"] == "7000"


def test_subprocess_receives_errors_db_env(tmp_path, monkeypatch):
    """The spawned sorter subprocess gets ERRORS_DB in its environment so its log
    capture writes into the same errors DB the launcher UI reads. Even when the
    launcher's own process has no ERRORS_DB set, post_jobs injects the resolved
    default path so the child still shares one DB."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")
    monkeypatch.delenv("ERRORS_DB", raising=False)

    from fastapi.testclient import TestClient
    from launcher.errors import errors_db_path
    app = make_app(tmp_path)

    captured_env = {}

    def fake_popen(cmd, **kwargs):
        captured_env.update(kwargs.get("env", {}))
        return type("FakeProc", (), {
            "poll": lambda self: None,
            "pid": 99,
            "terminate": lambda self: None,
        })()

    monkeypatch.setattr("subprocess.Popen", fake_popen)

    client = TestClient(app)
    client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    assert captured_env.get("ERRORS_DB") == str(errors_db_path())


# ---------------------------------------------------------------------------
# Criterion: GET /api/status — idle after subprocess exits
# ---------------------------------------------------------------------------

def test_get_status_idle_after_subprocess_exits(tmp_path, monkeypatch):
    """After the subprocess exits, GET /api/status reports idle."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient

    app = make_app(tmp_path)

    poll_results = [None]  # None = still running

    class FakeProc:
        pid = 42
        def poll(self):
            return poll_results[0]
        def terminate(self):
            pass

    fake_proc = FakeProc()
    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: fake_proc)

    client = TestClient(app)
    client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    # Simulate process exit
    poll_results[0] = 0

    response = client.get("/api/status")
    assert response.status_code == 200
    assert response.json()["status"] == "idle"


# ---------------------------------------------------------------------------
# Criterion: GET /api/status while running
# ---------------------------------------------------------------------------

def test_get_status_running_returns_user_and_mode(tmp_path, monkeypatch):
    """GET /api/status while a job is running returns {status: running, user, mode}."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient
    app = make_app(tmp_path)

    fake_proc = type("FakeProc", (), {
        "poll": lambda self: None,
        "pid": 55,
        "terminate": lambda self: None,
    })()
    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: fake_proc)

    client = TestClient(app)
    client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    response = client.get("/api/status")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "running"
    assert body["user"] == "alice"
    assert body["mode"] == "groupby"


# ---------------------------------------------------------------------------
# Criterion: server binds port 7000; exits non-zero if occupied
# ---------------------------------------------------------------------------

def test_serve_exits_nonzero_if_port_7000_occupied(monkeypatch):
    """serve() must exit non-zero with a clear message when port 7000 is already taken."""
    import socket
    import launcher.server as srv

    def fake_bind_fail(addr):
        raise OSError("address already in use")

    from unittest.mock import MagicMock, patch

    with patch("socket.socket") as mock_cls:
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind.side_effect = fake_bind_fail
        mock_cls.return_value = mock_sock

        with pytest.raises(SystemExit) as exc_info:
            srv.serve()

    assert exc_info.value.code != 0


def test_serve_exits_with_message_mentioning_port(monkeypatch, capsys):
    """serve() must print a message mentioning port 7000 when it's already occupied."""
    import launcher.server as srv
    from unittest.mock import MagicMock, patch

    with patch("socket.socket") as mock_cls:
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind.side_effect = OSError("address already in use")
        mock_cls.return_value = mock_sock

        with pytest.raises(SystemExit):
            srv.serve()

    captured = capsys.readouterr()
    output = captured.out + captured.err
    assert "7000" in output


# ---------------------------------------------------------------------------
# Criterion: GET / serves frontend HTML (HTTP 200, Content-Type: text/html)
# ---------------------------------------------------------------------------

def test_get_root_serves_html(tmp_path):
    """GET / on the launcher returns HTTP 200 with Content-Type: text/html."""
    # Create a minimal dist/index.html
    dist = tmp_path / "dist"
    dist.mkdir()
    (dist / "index.html").write_text("<html><body>Launcher</body></html>")

    from fastapi.testclient import TestClient
    import launcher.server as srv

    state = srv._JobState()
    app = srv.create_app(state, dist_dir=dist)

    client = TestClient(app)
    response = client.get("/")

    assert response.status_code == 200
    assert "text/html" in response.headers.get("content-type", "")


# ---------------------------------------------------------------------------
# Criterion: on shutdown, launcher terminates child subprocess (no orphans)
# ---------------------------------------------------------------------------

def test_shutdown_terminates_child_subprocess(tmp_path, monkeypatch):
    """When the launcher app shuts down, any running subprocess is terminated."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient
    import launcher.server as srv

    state = srv._JobState()
    app = srv.create_app(state)

    terminated = []

    class FakeProc:
        pid = 77
        def poll(self):
            return None  # still running
        def terminate(self):
            terminated.append(True)
        def wait(self, timeout=None):
            pass

    monkeypatch.setattr("subprocess.Popen", lambda *a, **kw: FakeProc())

    client = TestClient(app)
    client.post("/api/jobs", json={"user": "alice", "mode": "groupby"})

    # Simulate shutdown by calling the shutdown handler directly
    srv.shutdown_handler(state)

    assert terminated, "terminate() must be called on the child subprocess during shutdown"


# ---------------------------------------------------------------------------
# Criterion: shutdown_handler is wired into server lifecycle via atexit
# ---------------------------------------------------------------------------

def test_serve_registers_shutdown_handler_via_atexit(monkeypatch):
    """serve() must register shutdown_handler via atexit so it's called on process exit."""
    import atexit
    import launcher.server as srv
    from unittest.mock import MagicMock, patch

    registered = []

    def fake_register(fn, *args, **kwargs):
        registered.append((fn, args, kwargs))

    with patch("socket.socket") as mock_cls, \
         patch("atexit.register", side_effect=fake_register), \
         patch("uvicorn.run"):
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind.return_value = None  # port is free
        mock_cls.return_value = mock_sock

        srv.serve()

    assert any(fn is srv.shutdown_handler for fn, args, kwargs in registered), (
        "shutdown_handler must be registered via atexit.register() inside serve()"
    )


# ---------------------------------------------------------------------------
# Criterion: POST /api/jobs returns 404 when config file does not exist
# ---------------------------------------------------------------------------

def test_post_jobs_returns_404_when_config_missing(tmp_path):
    """POST /api/jobs with a {user, mode} whose config file does not exist returns 404."""
    from fastapi.testclient import TestClient
    app = make_app(tmp_path)  # tmp_path has no config files
    client = TestClient(app)

    response = client.post("/api/jobs", json={"user": "ghost", "mode": "groupby"})

    assert response.status_code == 404, (
        f"Expected 404 for missing config, got {response.status_code}: {response.text}"
    )


# ---------------------------------------------------------------------------
# Criterion: serve() passes dist_dir to create_app so GET / returns 200
# ---------------------------------------------------------------------------

def test_serve_wires_dist_dir_so_get_root_returns_200(tmp_path, monkeypatch):
    """serve() must compute the dist_dir relative to server.py and pass it to
    create_app(), so that GET / returns 200 (not 404) when called via serve()."""
    import launcher.server as srv
    from unittest.mock import MagicMock, patch

    # Build a fake dist dir next to server.py (the real relative path serve() should use)
    server_dir = Path(srv.__file__).parent
    dist_dir = server_dir / "dist"
    dist_dir.mkdir(exist_ok=True)
    (dist_dir / "index.html").write_text("<html><body>Launcher</body></html>")

    captured_apps = []

    def fake_uvicorn_run(app, **kwargs):
        captured_apps.append(app)

    with patch("socket.socket") as mock_cls, \
         patch("uvicorn.run", side_effect=fake_uvicorn_run):
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind.return_value = None
        mock_cls.return_value = mock_sock

        srv.serve()

    assert captured_apps, "uvicorn.run was not called"

    from fastapi.testclient import TestClient
    client = TestClient(captured_apps[0])
    response = client.get("/")

    # Clean up the dist dir we created
    import shutil
    shutil.rmtree(dist_dir)

    assert response.status_code == 200, (
        f"Expected 200 for GET /, got {response.status_code}. "
        "serve() must pass dist_dir to create_app()."
    )
    assert "text/html" in response.headers.get("content-type", "")


# ---------------------------------------------------------------------------
# Criterion: saving a media folder makes the live indexer scan it (no restart)
# ---------------------------------------------------------------------------

def test_saving_media_folder_makes_build_scan_it(tmp_path, monkeypatch):
    """POST /api/settings with a new media folder must update the live indexer so
    a subsequent build scans that folder — without a container restart.

    Regression: the indexer read its folder list once at startup, so folders
    added via the settings UI were ignored until the process restarted, and a
    build indexed nothing (0 photos).
    """
    import time
    from PIL import Image

    # Isolate all media storage under tmp so the build is self-contained.
    monkeypatch.setenv("MEDIA_DB", str(tmp_path / "media.db"))
    monkeypatch.setenv("MEDIA_THUMBS_DIR", str(tmp_path / "thumbs"))
    monkeypatch.setenv("MEDIA_PROXIES_DIR", str(tmp_path / "proxies"))

    # A source folder with one real image, added *after* the app is created.
    src = tmp_path / "photos"
    src.mkdir()
    Image.new("RGB", (8, 8), "red").save(src / "pic.jpg")

    from fastapi.testclient import TestClient
    app = make_app(tmp_path)
    client = TestClient(app)

    r = client.post("/api/settings", json={
        "media_library": {
            "enabled": True,
            "folders": [str(src)],
            "schedule": "0 2 * * *",
        }
    })
    assert r.status_code == 200, f"settings save failed: {r.status_code} {r.text}"

    client.post("/api/media/build")
    deadline = time.monotonic() + 5.0
    status = client.get("/api/media/build/status").json()
    while status["state"] != "idle" and time.monotonic() < deadline:
        time.sleep(0.02)
        status = client.get("/api/media/build/status").json()

    assert str(src) not in status["skipped_roots"], (
        f"folder was skipped instead of scanned: {status}"
    )
    assert status["added"] >= 1, f"build indexed nothing: {status}"


# ---------------------------------------------------------------------------
# Criterion: management/admin surface is LAN-only (hidden from proxied requests)
# ---------------------------------------------------------------------------

# A reverse proxy (Traefik) stamps this on every forwarded request; its presence
# is what the guard uses to distinguish public traffic from a direct LAN client.
_PROXIED = {"X-Forwarded-For": "203.0.113.9"}


def test_is_public_path_classification():
    """Unit-level contract for the public/admin split the guard enforces."""
    from launcher.server import _is_public_path as pub

    # Public: app + pairing + shares.
    assert pub("GET", "/api/ping")
    assert pub("GET", "/api/media")
    assert pub("GET", "/api/media/42/thumb")
    assert pub("GET", "/api/albums")
    assert pub("POST", "/api/sync/reconcile")
    assert pub("POST", "/api/sync/devices")               # register
    assert pub("GET", "/api/sync/devices/abc/status")     # status poll
    assert pub("GET", "/share/tok/media/1/thumb")
    assert pub("POST", "/api/errors/report")              # device failure report

    # Admin: UI, jobs, settings, build, device listing / approve / revoke.
    assert not pub("GET", "/")
    assert not pub("GET", "/api/users")
    assert not pub("POST", "/api/jobs")
    assert not pub("POST", "/api/settings")
    assert not pub("POST", "/api/media/build")
    assert not pub("GET", "/api/media/build/status")
    assert not pub("GET", "/api/sync/devices")            # listing
    assert not pub("POST", "/api/sync/devices/abc/approve")
    assert not pub("POST", "/api/sync/devices/abc/revoke")
    assert not pub("GET", "/api/errors")                  # admin errors view
    assert not pub("DELETE", "/api/errors")
    assert not pub("DELETE", "/api/errors/1")


def test_admin_route_hidden_from_proxied_requests(tmp_path):
    """An admin route returns 404 when the request came through the proxy, but is
    served normally on a direct LAN connection."""
    (tmp_path / "config_alice_groupby.yaml").write_text("mode: GroupByTags\n")

    from fastapi.testclient import TestClient
    client = TestClient(make_app(tmp_path))

    assert client.get("/api/users", headers=_PROXIED).status_code == 404
    assert client.get("/api/users").status_code == 200


def test_device_approve_hidden_from_proxied_requests(tmp_path):
    """The unauthenticated approve/revoke routes must not be reachable publicly,
    or a remote attacker could self-approve a device and mint a token."""
    from fastapi.testclient import TestClient
    client = TestClient(make_app(tmp_path))

    assert client.post("/api/sync/devices/x/approve", headers=_PROXIED).status_code == 404
    assert client.post("/api/sync/devices/x/revoke", headers=_PROXIED).status_code == 404


def test_ping_is_reachable_when_proxied(tmp_path):
    """The reachability probe stays public so the app can connect over HTTPS."""
    from fastapi.testclient import TestClient
    client = TestClient(make_app(tmp_path))

    resp = client.get("/api/ping", headers=_PROXIED)
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_public_app_route_passes_guard_but_still_requires_token(tmp_path):
    """A proxied request to a public app route is not hidden by the guard; it is
    still rejected by its own auth (401) rather than 404."""
    from fastapi.testclient import TestClient
    client = TestClient(make_app(tmp_path))

    # Reaches the handler (device auth), so it is NOT the guard's 404.
    assert client.get("/api/albums", headers=_PROXIED).status_code == 401


def test_errors_report_public_but_admin_errors_hidden_when_proxied(tmp_path):
    """Only POST /api/errors/report is reachable through the proxy (device-facing):
    it passes the guard and is rejected by its own auth (401). The admin errors
    surface (GET/DELETE) stays hidden (404) from proxied requests."""
    from fastapi.testclient import TestClient
    client = TestClient(make_app(tmp_path))

    # Passes the guard (not 404); its own device auth rejects the tokenless call.
    assert client.post(
        "/api/errors/report", headers=_PROXIED, json=[]
    ).status_code == 401

    assert client.get("/api/errors", headers=_PROXIED).status_code == 404
    assert client.delete("/api/errors", headers=_PROXIED).status_code == 404
    assert client.delete("/api/errors/1", headers=_PROXIED).status_code == 404


# ---------------------------------------------------------------------------
# Criterion: launcher is runnable via python -m launcher (has __main__.py)
# ---------------------------------------------------------------------------

def test_launcher_main_module_exposes_serve():
    """launcher/__main__.py must exist and expose a callable 'serve' or 'main',
    enabling users to run `python -m launcher`."""
    import launcher.__main__ as main_mod

    entry = getattr(main_mod, "serve", None) or getattr(main_mod, "main", None)
    assert callable(entry), (
        "launcher/__main__.py must expose a callable 'serve' or 'main'"
    )
