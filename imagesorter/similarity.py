"""SimilaritySearch orchestration."""
from __future__ import annotations

import logging
from datetime import datetime
from pathlib import Path

from .config import Config
from .file_ops import transfer

logger = logging.getLogger(__name__)


def _get_image_date(path: Path) -> datetime:
    """Return datetime from EXIF DateTimeOriginal, fall back to file creation time."""
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
    return datetime.fromtimestamp(path.stat().st_ctime)


def _hash_image(path: Path):
    import imagehash
    from PIL import Image
    return imagehash.phash(Image.open(path))


def _transitive_groups(pairs: list[tuple[int, int]]) -> list[list[int]]:
    """Union-Find transitive grouping."""
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    for a, b in pairs:
        union(a, b)

    groups: dict[int, list[int]] = {}
    for node in set(parent):
        root = find(node)
        groups.setdefault(root, []).append(node)
    return list(groups.values())


def run(config: Config) -> None:
    source = Path(config.source_folder).resolve()

    pattern = "**/*" if config.recursive else "*"
    images: list[Path] = []
    for fmt in config.include_formats:
        images.extend(p for p in source.glob(pattern) if p.suffix.lower() == fmt)
    images = list(dict.fromkeys(images))

    if not images:
        logger.info("No images found in %s", source)
        logger.info("Run summary: total=0 moved=0 skipped=0 errors=0")
        return

    total = len(images)
    moved = 0
    skipped = 0
    errors = 0

    logger.info("Mode: SimilaritySearch | Source: %s | Images: %d", source, total)

    # Hash all images
    hashes: list = []
    valid_images: list[Path] = []
    for img in images:
        try:
            hashes.append(_hash_image(img))
            valid_images.append(img)
        except Exception as exc:
            logger.error("Error hashing %s: %s", img, exc)
            errors += 1

    n = len(valid_images)
    threshold = config.similarity_threshold

    # Find similar pairs
    pairs: list[tuple[int, int]] = []
    for i in range(n):
        for j in range(i + 1, n):
            h1, h2 = hashes[i], hashes[j]
            max_bits = max(len(h1.hash) ** 2, 1)
            diff = h1 - h2
            similarity = 1.0 - diff / max_bits
            if similarity >= threshold:
                pairs.append((i, j))

    if not pairs:
        logger.info("No similar image groups found.")
        logger.info(
            "Run summary: total=%d moved=%d skipped=%d errors=%d",
            total, moved, skipped, errors,
        )
        return

    # Build transitive groups
    groups = _transitive_groups(pairs)

    # Nodes involved in any group
    grouped_indices: set[int] = set()
    for g in groups:
        if len(g) > 1:
            grouped_indices.update(g)

    for group in groups:
        if len(group) < 2:
            # Solo — leave in place
            skipped += 1
            continue

        group_paths = [valid_images[i] for i in group]
        # Representative: earliest date
        dates = []
        for p in group_paths:
            dates.append((_get_image_date(p), p))
        dates.sort(key=lambda x: x[0])
        representative = dates[0][1]

        # Subfolder named after representative (without extension), inside its parent
        subfolder = representative.parent / representative.stem
        for p in group_paths:
            try:
                result = transfer(p, subfolder, config.copy_instead_of_move, on_collision=config.on_collision)
                if result is None:
                    skipped += 1
                else:
                    moved += 1
            except Exception as exc:
                logger.error("Error moving %s: %s", p, exc)
                errors += 1

    logger.info(
        "Run summary: total=%d moved=%d skipped=%d errors=%d",
        total, moved, skipped, errors,
    )
