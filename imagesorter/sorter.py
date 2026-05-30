"""GroupByTags orchestration."""
from __future__ import annotations

import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image as _PILImage
from ultralytics import YOLO

from .config import Config, TagGroup
from .file_ops import transfer

logger = logging.getLogger(__name__)


def _resize_if_needed(img: "_PILImage.Image", max_dim: int) -> "_PILImage.Image":
    if max_dim <= 0:
        return img
    w, h = img.size
    longest = max(w, h)
    if longest <= max_dim:
        return img
    scale = max_dim / longest
    return img.resize((int(w * scale), int(h * scale)), _PILImage.LANCZOS)


def _transfer_with_policy(src: Path, dest_dir: Path, copy: bool, on_collision: str) -> Path | None:
    """Call transfer() using the configured collision policy.

    Returns None (and skips the transfer) when on_collision='skip' and dest already exists.
    Otherwise delegates to transfer() with its 3-arg signature so callers that mock transfer
    at the sorter namespace see the standard (src, dest_dir, copy) call.
    """
    dest_dir.mkdir(parents=True, exist_ok=True)
    if on_collision == "skip" and (dest_dir / src.name).exists():
        logger.warning("Collision: %s already exists, skipping %s", src.name, src.name)
        return None
    return transfer(src, dest_dir, copy)


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


def _iter_batches(source: Path, pattern: str, include_formats: list[str], batch_size: int):
    """Yield lists of Path objects in batch_size chunks, deduplicating via seen set."""
    seen: set[Path] = set()
    formats = set(include_formats)
    batch: list[Path] = []
    for p in source.glob(pattern):
        if p.suffix.lower() not in formats:
            continue
        if p in seen:
            continue
        seen.add(p)
        batch.append(p)
        if len(batch) == batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


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

    pattern = "**/*" if config.recursive else "*"
    logger.info("Discovering images in %s ...", source)

    logger.info("Loading YOLO model ...")
    model = YOLO()  # default model
    logger.info("Model ready")

    total = 0
    moved = 0
    skipped = 0
    errors = 0
    _lock = threading.Lock()

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
                    transfer_result = _transfer_with_policy(img, dest_dir, config.copy_instead_of_move, config.on_collision)
                    with _lock:
                        if transfer_result is None:
                            skipped += 1
                        else:
                            moved += 1
                else:
                    with _lock:
                        skipped += 1
                return

            dt = _get_image_date(img)
            dest_dir = _build_dest_dir(group.destination, dt, group.group_by_year, group.group_by_month)
            transfer_result = _transfer_with_policy(img, dest_dir, config.copy_instead_of_move, config.on_collision)
            with _lock:
                if transfer_result is None:
                    skipped += 1
                else:
                    moved += 1
        except Exception as exc:
            logger.error("Error processing %s: %s", img, exc)
            with _lock:
                errors += 1

    with ThreadPoolExecutor(max_workers=config.threads) as pool:
        for batch_idx, chunk in enumerate(_iter_batches(source, pattern, config.include_formats, config.batch_size)):
            batch_start = total + 1
            batch_end = total + len(chunk)
            total += len(chunk)
            logger.info(
                "Processing batch %d (images %d–%d)",
                batch_idx + 1, batch_start, batch_end,
            )
            try:
                chunk_imgs = []
                for img_path in chunk:
                    with _PILImage.open(img_path) as pil:
                        pil = _resize_if_needed(pil, config.max_image_dimension)
                        chunk_imgs.append(pil.copy())
                chunk_results = model(chunk_imgs, conf=config.confidence_threshold, verbose=False)
                futures = [pool.submit(process, img, result) for img, result in zip(chunk, chunk_results)]
            except Exception:
                futures = []
                for img in chunk:
                    try:
                        try:
                            with _PILImage.open(img) as pil:
                                pil = _resize_if_needed(pil, config.max_image_dimension)
                                model_input = [pil.copy()]
                        except Exception:
                            model_input = [img]
                        res = model(model_input, conf=config.confidence_threshold, verbose=False)
                        futures.append(pool.submit(process, img, res[0]))
                    except Exception as exc:
                        logger.error("Skipping unreadable image %s: %s", img.name, exc)
                        errors += 1
            for future in futures:
                future.result()

    logger.info(
        "Run summary: total=%d moved=%d skipped=%d errors=%d",
        total, moved, skipped, errors,
    )
