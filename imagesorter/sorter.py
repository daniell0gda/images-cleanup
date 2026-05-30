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
    """Call transfer() using the configured collision policy."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    return transfer(src, dest_dir, copy, on_collision)


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
    except Exception as exc:
        logger.warning("EXIF read failed for %s, falling back to mtime: %s", path.name, exc)
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

    if not config.copy_instead_of_move:
        logger.warning(
            "Move mode active: files will be permanently moved and originals will not be retained. "
            "Set copy_instead_of_move=true to keep originals."
        )

    if config.confidence_threshold == 0.0:
        logger.warning(
            "confidence_threshold is 0.0: every detection will pass regardless of confidence, "
            "which may produce nonsensical sorting results."
        )

    # Validate destinations don't collide with source
    for group in config.tag_groups:
        dest = Path(group.destination).resolve()
        if dest == source:
            raise SystemExit(
                f"Error: destination '{group.destination}' resolves to the same path as source_folder '{config.source_folder}'"
            )
        # Ancestor conflict: source_folder is inside destination
        try:
            source.relative_to(dest)
            raise SystemExit(
                f"Error: destination '{group.destination}' is an ancestor of source_folder '{config.source_folder}' "
                f"— moving files there would corrupt the source tree"
            )
        except ValueError:
            pass

    # Warn when two groups share the same destination
    seen_dests: dict[Path, str] = {}
    for group in config.tag_groups:
        dest = Path(group.destination).resolve()
        if dest in seen_dests:
            logger.warning(
                "Duplicate destination '%s' shared by groups '%s' and '%s'",
                group.destination, seen_dests[dest], group.name,
            )
        else:
            seen_dests[dest] = group.name

    # Warn when month grouping is used without year grouping
    for group in config.tag_groups:
        if group.group_by_month and not group.group_by_year:
            logger.warning(
                "Group '%s' has group_by_month=True but group_by_year=False — "
                "month folders will merge images from different years",
                group.name,
            )

    # Warn when a group has no tags (will never match any image)
    for group in config.tag_groups:
        if not group.tags:
            logger.warning(
                "Group '%s' has an empty tags list and will never match any image",
                group.name,
            )

    if config.unclassified.enabled:
        uncl_dest = (Path(config.unclassified.destination) / config.unclassified.folder_name).resolve()
        if uncl_dest == source:
            full_path = Path(config.unclassified.destination) / config.unclassified.folder_name
            raise SystemExit(
                f"Error: unclassified.destination '{full_path}' "
                f"resolves to the same path as source_folder '{config.source_folder}'"
            )
        # Ancestor conflict: source_folder is inside unclassified destination
        try:
            source.relative_to(uncl_dest)
            full_path = Path(config.unclassified.destination) / config.unclassified.folder_name
            raise SystemExit(
                f"Error: unclassified.destination '{full_path}' is an ancestor of source_folder '{config.source_folder}'"
            )
        except ValueError:
            pass

    # Collect destination subtrees inside source to exclude from discovery
    excluded_subtrees: list[Path] = []
    for group in config.tag_groups:
        dest = Path(group.destination).resolve()
        try:
            dest.relative_to(source)
            excluded_subtrees.append(dest)
        except ValueError:
            pass
    if config.unclassified.enabled:
        uncl = (Path(config.unclassified.destination) / config.unclassified.folder_name).resolve()
        try:
            uncl.relative_to(source)
            excluded_subtrees.append(uncl)
        except ValueError:
            pass

    def _is_excluded(p: Path) -> bool:
        for subtree in excluded_subtrees:
            try:
                p.relative_to(subtree)
                return True
            except ValueError:
                pass
        return False

    pattern = "**/*" if config.recursive else "*"
    logger.info("Discovering images in %s ...", source)
    images: list[Path] = []
    seen: set[Path] = set()
    formats = set(config.include_formats)
    for p in source.glob(pattern):
        if p.suffix.lower() not in formats or p in seen:
            continue
        if excluded_subtrees and _is_excluded(p):
            continue
        # Skip symlinks whose resolved target is outside the source tree
        if p.is_symlink():
            try:
                resolved = p.resolve()
                source.resolve()
                resolved.relative_to(source.resolve())
            except ValueError:
                logger.warning("Skipping symlink %s: target is outside source_folder", p)
                continue
        seen.add(p)
        images.append(p)
        if len(images) % 500 == 0:
            logger.info("  ... %d images found so far", len(images))
    logger.info("Found %d images", len(images))

    logger.info("Loading YOLO model ...")
    model = YOLO("yolo11s.pt")
    logger.info("Model ready")

    batch_size = config.batch_size
    num_batches = max(1, (len(images) + batch_size - 1) // batch_size) if images else 0
    total = 0
    moved = 0
    skipped = 0
    collision_skipped = 0
    errors = 0
    any_group_matched = False
    _lock = threading.Lock()

    def process(img: Path, result) -> None:
        nonlocal moved, skipped, collision_skipped, errors, any_group_matched
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
                            collision_skipped += 1
                        else:
                            moved += 1
                else:
                    with _lock:
                        skipped += 1
                return

            with _lock:
                any_group_matched = True
            dt = _get_image_date(img)
            dest_dir = _build_dest_dir(group.destination, dt, group.group_by_year, group.group_by_month)
            transfer_result = _transfer_with_policy(img, dest_dir, config.copy_instead_of_move, config.on_collision)
            with _lock:
                if transfer_result is None:
                    collision_skipped += 1
                else:
                    moved += 1
        except Exception as exc:
            logger.error("Error processing %s: %s", img, exc)
            with _lock:
                errors += 1

    with ThreadPoolExecutor(max_workers=config.threads) as pool:
        for batch_idx in range(num_batches):
            start = batch_idx * batch_size
            end = min(start + batch_size, len(images))
            chunk = images[start:end]
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
                        if config.max_image_dimension == 0:
                            w, h = pil.size
                            if max(w, h) > 4096:
                                logger.warning(
                                    "Resizing is disabled (max_image_dimension=0) but %s is %dx%d — "
                                    "large images may exhaust available memory.",
                                    img_path.name, w, h,
                                )
                        if pil.mode != "RGB":
                            pil = pil.convert("RGB")
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
                                if pil.mode != "RGB":
                                    pil = pil.convert("RGB")
                                pil = _resize_if_needed(pil, config.max_image_dimension)
                                model_input = [pil.copy()]
                        except Exception:
                            model_input = [img]
                        res = model(model_input, conf=config.confidence_threshold, verbose=False)
                        futures.append(pool.submit(process, img, res[0]))
                    except Exception as exc:
                        logger.error("Skipping unreadable image %s: %s", img.name, exc)
                        with _lock:
                            errors += 1
            for future in futures:
                future.result()

    if images and config.tag_groups and not any_group_matched:
        logger.warning(
            "No configured tag groups matched any image — all images were sent to unclassified. "
            "Check your tag_groups configuration."
        )

    logger.info(
        "Run summary: total=%d moved=%d skipped=%d collision_skipped=%d errors=%d",
        total, moved, skipped, collision_skipped, errors,
    )
