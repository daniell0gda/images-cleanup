"""Config loading, validation, and defaults."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any

import yaml


TEMPLATE = """\
# --- General ------------------------------------------------------------------

mode: GroupByTags            # "GroupByTags" | "SimilaritySearch"

source_folder: ./photos      # Directory to search for images
recursive: true              # Search subfolders recursively

copy_instead_of_move: false  # If true, copy files; if false, move them

include_formats:             # Image file extensions to process (case-insensitive)
  - .jpg
  - .jpeg
  - .png
  - .webp

threads: null                # Number of worker threads. null = cpu_count - 1

# --- Logging ------------------------------------------------------------------

log_level: INFO              # DEBUG | INFO | WARNING | ERROR
log_file: ./imagesorter.log  # Path to log file. null = console only

# --- GroupByTags mode ---------------------------------------------------------

tag_groups:
  - name: Family             # Human-readable label (used in logs)
    tags:                    # ALL tags must be detected for a match (AND logic)
      - person
    destination: ./sorted/family
    group_by_year: true
    group_by_month: true

  - name: Vehicles
    tags:
      - car
      - truck
    destination: ./sorted/vehicles
    group_by_year: false
    group_by_month: false

unclassified:
  enabled: true              # Move/copy unclassified images to the unclassified folder
  folder_name: others        # Subfolder name inside destination root
  destination: ./sorted      # Root for the unclassified folder
  group_by_year: false
  group_by_month: false

# --- SimilaritySearch mode ----------------------------------------------------

similarity_threshold: 0.96   # 0.0-1.0. Images at or above this similarity are grouped
"""


@dataclass
class TagGroup:
    name: str
    tags: list[str]
    destination: str
    group_by_year: bool = False
    group_by_month: bool = False


@dataclass
class Unclassified:
    enabled: bool
    folder_name: str
    destination: str
    group_by_year: bool = False
    group_by_month: bool = False


@dataclass
class Config:
    mode: str
    source_folder: str
    recursive: bool
    copy_instead_of_move: bool
    include_formats: list[str]
    threads: int
    log_level: str
    log_file: str | None
    tag_groups: list[TagGroup]
    unclassified: Unclassified
    similarity_threshold: float


def _default_threads() -> int:
    return max((os.cpu_count() or 2) - 1, 1)


def load(path: str, overrides: dict[str, Any] | None = None) -> Config:
    """Load config from YAML file, apply CLI overrides, fill defaults."""
    with open(path, encoding="utf-8") as fh:
        raw: dict[str, Any] = yaml.safe_load(fh) or {}

    if overrides:
        raw.update({k: v for k, v in overrides.items() if v is not None})

    threads_raw = raw.get("threads")
    threads = _default_threads() if threads_raw is None else int(threads_raw)

    unclassified_raw = raw.get("unclassified", {})
    unclassified = Unclassified(
        enabled=unclassified_raw.get("enabled", False),
        folder_name=unclassified_raw.get("folder_name", "others"),
        destination=unclassified_raw.get("destination", "./sorted"),
        group_by_year=unclassified_raw.get("group_by_year", False),
        group_by_month=unclassified_raw.get("group_by_month", False),
    )

    tag_groups_raw = raw.get("tag_groups") or []
    tag_groups = [
        TagGroup(
            name=tg.get("name", ""),
            tags=[t.lower() for t in tg.get("tags", [])],
            destination=tg.get("destination", ""),
            group_by_year=tg.get("group_by_year", False),
            group_by_month=tg.get("group_by_month", False),
        )
        for tg in tag_groups_raw
    ]

    return Config(
        mode=raw.get("mode", "GroupByTags"),
        source_folder=raw.get("source_folder", "./photos"),
        recursive=raw.get("recursive", True),
        copy_instead_of_move=raw.get("copy_instead_of_move", False),
        include_formats=[f.lower() for f in raw.get("include_formats", [".jpg", ".jpeg", ".png", ".webp"])],
        threads=threads,
        log_level=raw.get("log_level", "INFO"),
        log_file=raw.get("log_file"),
        tag_groups=tag_groups,
        unclassified=unclassified,
        similarity_threshold=float(raw.get("similarity_threshold", 0.96)),
    )
