"""FastAPI server providing the SimilaritySearch web UI."""
from __future__ import annotations

import asyncio
import json
import logging
import socket
import sys
import threading
import webbrowser
from pathlib import Path
from urllib.parse import unquote

from .config import Config
from .scanner import ScanState, run_scan_safely


PROJECT_ROOT = Path(__file__).resolve().parent.parent

logger = logging.getLogger(__name__)


def find_free_port(start: int = 8080) -> int:
    """Return the first port >= start that bind() accepts on localhost."""
    port = start
    while True:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(("127.0.0.1", port))
                return port
            except OSError:
                port += 1


def _is_path_within(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def create_app(config: Config, state: ScanState):
    """Build the FastAPI application."""
    from fastapi import Body, FastAPI, HTTPException
    from fastapi.responses import FileResponse
    from fastapi.staticfiles import StaticFiles
    from sse_starlette.sse import EventSourceResponse

    app = FastAPI()
    source = Path(config.source_folder).resolve()
    dist_dir = PROJECT_ROOT / "frontend" / "dist"

    @app.get("/api/images/{encoded_path:path}")
    async def get_image(encoded_path: str):
        decoded = unquote(encoded_path)
        target = Path(decoded)
        if not _is_path_within(target, source):
            raise HTTPException(status_code=403, detail="path outside source_folder")
        if not target.exists():
            raise HTTPException(status_code=404, detail="not found")
        return FileResponse(str(target))

    @app.delete("/api/images")
    async def delete_images(paths: list[str] = Body(...)):
        for p in paths:
            if not _is_path_within(Path(p), source):
                raise HTTPException(status_code=403, detail=f"path outside source_folder: {p}")
        import send2trash
        trashed: list[str] = []
        failed: list[dict[str, str]] = []
        for p in paths:
            try:
                send2trash.send2trash(p)
                trashed.append(p)
                state.mark_deleted(p)
            except Exception as exc:
                failed.append({"path": p, "error": str(exc)})
        return {"trashed": trashed, "failed": failed}

    @app.get("/api/stream")
    async def stream():
        async def event_generator():
            state.loop = asyncio.get_event_loop()
            queue = state.subscribe()
            try:
                for g in state.groups:
                    yield {"event": "group", "data": json.dumps(g)}
                if state.last_progress is not None:
                    yield {"event": "progress", "data": json.dumps(state.last_progress)}
                if state.last_comparing is not None:
                    yield {"event": "comparing", "data": json.dumps(state.last_comparing)}
                if state.scan_complete:
                    yield {"event": "complete", "data": "{}"}
                    return
                while True:
                    item = await queue.get()
                    if item.get("event") == "complete":
                        yield {"event": "complete", "data": "{}"}
                        break
                    if item.get("event") == "progress":
                        yield {"event": "progress", "data": json.dumps(item)}
                        continue
                    if item.get("event") == "comparing":
                        yield {"event": "comparing", "data": json.dumps(item)}
                        continue
                    yield {"event": "group", "data": json.dumps(item)}
            finally:
                state.unsubscribe(queue)
        return EventSourceResponse(event_generator())

    if dist_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(dist_dir), html=True), name="frontend")

    return app


def serve(config: Config) -> None:
    """Start the FastAPI server. Exits the process if the frontend is not built."""
    dist_dir = PROJECT_ROOT / "frontend" / "dist"
    if not dist_dir.is_dir():
        print(
            "Error: frontend/dist not found. Build the frontend first:\n"
            "  cd frontend && npm install && npm run build",
            file=sys.stderr,
        )
        sys.exit(1)

    port = find_free_port(8080)
    url = f"http://127.0.0.1:{port}"
    logger.info("Web UI listening on %s", url)

    state = ScanState()
    scan_thread = threading.Thread(
        target=run_scan_safely, args=(config, state), daemon=True
    )
    scan_thread.start()

    webbrowser.open(url)

    import uvicorn
    app = create_app(config, state)
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="info")
