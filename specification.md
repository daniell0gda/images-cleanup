# Image Sorter — Full Specification

## Overview

A Python CLI tool and library for organizing images either by detected object tags (using YOLOv8) or by visual similarity (using imagehash). Designed for performance, data safety, and transparency.

---

## Requirements

- **Python**: `>=3.11`
- **Dependencies**:
  - `ultralytics` — YOLOv8 inference (auto-downloads model weights)
  - `opencv-python` — image loading and processing
  - `imagehash` — perceptual hashing for similarity detection
  - `PyYAML` — config file parsing
  - `Pillow` — EXIF date extraction

---

## Modes

The tool operates in exactly one mode per run, set via `mode` in the config.

| Mode | Description |
|---|---|
| `GroupByTags` | Detects objects in images using YOLOv8 and moves/copies them to configured destinations based on tag rules |
| `SimilaritySearch` | Groups visually similar images into subfolders in-place within the source folder |

---

## Configuration

### Format

YAML file. Passed to the CLI via `--config path/to/config.yaml`. Individual keys can be overridden via CLI flags.

### CLI Interface

```bash
# Run with a config file
python -m imagesorter --config config.yaml

# Generate a commented template config
python -m imagesorter --generate-config > config.yaml

# Override a single key
python -m imagesorter --config config.yaml --threads 4
```

### Full Config Schema

```yaml
# ── General ────────────────────────────────────────────────────────────────────

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

# ── Logging ────────────────────────────────────────────────────────────────────

log_level: INFO              # DEBUG | INFO | WARNING | ERROR
log_file: ./imagesorter.log  # Path to log file. null = console only

# ── GroupByTags mode ───────────────────────────────────────────────────────────

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

# ── SimilaritySearch mode ──────────────────────────────────────────────────────

similarity_threshold: 0.96   # 0.0–1.0. Images at or above this similarity are grouped
```

---

## GroupByTags Mode — Behavior

### Tag Detection

- Uses **YOLOv8** via `ultralytics` with the default COCO-pretrained model.
- Tags must be **raw COCO class names** (e.g. `person`, `car`, `dog`). All 80 COCO labels are listed in the README.
- A rule matches an image only if **all** tags in the rule are detected in the image (AND logic).

### Rule Conflict Resolution

When an image matches multiple tag groups:

1. The rule with the **most matching tags** wins (most specific match).
2. Tie-break: the rule that appears **first** in the config wins.

### Destination Folder Structure

Based on each tag group's `group_by_year` and `group_by_month` settings:

| group_by_year | group_by_month | Path |
|---|---|---|
| false | false | `destination/image.jpg` |
| true | false | `destination/2024/image.jpg` |
| true | true | `destination/2024/05/image.jpg` |
| false | true | `destination/05/image.jpg` |

Date is sourced from EXIF `DateTimeOriginal`; falls back to file modification date.

### Source Folder Exclusion

The `source_folder` is never used as a destination. If a configured `destination` resolves to the same path as `source_folder`, the tool exits with an error before processing.

### Unclassified Images

If `unclassified.enabled: true`, images that match no tag group are moved/copied to:
`unclassified.destination / unclassified.folder_name / [year/month if configured]`

### File Collision Handling

If a file with the same name already exists at the destination:
- The incoming file is renamed with an incrementing suffix: `image.jpg` → `image_1.jpg` → `image_2.jpg`
- The collision is logged as a WARNING.

---

## SimilaritySearch Mode — Behavior

### Algorithm

1. All images in `source_folder` (respecting `recursive`) are hashed using `imagehash`.
2. Pairwise similarity is computed. Images are grouped using **transitive grouping**: if A~B and B~C, all three belong to the same group regardless of A~C similarity.
3. The "representative" image of each group is the image with the **earliest EXIF `DateTimeOriginal`**; falls back to file creation date.
4. A subfolder named after the representative image (without extension) is created **inside the folder where those images reside**.
5. All images in the group are **moved** into that subfolder (`copy_instead_of_move` is respected).

### Solo Images

Images with no similar counterparts above the threshold are **left in place**. No folder is created for them.

### Destination Folder

`destination` config key is **not used** in SimilaritySearch mode. Groups are created in-place within the source directory tree.

### File Collision Handling

Same as GroupByTags: rename with incrementing suffix, log as WARNING.

---

## Threading Model

- File I/O operations (move/copy) run in a `ThreadPoolExecutor` with `threads` workers.
- YOLO inference uses **batch processing** via `ultralytics` native batch API — images are collected and inferred in batches rather than one at a time.
- Default thread count: `os.cpu_count() - 1` (minimum 1).

---

## Logging

| Event | Level |
|---|---|
| Each file moved/copied | DEBUG |
| Skipped files (unsupported format, already at destination) | DEBUG |
| File rename due to collision | WARNING |
| Errors (unreadable file, EXIF failure, IO error) | ERROR |
| Run summary (total processed / moved / skipped / errors) | INFO |
| Mode, config path, source folder at startup | INFO |

- **Console**: always active, respects `log_level`
- **File**: written to `log_file` path if set, respects `log_level`
- Log format: `[YYYY-MM-DD HH:MM:SS] [LEVEL] message`

---

## Data Safety

- **Nothing is deleted.** The tool only moves or copies. Move = copy + delete original, where original is deleted only after successful copy is confirmed.
- **Source folder is never a destination** for GroupByTags mode (validated at startup).
- **Collisions never overwrite** — always renamed with suffix.
- **Errors are non-fatal** — a single unreadable file is logged and skipped; processing continues.
- All operations are logged so the user has a full audit trail.

---

## README — COCO Labels Reference

The README must include the full list of 80 COCO class names that can be used as `tags`:

```
person, bicycle, car, motorcycle, airplane, bus, train, truck, boat,
traffic light, fire hydrant, stop sign, parking meter, bench, bird, cat,
dog, horse, sheep, cow, elephant, bear, zebra, giraffe, backpack, umbrella,
handbag, tie, suitcase, frisbee, skis, snowboard, sports ball, kite,
baseball bat, baseball glove, skateboard, surfboard, tennis racket, bottle,
wine glass, cup, fork, knife, spoon, bowl, banana, apple, sandwich, orange,
broccoli, carrot, hot dog, pizza, donut, cake, chair, couch, potted plant,
bed, dining table, toilet, tv, laptop, mouse, remote, keyboard, cell phone,
microwave, oven, toaster, sink, refrigerator, book, clock, vase, scissors,
teddy bear, hair drier, toothbrush
```

Tags are **case-insensitive** and matched against COCO class names exactly (after lowercasing).
