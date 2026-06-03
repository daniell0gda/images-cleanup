"""Scanning state and background scan logic for the SimilaritySearch web UI."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from ultralytics import YOLO

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
        self.images: list[str] = []
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

    def emit_image(self, path: str) -> None:
        """Record a single unclassified-image path and broadcast it as an 'image' event."""
        self.images.append(path)
        self._broadcast({"event": "image", "path": path})

    def remove_image(self, path: str) -> None:
        """Remove a path from the images replay list (after a successful move-to-group)."""
        if path in self.images:
            self.images.remove(path)

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


def scan_images_groupby(config: Config, state: ScanState) -> None:
    """Scan source_folder for GroupByTags web-UI mode.

    - Pre-existing images in the unclassified folder are emitted first.
    - Classified images (matched by YOLO) are moved immediately and silently.
    - Unclassified images are moved to the unclassified folder and emitted as
      SSE 'image' events so the web UI can show them.
    - Emits 'progress' events; never emits 'comparing'.
    """
    from PIL import Image as _PILImage
    from .sorter import _select_group, _get_image_date as _sorter_get_image_date, _build_dest_dir, _transfer_with_policy

    # Emit pre-existing images already in the unclassified folder
    unclassified_folder = Path(config.unclassified.destination) / config.unclassified.folder_name
    if unclassified_folder.is_dir():
        formats = set(config.include_formats)
        for p in sorted(unclassified_folder.rglob("*")):
            if p.suffix.lower() in formats and p.is_file():
                state.emit_image(str(p))

    images = _discover_images(config)
    total = len(images)

    if not images:
        state.mark_complete()
        return

    logger.info("GroupByTags web scan: loading YOLO model ...")
    model = YOLO("yolo11s.pt")
    logger.info("GroupByTags web scan: model ready, processing %d images", total)

    batch_size = config.batch_size
    processed = 0

    for batch_start in range(0, total, batch_size):
        chunk = images[batch_start: batch_start + batch_size]

        try:
            chunk_imgs = []
            for img_path in chunk:
                with _PILImage.open(img_path) as pil:
                    if pil.mode != "RGB":
                        pil = pil.convert("RGB")
                    chunk_imgs.append(pil.copy())
            results = model(chunk_imgs, conf=config.confidence_threshold, verbose=False)
        except Exception:
            # Per-image fallback
            results = []
            for img_path in chunk:
                try:
                    with _PILImage.open(img_path) as pil:
                        if pil.mode != "RGB":
                            pil = pil.convert("RGB")
                        res = model([pil.copy()], conf=config.confidence_threshold, verbose=False)
                        results.append(res[0])
                except Exception as exc:
                    logger.error("Skipping unreadable image %s: %s", img_path.name, exc)
                    results.append(None)

        for img_path, result in zip(chunk, results):
            processed += 1
            try:
                if result is None:
                    pass
                else:
                    cls_tensor = result.boxes.cls if result.boxes is not None else []
                    detected = {model.names[int(cls)].lower() for cls in cls_tensor}
                    group = _select_group(detected, config.tag_groups)

                    if group is not None:
                        # Classified → move to group destination, no SSE
                        dt = _sorter_get_image_date(img_path)
                        dest_dir = _build_dest_dir(group.destination, dt, group.group_by_year, group.group_by_month)
                        _transfer_with_policy(img_path, dest_dir, config.copy_instead_of_move, config.on_collision)
                    else:
                        # Unclassified → move to unclassified folder and emit SSE image event
                        dt = _sorter_get_image_date(img_path)
                        base = str(Path(config.unclassified.destination) / config.unclassified.folder_name)
                        dest_dir = _build_dest_dir(
                            base, dt,
                            config.unclassified.group_by_year,
                            config.unclassified.group_by_month,
                        )
                        transfer_result = _transfer_with_policy(img_path, dest_dir, config.copy_instead_of_move, config.on_collision)
                        if transfer_result is not None:
                            state.emit_image(str(transfer_result))
                        else:
                            # Collision-skipped: emit the would-be destination path
                            state.emit_image(str(dest_dir / img_path.name))
            except Exception as exc:
                logger.error("Error processing %s: %s", img_path, exc)

            state.emit_progress(processed, total)

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
