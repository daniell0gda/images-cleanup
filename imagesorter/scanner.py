"""Scanning state and background scan logic for the SimilaritySearch web UI."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from .config import Config
from .similarity import _discover_images, _get_image_date, _hash_image

logger = logging.getLogger(__name__)


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
        self.deleted_paths: set[str] = set()
        self.last_progress: dict[str, Any] | None = None
        self.last_comparing: dict[str, Any] | None = None

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
        if self.deleted_paths:
            filtered_paths = [p for p in group.get("paths", []) if p not in self.deleted_paths]
            group = {**group, "paths": filtered_paths}
        self.groups.append(group)
        self._broadcast(group)

    def emit_progress(self, scanned: int, total: int) -> None:
        item = {"event": "progress", "scanned": scanned, "total": total}
        self.last_progress = item
        self._broadcast(item)

    def emit_comparing(self, total: int) -> None:
        item = {"event": "comparing", "total": total}
        self.last_comparing = item
        self._broadcast(item)

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

    total = len(images)
    hashes: list = []
    dates: list[datetime] = []
    valid: list[Path] = []
    for i, img in enumerate(images):
        try:
            hashes.append(_hash_image(img))
            dates.append(_get_image_date(img))
            valid.append(img)
        except Exception as exc:
            logger.error("Error hashing %s: %s", img, exc)
        state.emit_progress(i + 1, total)

    n = len(valid)
    if n == 0:
        state.mark_complete()
        return
    state.emit_comparing(n)
    threshold = config.similarity_threshold
    window = timedelta(minutes=config.similarity_time_window_minutes)

    parent: dict[int, int] = {}

    def find(x: int) -> int:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    group_id_for_root: dict[int, int] = {}
    group_min_similarity: dict[int, float] = {}
    next_group_id = 0

    def emit(group_id: int, members: list[Path]) -> None:
        state.emit_group({
            "id": group_id,
            "paths": [str(p) for p in members],
            "similarity": group_min_similarity.get(group_id, 1.0),
        })

    def members_of(root: int) -> list[Path]:
        return [valid[i] for i in range(n) if find(i) == root]

    for i in range(n):
        for j in range(i + 1, n):
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
                continue

            id_a = group_id_for_root.get(ra)
            id_b = group_id_for_root.get(rb)

            parent[ra] = rb
            new_root = find(i)
            other_root = ra if new_root == rb else rb

            if id_a is None and id_b is None:
                group_id = next_group_id
                next_group_id += 1
                group_min_similarity[group_id] = similarity
            elif id_a is not None and id_b is None:
                group_id = id_a
                group_min_similarity[group_id] = min(group_min_similarity.get(group_id, 1.0), similarity)
            elif id_a is None and id_b is not None:
                group_id = id_b
                group_min_similarity[group_id] = min(group_min_similarity.get(group_id, 1.0), similarity)
            else:
                keep_id, drop_id = (id_a, id_b) if id_a <= id_b else (id_b, id_a)
                group_id = keep_id
                merged_similarity = min(
                    group_min_similarity.get(keep_id, 1.0),
                    group_min_similarity.get(drop_id, 1.0),
                    similarity,
                )
                group_min_similarity[group_id] = merged_similarity
                emit(drop_id, [])

            group_id_for_root.pop(other_root, None)
            group_id_for_root[new_root] = group_id

            members = members_of(new_root)
            if len(members) >= 2:
                emit(group_id, members)

    state.mark_complete()


def run_scan_safely(config: Config, state: ScanState) -> None:
    """Run `scan_images` and guarantee `mark_complete()` is called even on crash."""
    try:
        scan_images(config, state)
    except Exception:
        logger.exception("scan_images crashed unexpectedly")
    finally:
        if not state.scan_complete:
            state.mark_complete()
