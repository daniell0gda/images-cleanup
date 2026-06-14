"""FastAPI server providing the SimilaritySearch and GroupByTags web UI."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import sys
import threading
import webbrowser
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote

from pydantic import BaseModel

from .config import Config
from .file_ops import transfer
from .scanner import ScanState, run_scan_safely
from .sorter import _get_image_date as _get_file_date


class MoveToGroupRequest(BaseModel):
    paths: list[str]
    group_name: str


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
        allowed = _is_path_within(target, source)
        if not allowed and config.mode == "GroupByTags":
            unclassified_dest = Path(config.unclassified.destination).resolve()
            allowed = _is_path_within(target, unclassified_dest)
        if not allowed:
            raise HTTPException(status_code=403, detail="path outside source_folder")
        if not target.exists():
            raise HTTPException(status_code=404, detail="not found")
        return FileResponse(str(target))

    @app.delete("/api/images")
    async def delete_images(paths: list[str] = Body(...)):
        for p in paths:
            allowed = _is_path_within(Path(p), source)
            if not allowed and config.mode == "GroupByTags":
                unclassified_dest = Path(config.unclassified.destination).resolve()
                allowed = _is_path_within(Path(p), unclassified_dest)
            if not allowed:
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

    @app.get("/api/config")
    async def get_config():
        result: dict = {"mode": config.mode, "similarity_threshold": config.similarity_threshold}
        if config.mode == "GroupByTags":
            result["tag_groups"] = [
                {"name": g.name, "destination": g.destination}
                for g in config.tag_groups
            ]
        launcher_url = os.environ.get("LAUNCHER_URL")
        if launcher_url:
            result["launcher_url"] = launcher_url
        return result

    @app.post("/api/move-to-group")
    async def move_to_group(body: MoveToGroupRequest):
        group = next((g for g in config.tag_groups if g.name == body.group_name), None)
        if group is None:
            raise HTTPException(status_code=404, detail="group not found")
        unclassified_folder = Path(config.unclassified.destination) / config.unclassified.folder_name
        for path_str in body.paths:
            if not _is_path_within(Path(path_str), unclassified_folder):
                raise HTTPException(status_code=400, detail=f"path outside unclassified folder: {path_str}")
        results = []
        for path_str in body.paths:
            src = Path(path_str)
            try:
                dt = _get_file_date(src)
            except Exception:
                dt = datetime.now()
            dst_dir = Path(group.destination)
            if group.group_by_year:
                dst_dir = dst_dir / f"{dt.year:04d}"
            if group.group_by_month:
                dst_dir = dst_dir / f"{dt.month:02d}"
            dst_dir.mkdir(parents=True, exist_ok=True)
            result = transfer(src, dst_dir, False, config.on_collision)
            ok = result is not None
            if ok:
                state.remove_image(path_str)
            results.append({"path": path_str, "ok": ok})
        return {"results": results}

    @app.get("/api/stream")
    async def stream():
        async def event_generator():
            state.loop = asyncio.get_event_loop()
            queue = state.subscribe()
            try:
                # Replay existing data
                if config.mode == "GroupByTags":
                    for path in list(state.images):
                        yield {"event": "image", "data": json.dumps({"path": path})}
                else:
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
                    if item.get("event") == "image":
                        yield {"event": "image", "data": json.dumps({"path": item["path"]})}
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

    host = os.environ.get("SORTER_HOST", "127.0.0.1")
    port_env = os.environ.get("SORTER_PORT")
    port = int(port_env) if port_env else find_free_port(8080)
    url = f"http://127.0.0.1:{port}"
    logger.info("Web UI listening on %s (bind %s:%d)", url, host, port)

    state = ScanState()
    if config.mode == "GroupByTags":
        from .scanner import scan_images_groupby

        def _run_groupby_safely(cfg: Config, st: ScanState) -> None:
            try:
                scan_images_groupby(cfg, st)
            except Exception:
                logger.exception("scan_images_groupby crashed unexpectedly")
            finally:
                if not st.scan_complete:
                    st.mark_complete()

        scan_thread = threading.Thread(
            target=_run_groupby_safely, args=(config, state), daemon=True
        )
    else:
        scan_thread = threading.Thread(
            target=run_scan_safely, args=(config, state), daemon=True
        )
    scan_thread.start()

    if os.environ.get("IMAGESORTER_NO_BROWSER") != "1":
        webbrowser.open(url)

    import uvicorn
    app = create_app(config, state)
    uvicorn.run(app, host=host, port=port, log_level="info")
