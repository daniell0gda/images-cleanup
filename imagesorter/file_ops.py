"""File move/copy with collision handling."""
from __future__ import annotations

import logging
import os
import shutil
from pathlib import Path

logger = logging.getLogger(__name__)


def _collision_free_path(dest: Path) -> Path:
    """Return dest if it doesn't exist, else dest with an incrementing suffix."""
    if not dest.exists():
        return dest
    stem = dest.stem
    suffix = dest.suffix
    parent = dest.parent
    counter = 1
    while True:
        candidate = parent / f"{stem}_{counter}{suffix}"
        if not candidate.exists():
            logger.warning("Collision: %s already exists, renaming to %s", dest.name, candidate.name)
            return candidate
        counter += 1


def transfer(src: Path, dest_dir: Path, copy: bool) -> Path:
    """Move or copy src into dest_dir, handling name collisions.

    Returns the final destination path.
    """
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = _collision_free_path(dest_dir / src.name)

    if copy:
        shutil.copy2(str(src), str(dest))
        logger.debug("Copied %s -> %s", src, dest)
    else:
        # Safe move: copy first, verify, then remove source
        shutil.copy2(str(src), str(dest))
        if dest.exists():
            os.remove(str(src))
            logger.debug("Moved %s -> %s", src, dest)
        else:
            raise IOError(f"Destination {dest} not confirmed after copy")

    return dest
