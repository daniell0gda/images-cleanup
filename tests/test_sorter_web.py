"""Tests for GroupByTags web-UI dispatch in sorter.run() and scan_images_groupby."""
from __future__ import annotations

import asyncio
import sys
import threading
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from PIL import Image as _PILImage


def make_jpeg(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img = _PILImage.new("RGB", (10, 10), color=(100, 100, 100))
    img.save(str(path), "JPEG")
    return path


def _make_config(tmp_path, *, web_ui=True, unclassified_enabled=True, copy=True):
    from imagesorter.config import Config, TagGroup, Unclassified
    return Config(
        mode="GroupByTags",
        source_folder=str(tmp_path / "src"),
        recursive=False,
        copy_instead_of_move=copy,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[
            TagGroup(name="Animals", tags=["dog"], destination=str(tmp_path / "animals"),
                     group_by_year=False, group_by_month=False),
        ],
        unclassified=Unclassified(
            enabled=unclassified_enabled,
            folder_name="others",
            destination=str(tmp_path / "sorted"),
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
        web_ui=web_ui,
    )


# ── Criterion 1: web_ui=true calls web.serve ─────────────────────────────────

def test_sorter_run_calls_web_serve_when_web_ui_true(tmp_path):
    """When config.web_ui=True, sorter.run() calls web.serve(config) instead of the CLI batch loop."""
    from imagesorter import sorter

    config = _make_config(tmp_path, web_ui=True)
    (tmp_path / "src").mkdir(parents=True)

    serve_calls = []

    with patch("imagesorter.sorter._serve_web", side_effect=lambda cfg: serve_calls.append(cfg)):
        sorter.run(config)

    assert serve_calls == [config], (
        "sorter.run() must call web.serve(config) when config.web_ui=True"
    )


# ── Criterion 2: web_ui=true but unclassified.enabled=false → exit non-zero ──

def test_sorter_run_exits_when_web_ui_and_unclassified_disabled(tmp_path, capsys):
    """When web_ui=True but unclassified.enabled=False, sorter.run() must exit with a non-zero code."""
    from imagesorter import sorter

    config = _make_config(tmp_path, web_ui=True, unclassified_enabled=False)
    (tmp_path / "src").mkdir(parents=True)

    with pytest.raises(SystemExit) as exc_info:
        sorter.run(config)

    assert exc_info.value.code != 0, (
        "sorter.run() must exit with a non-zero code when web_ui=True and unclassified.enabled=False"
    )
    captured = capsys.readouterr()
    output = captured.out + captured.err
    assert output.strip(), "sorter.run() must print an error message"


# ── Criteria 3 & 4: scan_images_groupby classified/unclassified handling ──────

def _make_fake_model(files: list[Path], file_to_labels: dict[str, list[str]]):
    """Build a fake YOLO model where each call returns labels by file order."""
    call_order: list[int] = []

    def model_call(imgs, conf=0.5, verbose=False):
        results = []
        for _ in imgs:
            idx = len(call_order)
            call_order.append(idx)
            fname = files[idx % len(files)].name
            labels = file_to_labels.get(fname, [])
            result = MagicMock()
            result.boxes = MagicMock()
            result.boxes.cls = list(range(len(labels)))
            model_instance.names = {i: labels[i] for i in range(len(labels))}
            results.append(result)
        return results

    model_instance = MagicMock()
    model_instance.side_effect = model_call
    model_instance.names = {}
    return model_instance


def test_classified_image_is_moved_silently_without_sse_event(tmp_path):
    """An image that matches a tag group is moved (copied) to its destination without emitting an SSE event."""
    from imagesorter import scanner

    src = tmp_path / "src"
    img = make_jpeg(src / "dog.jpg")
    config = _make_config(tmp_path, web_ui=True, copy=True)

    state = scanner.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop
    queue = state.subscribe()

    file_to_labels = {"dog.jpg": ["dog"]}

    with patch("imagesorter.scanner.YOLO") as mock_yolo_cls:
        mock_yolo_cls.return_value = _make_fake_model([img], file_to_labels)
        scanner.scan_images_groupby(config, state)

    loop.close()

    # No image SSE event emitted (dog.jpg matched the "Animals" group)
    assert state.images == [], (
        "Classified images must not be emitted as SSE image events"
    )
    # The file should be copied to the Animals destination
    dest = tmp_path / "animals" / "dog.jpg"
    assert dest.exists(), "Classified image must be copied to its tag-group destination"


def test_unclassified_image_is_moved_and_emits_sse_image_event(tmp_path):
    """An image that matches no tag group is moved to the unclassified folder and emits an SSE 'image' event."""
    from imagesorter import scanner

    src = tmp_path / "src"
    img = make_jpeg(src / "landscape.jpg")
    config = _make_config(tmp_path, web_ui=True, copy=True)

    state = scanner.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop
    queue = state.subscribe()

    file_to_labels = {"landscape.jpg": []}  # no labels → unclassified

    with patch("imagesorter.scanner.YOLO") as mock_yolo_cls:
        mock_yolo_cls.return_value = _make_fake_model([img], file_to_labels)
        scanner.scan_images_groupby(config, state)

    loop.close()

    # SSE image event must have been emitted
    assert len(state.images) == 1, (
        "Unclassified image must emit an SSE 'image' event"
    )
    # The path in the event must point to the unclassified destination
    unclassified_dest = tmp_path / "sorted" / "others" / "landscape.jpg"
    assert unclassified_dest.exists() or str(unclassified_dest) in state.images, (
        "Unclassified image must be moved to the unclassified folder"
    )


# ── Criterion 5: progress events emitted, no comparing event ─────────────────

def test_scan_images_groupby_emits_progress_and_no_comparing(tmp_path):
    """scan_images_groupby emits progress events and never emits a 'comparing' event."""
    from imagesorter import scanner

    src = tmp_path / "src"
    imgs = [make_jpeg(src / f"img{i}.jpg") for i in range(3)]
    config = _make_config(tmp_path, web_ui=True, copy=True)

    state = scanner.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop
    queue = state.subscribe()

    file_to_labels = {f"img{i}.jpg": [] for i in range(3)}  # all unclassified

    with patch("imagesorter.scanner.YOLO") as mock_yolo_cls:
        mock_yolo_cls.return_value = _make_fake_model(imgs, file_to_labels)

        done = threading.Event()

        def run_scan():
            scanner.scan_images_groupby(config, state)
            done.set()

        threading.Thread(target=run_scan, daemon=True).start()

        async def drain():
            events = []
            while True:
                try:
                    item = await asyncio.wait_for(queue.get(), timeout=3.0)
                    events.append(item)
                    if item.get("event") == "complete":
                        break
                except asyncio.TimeoutError:
                    break
            return events

        events = loop.run_until_complete(drain())

    loop.close()

    event_types = [e.get("event") for e in events]
    assert "progress" in event_types, "scan_images_groupby must emit progress events"
    assert "comparing" not in event_types, "scan_images_groupby must not emit a comparing event"
    assert "complete" in event_types, "scan_images_groupby must emit a complete event"

    # Progress events must cover all 3 images
    progress_events = [e for e in events if e.get("event") == "progress"]
    assert len(progress_events) == 3
    assert [e["scanned"] for e in progress_events] == [1, 2, 3]
    assert all(e["total"] == 3 for e in progress_events)


# ── Criterion: pre-existing unclassified images emitted before source scan ────

def test_scan_images_groupby_emits_preexisting_unclassified_images(tmp_path):
    """scan_images_groupby must emit paths already in the unclassified folder before scanning source."""
    from imagesorter import scanner
    from unittest.mock import patch

    # Create the unclassified folder with one pre-existing image
    unclassified_dir = tmp_path / "sorted" / "others"
    preexisting = make_jpeg(unclassified_dir / "preexisting.jpg")

    # Source folder is empty so no new images will be scanned
    src = tmp_path / "src"
    src.mkdir()

    config = _make_config(tmp_path, web_ui=True, copy=True)
    state = scanner.ScanState()

    with patch("imagesorter.scanner.YOLO") as mock_yolo_cls:
        mock_yolo_cls.return_value = _make_fake_model([], {})
        scanner.scan_images_groupby(config, state)

    assert str(preexisting) in state.images, (
        f"Pre-existing unclassified image must be emitted; got state.images={state.images}"
    )
