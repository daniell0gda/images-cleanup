"""FastAPI server providing the SimilaritySearch web UI."""
from __future__ import annotations

import asyncio
import json
import logging
import socket
import sys
import threading
import webbrowser
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from .config import Config
from .similarity import _discover_images, _get_image_date, _hash_image


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


class ScanState:
    """Shared state between the scan thread and the SSE endpoint.

    Each connected SSE client gets its own queue (registered via
    `subscribe`/`unsubscribe`); `emit_group` and `mark_complete` fan out
    to every subscriber so multiple concurrent clients all see every event.
    """

    def __init__(self) -> None:
        self.groups: list[dict[str, Any]] = []
        self._subscribers: list[asyncio.Queue[dict[str, Any]]] = []
        self.loop: asyncio.AbstractEventLoop | None = None
        self.scan_complete = False
        # Paths trashed via the DELETE endpoint. Any later emit_group call
        # filters these out so the UI never sees a deleted image reappear.
        self.deleted_paths: set[str] = set()

    def mark_deleted(self, path: str) -> None:
        self.deleted_paths.add(path)

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._subscribers.append(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        if queue in self._subscribers:
            self._subscribers.remove(queue)

    def _broadcast(self, item: dict[str, Any]) -> None:
        if self.loop is None:
            return
        for queue in list(self._subscribers):
            asyncio.run_coroutine_threadsafe(queue.put(item), self.loop)

    def emit_group(self, group: dict[str, Any]) -> None:
        """Publish a group to every subscribed client (thread-safe).

        Paths previously marked as deleted are stripped from `paths` so a
        post-deletion merge cannot re-introduce an image the user has removed.
        """
        if self.deleted_paths:
            filtered_paths = [p for p in group.get("paths", []) if p not in self.deleted_paths]
            group = {**group, "paths": filtered_paths}
        self.groups.append(group)
        self._broadcast(group)

    def mark_complete(self) -> None:
        self.scan_complete = True
        self._broadcast({"event": "complete"})


def scan_images(config: Config, state: ScanState) -> None:
    """Scan source_folder for similarity groups, emitting each group as it is found.

    Pairs are pre-filtered by timestamp: two images are only compared when their
    timestamps differ by no more than config.similarity_time_window_minutes.
    """
    images = _discover_images(config)
    if not images:
        state.mark_complete()
        return

    # Hash + date every image once
    hashes: list = []
    dates: list[datetime] = []
    valid: list[Path] = []
    for img in images:
        try:
            hashes.append(_hash_image(img))
            dates.append(_get_image_date(img))
            valid.append(img)
        except Exception as exc:
            logger.error("Error hashing %s: %s", img, exc)

    n = len(valid)
    threshold = config.similarity_threshold
    window = timedelta(minutes=config.similarity_time_window_minutes)

    # Union-Find for transitive grouping; emit one event per group as it grows.
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    # Track group_id assigned to each current root, plus all roots that have
    # ever held a given group_id so we can invalidate them on merge.
    group_id_for_root: dict[int, int] = {}
    next_group_id = 0

    def emit(group_id: int, members: list[Path]) -> None:
        state.emit_group({
            "id": group_id,
            "paths": [str(p) for p in members],
        })

    def members_of(root: int) -> list[Path]:
        return [valid[i] for i in range(n) if find(i) == root]

    for i in range(n):
        for j in range(i + 1, n):
            # Time-window pre-filter
            if abs(dates[i] - dates[j]) > window:
                continue
            h1, h2 = hashes[i], hashes[j]
            max_bits = max(len(h1.hash) ** 2, 1)
            diff = h1 - h2
            similarity = 1.0 - diff / max_bits
            if similarity < threshold:
                continue

            ra, rb = find(i), find(j)
            if ra == rb:
                # Already in the same group — nothing to update.
                continue

            id_a = group_id_for_root.get(ra)
            id_b = group_id_for_root.get(rb)

            # Merge the two trees.
            parent[ra] = rb
            new_root = find(i)
            other_root = ra if new_root == rb else rb

            if id_a is None and id_b is None:
                # First time either of these roots forms a group → new id.
                group_id = next_group_id
                next_group_id += 1
            elif id_a is not None and id_b is None:
                group_id = id_a
            elif id_a is None and id_b is not None:
                group_id = id_b
            else:
                # Both roots were previously emitted: reuse one id, invalidate the other.
                # Keep the lower id for stability.
                keep_id, drop_id = (id_a, id_b) if id_a <= id_b else (id_b, id_a)
                group_id = keep_id
                # Emit an explicit "empty" update for the now-stale id so the
                # client can remove it; no two ids ever name overlapping paths.
                emit(drop_id, [])

            # The merged tree lives at new_root; the other root no longer
            # owns its previous group_id.
            group_id_for_root.pop(other_root, None)
            group_id_for_root[new_root] = group_id

            members = members_of(new_root)
            if len(members) >= 2:
                emit(group_id, members)

    state.mark_complete()


def run_scan_safely(config: Config, state: ScanState) -> None:
    """Run `scan_images` and guarantee `mark_complete()` is called even on crash.

    Without this wrapper, an unexpected exception in `scan_images` (e.g. raised
    outside the per-image try/except) would leave SSE clients blocked on
    `queue.get()` forever because `mark_complete()` would never run.
    """
    try:
        scan_images(config, state)
    except Exception:
        logger.exception("scan_images crashed unexpectedly")
    finally:
        if not state.scan_complete:
            state.mark_complete()


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
                # Replay anything already discovered before the client connected
                for g in state.groups:
                    yield {"event": "group", "data": json.dumps(g)}
                # If the scan already finished before this client connected, the
                # 'complete' marker may never have been broadcast to anyone.
                # Emit it directly so late clients don't block on queue.get.
                if state.scan_complete:
                    yield {"event": "complete", "data": "{}"}
                    return
                while True:
                    item = await queue.get()
                    if item.get("event") == "complete":
                        yield {"event": "complete", "data": "{}"}
                        break
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
