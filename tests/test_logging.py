"""Tests for logging format and error resilience."""
import logging
import re
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
from PIL import Image


LOG_PATTERN = re.compile(r"^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] \[(\w+)\] .+")


def make_jpeg(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (10, 10), color=(100, 100, 100))
    img.save(str(path), "JPEG")
    return path


# ── Criterion 15: log format ──────────────────────────────────────────────────

# ── Criterion: log_level suppression ─────────────────────────────────────────

def test_warning_level_suppresses_debug_and_info(tmp_path):
    """When log_level=WARNING, DEBUG and INFO messages must not appear in output."""
    from imagesorter.logging_setup import setup

    log_file = tmp_path / "test.log"
    setup("WARNING", str(log_file))

    logger = logging.getLogger("imagesorter.test_suppression")
    logger.debug("should be hidden debug")
    logger.info("should be hidden info")
    logger.warning("should appear warning")

    content = log_file.read_text(encoding="utf-8")
    assert "should be hidden debug" not in content
    assert "should be hidden info" not in content
    assert "should appear warning" in content


def test_debug_level_shows_all_levels(tmp_path):
    """When log_level=DEBUG, all log levels should appear in output."""
    from imagesorter.logging_setup import setup

    log_file = tmp_path / "test.log"
    setup("DEBUG", str(log_file))

    logger = logging.getLogger("imagesorter.test_debug_all")
    logger.debug("debug message")
    logger.info("info message")
    logger.warning("warning message")

    content = log_file.read_text(encoding="utf-8")
    assert "debug message" in content
    assert "info message" in content
    assert "warning message" in content


def test_log_format_matches_spec(tmp_path):
    """Log file lines must match [YYYY-MM-DD HH:MM:SS] [LEVEL] message."""
    from imagesorter.logging_setup import setup

    log_file = tmp_path / "test.log"
    setup("INFO", str(log_file))

    logger = logging.getLogger("imagesorter.test_format")
    logger.info("Test message")
    logger.warning("Warning message")

    lines = log_file.read_text(encoding="utf-8").strip().splitlines()
    assert lines, "Log file is empty"
    for line in lines:
        assert LOG_PATTERN.match(line), f"Line doesn't match format: {line!r}"


def test_debug_events_logged_at_debug(tmp_path):
    from imagesorter.logging_setup import setup
    from imagesorter.file_ops import transfer

    log_file = tmp_path / "test.log"
    setup("DEBUG", str(log_file))

    src_file = tmp_path / "src" / "photo.jpg"
    make_jpeg(src_file)
    dest_dir = tmp_path / "dest"
    transfer(src_file, dest_dir, copy=False)

    lines = log_file.read_text(encoding="utf-8")
    assert "DEBUG" in lines
    assert "photo.jpg" in lines


def test_collision_logged_at_warning(tmp_path):
    from imagesorter.logging_setup import setup
    from imagesorter.file_ops import transfer

    log_file = tmp_path / "test.log"
    setup("WARNING", str(log_file))

    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")

    src_file = tmp_path / "src" / "photo.jpg"
    make_jpeg(src_file)
    transfer(src_file, dest_dir, copy=False)

    lines = log_file.read_text(encoding="utf-8")
    assert "WARNING" in lines


# ── Criterion 16: error resilience ────────────────────────────────────────────

COCO_NAMES = {
    0: "person", 2: "car", 16: "dog",
}


def _make_config(tmp_path, tag_groups=None):
    from imagesorter.config import Config, TagGroup, Unclassified
    if tag_groups is None:
        tag_groups = [
            TagGroup(name="All", tags=["person"], destination=str(tmp_path / "dest"),
                     group_by_year=False, group_by_month=False),
        ]
    return Config(
        mode="GroupByTags",
        source_folder=str(tmp_path / "src"),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="ERROR",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
    )


def test_unreadable_image_logged_and_skipped(tmp_path, caplog):
    """A single unreadable image is logged at ERROR, processing continues."""
    from imagesorter.sorter import run
    import torch

    src = tmp_path / "src"
    src.mkdir()
    good = make_jpeg(src / "good.jpg")
    bad = src / "bad.jpg"
    bad.write_bytes(b"not an image")

    dest = tmp_path / "dest"
    config = _make_config(tmp_path)

    # good.jpg → detects person; bad.jpg → causes error in processing
    good_result = MagicMock()
    good_result.boxes.cls = torch.tensor([0.0])  # person

    bad_result = MagicMock()
    bad_result.boxes.cls = torch.tensor([0.0])   # also person, but file_ops will fail

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES

    call_count = [0]
    def side_effect(images, **kwargs):
        return [good_result, bad_result]
    mock_model.side_effect = side_effect

    # Make transfer fail for bad.jpg
    from imagesorter import file_ops
    original_transfer = file_ops.transfer
    def patched_transfer(src_path, dest_dir, copy):
        if src_path.name == "bad.jpg":
            raise IOError("Cannot read bad.jpg")
        return original_transfer(src_path, dest_dir, copy)

    with caplog.at_level(logging.ERROR, logger="imagesorter.sorter"), \
         patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter.transfer", side_effect=patched_transfer):
        run(config)

    # good.jpg should still be processed
    assert (dest / "good.jpg").exists()

    # bad.jpg error should be logged
    error_records = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert error_records, "Expected at least one ERROR log"


def test_processing_continues_after_single_error(tmp_path, caplog):
    """If one image errors, others are still processed."""
    from imagesorter.sorter import run
    import torch

    src = tmp_path / "src"
    src.mkdir()
    img1 = make_jpeg(src / "img1.jpg")
    img2 = make_jpeg(src / "img2.jpg")
    img3 = make_jpeg(src / "img3.jpg")

    dest = tmp_path / "dest"
    config = _make_config(tmp_path)

    person_result = MagicMock()
    person_result.boxes.cls = torch.tensor([0.0])

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.side_effect = lambda images, **kw: [person_result] * len(images)

    from imagesorter import file_ops
    original_transfer = file_ops.transfer
    call_order = []
    def patched_transfer(src_path, dest_dir, copy):
        call_order.append(src_path.name)
        if src_path.name == "img2.jpg":
            raise IOError("Simulated error")
        return original_transfer(src_path, dest_dir, copy)

    with caplog.at_level(logging.ERROR, logger="imagesorter.sorter"), \
         patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter.transfer", side_effect=patched_transfer):
        run(config)

    # img1 and img3 should be moved despite img2 failing
    assert (dest / "img1.jpg").exists()
    assert (dest / "img3.jpg").exists()
    # img2 stays (it errored)
    assert img2.exists()
