"""Launcher FastAPI server — management UI for image-sorter."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from datetime import timedelta
from pathlib import Path

from fastapi import Request
from pydantic import BaseModel


class _JobRequest(BaseModel):
    user: str
    mode: str


def _configs_dir() -> Path:
    return Path(os.environ.get("CONFIGS_DIR", "./configs"))


def _inbox_base() -> Path:
    return Path(os.environ.get("INBOX_BASE", "./data/inbox"))


def _sync_db_path() -> Path:
    return Path(os.environ.get("SYNC_DB", "./data/sync.db"))


# Largest single chunk body the server will append, in bytes. Bounds per-request
# memory and rejects pathological uploads; overridable via the env for tuning.
_DEFAULT_MAX_CHUNK_BYTES = 16 * 1024 * 1024


def _max_chunk_bytes() -> int:
    return int(os.environ.get("MAX_CHUNK_BYTES", _DEFAULT_MAX_CHUNK_BYTES))


def _launcher_public_port() -> str:
    """Host port the launcher UI is reachable on (may differ from the internal
    7000 when remapped in Docker)."""
    return os.environ.get("LAUNCHER_PUBLIC_PORT", "7000")


def _sorter_public_port() -> int:
    """Host port the sorter UI is reachable on (may differ from the internal
    8080 when remapped in Docker)."""
    return int(os.environ.get("SORTER_PUBLIC_PORT", "8080"))


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


def create_app(
    state: _JobState | None = None,
    dist_dir: Path | None = None,
    sync_detect_tags=None,
    sync_scheduler=None,
):
    from fastapi import FastAPI, HTTPException
    from fastapi.staticfiles import StaticFiles

    if state is None:
        state = _JobState()

    app = FastAPI()

    @app.get("/api/config")
    async def get_config():
        return {"sorter_port": _sorter_public_port()}

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

        env = {**os.environ, "LAUNCHER_PUBLIC_PORT": _launcher_public_port()}
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

    _register_sync_routes(app, sync_detect_tags, sync_scheduler)

    if dist_dir is not None and dist_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(dist_dir), html=True), name="frontend")

    return app


class _DeviceRequest(BaseModel):
    device_id: str
    name: str


class _Identity(BaseModel):
    name: str
    created_on: str
    size: int


class _SessionRequest(BaseModel):
    profile_id: str


def _load_sync_config(profile_id: str):
    """Load the GroupByTags Config backing a sync profile_id."""
    from imagesorter import config as config_mod
    user = profile_id[: -len("_groupby")] if profile_id.endswith("_groupby") else profile_id
    config_file = _configs_dir() / f"config_{user}_groupby.yaml"
    return config_mod.load(str(config_file))


def _default_detect_tags():
    """Build the production tag detector backed by a warm YOLO model."""
    from ultralytics import YOLO
    model = YOLO("yolo11s.pt")

    def detect(path: Path) -> set:
        result = model([str(path)], verbose=False)[0]
        cls_tensor = result.boxes.cls if result.boxes is not None else []
        return {model.names[int(c)].lower() for c in cls_tensor}

    return detect


class _DeferredDetect:
    """Lazily builds the YOLO-backed detector on first use, so creating the app
    (and the test suite) never downloads or loads the model."""

    def __init__(self):
        self._detect = None

    def __call__(self, path: Path) -> set:
        if self._detect is None:
            self._detect = _default_detect_tags()
        return self._detect(path)


_SYNC_TTL = timedelta(hours=24)


def _register_sync_routes(app, detect_tags=None, scheduler=None) -> None:
    """Mount the android-phone-sync endpoints onto the launcher app.

    On startup the lane reconciles complete-but-unprocessed sessions and runs a
    janitor pass; ``scheduler`` (``(interval_seconds, fn) -> None``) registers a
    recurring janitor so leftovers are reaped on a timer in production.
    """
    from fastapi import Header, HTTPException
    from . import sync as sync_mod

    store = sync_mod.SyncStore(_sync_db_path())
    sessions = sync_mod.SessionManager(_inbox_base())
    lane = sync_mod.SyncLane(
        store, sessions, _load_sync_config,
        detect_tags if detect_tags is not None else _DeferredDetect(),
    )
    app.state.sync_lane = lane

    lane.startup_reconcile(_SYNC_TTL)
    lane.janitor(_SYNC_TTL)
    if scheduler is not None:
        scheduler(_SYNC_TTL.total_seconds(), lambda: lane.janitor(_SYNC_TTL))

    def _profile_ids() -> set[str]:
        return {p["profile_id"] for p in sync_mod.list_profiles(_configs_dir())}

    def _require_device(authorization: str | None):
        token = ""
        if authorization and authorization.lower().startswith("bearer "):
            token = authorization[7:].strip()
        device = store.device_for_token(token)
        if device is None:
            raise HTTPException(status_code=401, detail="Invalid or missing token")
        return device

    def _require_owned_session(device, session_id: str) -> None:
        """Reject access to a session the authed device does not own.

        Ownership is read from the durable session registry (not the on-disk
        folder) so the check survives session cleanup. An unknown session is a
        404; a known session owned by another device is a 403, and neither
        performs any write to the session.
        """
        owner = store.session_owner(session_id)
        if owner is None:
            raise HTTPException(status_code=404, detail="Unknown session")
        if owner != device["device_id"]:
            raise HTTPException(status_code=403, detail="Session belongs to another device")

    @app.get("/api/sync/devices")
    async def sync_list_devices():
        return store.list_devices()

    @app.post("/api/sync/devices")
    async def sync_register(req: _DeviceRequest):
        try:
            code = store.register_device(req.device_id, req.name)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid device_id")
        return {"status": "pending", "pairing_code": code}

    @app.get("/api/sync/devices/{device_id}/status")
    async def sync_device_status(device_id: str):
        device = store.get_device(device_id)
        if device is None:
            raise HTTPException(status_code=404, detail="Unknown device")
        result = {"status": device["status"]}
        if device["status"] == "trusted":
            result["token"] = device["token"]
        elif device["status"] == "pending":
            # Surface the code so a phone that lost local state (e.g. cleared app
            # data) can re-display it without re-registering — re-registering would
            # reset an already-known device back to pending.
            result["pairing_code"] = device["pairing_code"]
        return result

    @app.post("/api/sync/devices/{device_id}/approve")
    async def sync_device_approve(device_id: str):
        token = store.approve_device(device_id)
        if token is None:
            raise HTTPException(status_code=404, detail="Unknown device")
        return {"status": "trusted", "token": token}

    @app.post("/api/sync/devices/{device_id}/revoke")
    async def sync_device_revoke(device_id: str):
        store.revoke_device(device_id)
        return {"status": "revoked"}

    @app.get("/api/sync/profiles")
    async def sync_profiles(authorization: str | None = Header(default=None)):
        _require_device(authorization)
        return sync_mod.list_profiles(_configs_dir())

    @app.post("/api/sync/reconcile")
    async def sync_reconcile(
        items: list[_Identity],
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        uploaded = sessions.uploaded_offsets(device["device_id"])
        results = []
        for i in items:
            synced = store.is_synced(i.name, i.created_on, i.size)
            row = {
                "name": i.name,
                "created_on": i.created_on,
                "size": i.size,
                "already_synced": synced,
                "uploaded_offset": 0,
                "resume_session_id": None,
                "resume_file_id": None,
            }
            if not synced:
                resume = uploaded.get((i.name, i.created_on, i.size))
                if resume is not None:
                    session_id, file_id, offset = resume
                    row["resume_session_id"] = session_id
                    row["resume_file_id"] = file_id
                    row["uploaded_offset"] = offset
            results.append(row)
        return {"results": results}

    @app.post("/api/sync/sessions")
    async def sync_open_session(
        req: _SessionRequest,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        if req.profile_id not in _profile_ids():
            raise HTTPException(status_code=404, detail="Unknown profile")
        config = _load_sync_config(req.profile_id)
        if not config.unclassified.enabled:
            raise HTTPException(
                status_code=400,
                detail="Sync profiles require unclassified.enabled=true",
            )
        session_id = sessions.create_session(device["device_id"], req.profile_id)
        store.record_session(session_id, device["device_id"], req.profile_id)
        return {"session_id": session_id}

    @app.get("/api/sync/sessions/{session_id}/files/{file_id}")
    async def sync_file_offset(
        session_id: str,
        file_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        return {"offset": sessions.file_offset(session_id, file_id)}

    @app.post("/api/sync/sessions/{session_id}/files")
    async def sync_upload_chunk(
        session_id: str,
        request: Request,
        authorization: str | None = Header(default=None),
        file_id: str = Header(alias="File-Id"),
        file_name: str = Header(alias="File-Name"),
        file_created_on: str = Header(alias="File-Created-On"),
        file_size: int = Header(alias="File-Size"),
        file_mime_type: str = Header(alias="File-Mime-Type"),
        upload_offset: int = Header(alias="Upload-Offset", default=0),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        meta = sync_mod.FileMeta(file_name, file_created_on, file_size, file_mime_type)
        # Reject an oversize chunk BEFORE appending any bytes. Check the declared
        # Content-Length first so we can refuse without buffering the body, then
        # guard the actual length in case the header lied.
        max_chunk = _max_chunk_bytes()
        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > max_chunk:
            raise HTTPException(status_code=413, detail="Chunk too large")
        data = await request.body()
        if len(data) > max_chunk:
            raise HTTPException(status_code=413, detail="Chunk too large")
        try:
            new_offset = sessions.write_chunk(session_id, file_id, meta, upload_offset, data)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail="Unknown session")
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid file_id")
        return {"offset": new_offset, "length": file_size}

    @app.post("/api/sync/sessions/{session_id}/complete")
    async def sync_complete_session(
        session_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        # Idempotent: a session already processed (its folder cleaned up by the
        # first placement) reports complete on retry instead of 404.
        if store.has_outcomes(session_id):
            return {"status": "complete"}
        try:
            sessions.complete(session_id)
        except FileNotFoundError:
            raise HTTPException(status_code=404, detail="Unknown session")
        lane.process_session(session_id)
        return {"status": "complete"}

    @app.get("/api/sync/sessions/{session_id}/outcomes")
    async def sync_outcomes(
        session_id: str,
        authorization: str | None = Header(default=None),
    ):
        device = _require_device(authorization)
        _require_owned_session(device, session_id)
        return {"outcomes": lane.read_outcomes(session_id)}

    @app.post("/api/sync/verify")
    async def sync_verify(
        items: list[_Identity],
        authorization: str | None = Header(default=None),
    ):
        # Accepted trust boundary (wontfix): verify exposes per-identity presence
        # to any trusted device. This is not narrowed because identity presence is
        # already inferable through global dedup — is_synced and reconcile's
        # already_synced are global across all devices (PK is (name, created_on,
        # size)), so a trusted device can already learn presence via reconcile.
        # Hiding it here would not change that same trust boundary.
        _require_device(authorization)
        results = []
        for i in items:
            stored = store.stored_path_for(i.name, i.created_on, i.size)
            present = bool(stored) and Path(stored).exists()
            results.append({
                "name": i.name,
                "created_on": i.created_on,
                "size": i.size,
                "present": present,
            })
        return {"results": results}


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


def _recurring_timer(interval: float, fn) -> None:
    """Run ``fn`` every ``interval`` seconds on a daemon timer that reschedules
    itself after each tick."""
    import threading

    def tick() -> None:
        try:
            fn()
        finally:
            t = threading.Timer(interval, tick)
            t.daemon = True
            t.start()

    t = threading.Timer(interval, tick)
    t.daemon = True
    t.start()


def serve() -> None:
    """Start the launcher server on port 7000."""
    import atexit
    import uvicorn

    _check_port(7000)
    state = _JobState()
    atexit.register(shutdown_handler, state)
    dist_dir = Path(__file__).parent / "dist"
    app = create_app(state, dist_dir=dist_dir, sync_scheduler=_recurring_timer)
    uvicorn.run(app, host="0.0.0.0", port=7000, log_level="info")
