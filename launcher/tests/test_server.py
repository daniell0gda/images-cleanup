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
# Criterion: subprocess receives LAUNCHER_URL env var
# ---------------------------------------------------------------------------

def test_subprocess_receives_launcher_url_env(tmp_path, monkeypatch):
    """The spawned subprocess gets LAUNCHER_URL=http://<host>:7000 in its environment."""
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

    assert "LAUNCHER_URL" in captured_env, f"LAUNCHER_URL not in env: {captured_env}"
    assert captured_env["LAUNCHER_URL"].endswith(":7000")


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
