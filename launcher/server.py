"""Launcher FastAPI server — management UI for image-sorter."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

from pydantic import BaseModel


class _JobRequest(BaseModel):
    user: str
    mode: str


def _configs_dir() -> Path:
    return Path(os.environ.get("CONFIGS_DIR", "./configs"))


_CONFIG_RE = re.compile(r"^config_(.+?)_(groupby|similarity)\.yaml$")


def _scan_users(configs_dir: Path) -> list[dict]:
    users: dict[str, list[str]] = {}
    if configs_dir.is_dir():
        for f in configs_dir.iterdir():
            m = _CONFIG_RE.match(f.name)
            if m:
                user, mode = m.group(1), m.group(2)
                users.setdefault(user, []).append(mode)
    return [{"user": u, "modes": sorted(modes)} for u, modes in sorted(users.items())]


class _JobState:
    proc: subprocess.Popen | None = None
    user: str | None = None
    mode: str | None = None

    def is_running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def clear_if_done(self) -> None:
        if self.proc is not None and self.proc.poll() is not None:
            self.proc = None
            self.user = None
            self.mode = None


def shutdown_handler(state: _JobState) -> None:
    """Terminate the child subprocess if it's still running."""
    if state.proc is not None and state.proc.poll() is None:
        state.proc.terminate()
        try:
            state.proc.wait(timeout=5)
        except Exception:
            pass


def create_app(state: _JobState | None = None, dist_dir: Path | None = None):
    from fastapi import FastAPI, HTTPException
    from fastapi.staticfiles import StaticFiles

    if state is None:
        state = _JobState()

    app = FastAPI()

    @app.get("/api/users")
    async def get_users():
        return _scan_users(_configs_dir())

    @app.get("/api/status")
    async def get_status():
        state.clear_if_done()
        if not state.is_running():
            return {"status": "idle"}
        return {"status": "running", "user": state.user, "mode": state.mode}

    @app.post("/api/jobs")
    async def post_jobs(req: _JobRequest):
        state.clear_if_done()
        if state.is_running():
            raise HTTPException(status_code=409, detail="A job is already running")

        configs_dir = _configs_dir()
        config_file = configs_dir / f"config_{req.user}_{req.mode}.yaml"
        if not config_file.exists():
            raise HTTPException(status_code=404, detail=f"Config not found: {config_file}")

        env = {**os.environ, "LAUNCHER_URL": "http://127.0.0.1:7000"}
        state.proc = subprocess.Popen(
            [sys.executable, "-m", "imagesorter", "--config", str(config_file)],
            env=env,
        )
        state.user = req.user
        state.mode = req.mode

        return {"status": "running", "user": state.user, "mode": state.mode}

    @app.delete("/api/jobs")
    async def delete_jobs():
        shutdown_handler(state)
        state.proc = None
        state.user = None
        state.mode = None
        return {"status": "idle"}

    if dist_dir is not None and dist_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(dist_dir), html=True), name="frontend")

    return app


def _check_port(port: int = 7000) -> None:
    """Raise SystemExit if port is already in use."""
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind(("0.0.0.0", port))
        except OSError:
            print(
                f"Error: port {port} is already in use. "
                "Stop the process using that port and try again.",
                file=sys.stderr,
            )
            sys.exit(1)


def serve() -> None:
    """Start the launcher server on port 7000."""
    import atexit
    import uvicorn

    _check_port(7000)
    state = _JobState()
    atexit.register(shutdown_handler, state)
    dist_dir = Path(__file__).parent / "dist"
    app = create_app(state, dist_dir=dist_dir)
    uvicorn.run(app, host="0.0.0.0", port=7000, log_level="info")
