"""GroupByTags orchestration."""
from __future__ import annotations

import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from ultralytics import YOLO

from .config import Config, TagGroup
from .file_ops import transfer

logger = logging.getLogger(__name__)


def _get_image_date(path: Path):
    """Return datetime from EXIF DateTimeOriginal, fall back to file mtime."""
    from datetime import datetime
    try:
        from PIL import Image
        from PIL.ExifTags import TAGS
        img = Image.open(path)
        exif_data = img._getexif()
        if exif_data:
            for tag_id, value in exif_data.items():
                if TAGS.get(tag_id) == "DateTimeOriginal":
                    return datetime.strptime(value, "%Y:%m:%d %H:%M:%S")
    except Exception:
        pass
    return datetime.fromtimestamp(path.stat().st_mtime)


def _build_dest_dir(base: str, dt, group_by_year: bool, group_by_month: bool) -> Path:
    parts = [base]
    if group_by_year:
        parts.append(f"{dt.year:04d}")
    if group_by_month:
        parts.append(f"{dt.month:02d}")
    return Path(*parts)


def _select_group(detected_labels: set[str], tag_groups: list[TagGroup]) -> TagGroup | None:
    """Select the best matching tag group (AND logic, most-specific wins, earlier tie-break)."""
    matches: list[tuple[int, int, TagGroup]] = []
    for idx, group in enumerate(tag_groups):
        group_tags = {t.lower() for t in group.tags}
        if group_tags and group_tags.issubset(detected_labels):
            matches.append((len(group_tags), -idx, group))
    if not matches:
        return None
    # sort descending by specificity (most tags first), then by original order (lowest idx first)
    matches.sort(key=lambda x: (x[0], x[1]), reverse=True)
    return matches[0][2]


def run(config: Config) -> None:
    """Run GroupByTags mode."""
    source = Path(config.source_folder).resolve()

    # Validate destinations don't collide with source
    for group in config.tag_groups:
        dest = Path(group.destination).resolve()
        if dest == source:
            raise SystemExit(
                f"Error: destination '{group.destination}' resolves to the same path as source_folder '{config.source_folder}'"
            )

    if config.unclassified.enabled:
        uncl_dest = Path(config.unclassified.destination).resolve()
        if uncl_dest == source:
            raise SystemExit(
                f"Error: unclassified.destination '{config.unclassified.destination}' "
                f"resolves to the same path as source_folder '{config.source_folder}'"
            )

    # Collect images
    pattern = "**/*" if config.recursive else "*"
    images: list[Path] = []
    for fmt in config.include_formats:
        images.extend(p for p in source.glob(pattern) if p.suffix.lower() == fmt)

    if not images:
        logger.info("No images found in %s", source)
        logger.info("Run summary: total=0 moved=0 skipped=0 errors=0")
        return

    model = YOLO()  # default model

    total = len(images)
    moved = 0
    skipped = 0
    errors = 0
    _lock = threading.Lock()

    logger.info("Mode: GroupByTags | Source: %s | Images: %d", source, total)

    # Chunked YOLO inference
    batch_size = config.batch_size
    num_batches = (total + batch_size - 1) // batch_size
    results: list = []
    for batch_idx in range(num_batches):
        start = batch_idx * batch_size
        end = min(start + batch_size, total)
        chunk = images[start:end]
        logger.info(
            "Processing batch %d/%d (images %d–%d of %d)",
            batch_idx + 1, num_batches, start + 1, end, total,
        )
        chunk_results = model(chunk, conf=config.confidence_threshold, verbose=False)
        results.extend(chunk_results)

    def process(img: Path, result) -> None:
        nonlocal moved, skipped, errors
        try:
            cls_tensor = result.boxes.cls if result.boxes is not None else []
            detected = {model.names[int(cls)].lower() for cls in cls_tensor}
            logger.debug("%s: detected %s", img.name, sorted(detected))
            group = _select_group(detected, config.tag_groups)

            if group is None:
                if config.unclassified.enabled:
                    dt = _get_image_date(img)
                    base = str(Path(config.unclassified.destination) / config.unclassified.folder_name)
                    dest_dir = _build_dest_dir(
                        base,
                        dt,
                        config.unclassified.group_by_year,
                        config.unclassified.group_by_month,
                    )
                    transfer(img, dest_dir, config.copy_instead_of_move)
                    with _lock:
                        moved += 1
                else:
                    with _lock:
                        skipped += 1
                return

            dt = _get_image_date(img)
            dest_dir = _build_dest_dir(group.destination, dt, group.group_by_year, group.group_by_month)
            transfer(img, dest_dir, config.copy_instead_of_move)
            with _lock:
                moved += 1
        except Exception as exc:
            logger.error("Error processing %s: %s", img, exc)
            with _lock:
                errors += 1

    with ThreadPoolExecutor(max_workers=config.threads) as pool:
        futures = [pool.submit(process, img, result) for img, result in zip(images, results)]
        for future in futures:
            future.result()

    logger.info(
        "Run summary: total=%d moved=%d skipped=%d errors=%d",
        total, moved, skipped, errors,
    )
