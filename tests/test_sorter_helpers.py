"""Unit tests for sorter helper functions."""
import pytest
from pathlib import Path
from PIL import Image


def make_jpeg(path: Path) -> Path:
    """Create a minimal JPEG image at path."""
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (10, 10), color=(128, 128, 128))
    img.save(str(path), "JPEG")
    return path


# ── _resize_if_needed ─────────────────────────────────────────────────────────

def test_resize_wide_image_longest_side_becomes_max_dim():
    """Wide image (3000×2000) with max_dim=1920 → longest side becomes 1920."""
    from imagesorter.sorter import _resize_if_needed
    img = Image.new("RGB", (3000, 2000))
    result = _resize_if_needed(img, 1920)
    w, h = result.size
    assert w == 1920
    assert h == int(2000 * 1920 / 3000)


def test_resize_small_image_returned_unchanged():
    """Small image (100×80) with max_dim=1920 → returned unchanged."""
    from imagesorter.sorter import _resize_if_needed
    img = Image.new("RGB", (100, 80))
    result = _resize_if_needed(img, 1920)
    assert result.size == (100, 80)


def test_resize_tall_portrait_longest_side_becomes_max_dim():
    """Tall portrait (2000×3000) with max_dim=1920 → height becomes 1920, width scaled."""
    from imagesorter.sorter import _resize_if_needed
    img = Image.new("RGB", (2000, 3000))
    result = _resize_if_needed(img, 1920)
    w, h = result.size
    assert h == 1920
    assert w == int(2000 * 1920 / 3000)


def test_resize_zero_max_dim_returns_image_unchanged():
    """max_dim=0 (disabled) must return the image without raising."""
    from imagesorter.sorter import _resize_if_needed
    img = Image.new("RGB", (3000, 2000))
    result = _resize_if_needed(img, 0)
    assert result.size == (3000, 2000)


# ── _get_image_date ───────────────────────────────────────────────────────────

def test_mtime_fallback_when_no_exif(tmp_path):
    """_get_image_date should return mtime when EXIF is absent."""
    from imagesorter.sorter import _get_image_date
    from datetime import datetime
    import os

    img_path = make_jpeg(tmp_path / "photo.jpg")
    # Set a known mtime
    known_ts = datetime(2019, 6, 1, 0, 0, 0).timestamp()
    os.utime(str(img_path), (known_ts, known_ts))

    dt = _get_image_date(img_path)
    assert dt.year == 2019
    assert dt.month == 6


def test_get_image_date_logs_warning_on_exif_failure(tmp_path, caplog):
    """_get_image_date logs a WARNING message when EXIF read fails."""
    import logging
    from imagesorter.sorter import _get_image_date

    # A non-image file causes EXIF read to fail
    bad_file = tmp_path / "fake.jpg"
    bad_file.write_bytes(b"not an image")

    with caplog.at_level(logging.WARNING, logger="imagesorter.sorter"):
        _get_image_date(bad_file)

    warning_msgs = [r.message for r in caplog.records if r.levelno == logging.WARNING]
    assert any("EXIF" in m or "mtime" in m or "fallback" in m for m in warning_msgs), (
        f"Expected a WARNING log about EXIF fallback, got: {warning_msgs}"
    )


# ── _build_dest_dir ───────────────────────────────────────────────────────────

def test_build_dest_dir_flat():
    """Both flags False → only base path."""
    from imagesorter.sorter import _build_dest_dir
    from datetime import datetime

    dt = datetime(2023, 7, 15)
    result = _build_dest_dir("/out", dt, group_by_year=False, group_by_month=False)
    assert result == Path("/out")


def test_build_dest_dir_year_only():
    """Year only → base/YYYY."""
    from imagesorter.sorter import _build_dest_dir
    from datetime import datetime

    dt = datetime(2023, 7, 15)
    result = _build_dest_dir("/out", dt, group_by_year=True, group_by_month=False)
    assert result == Path("/out/2023")


def test_build_dest_dir_year_and_month():
    """Both flags True → base/YYYY/MM."""
    from imagesorter.sorter import _build_dest_dir
    from datetime import datetime

    dt = datetime(2023, 7, 15)
    result = _build_dest_dir("/out", dt, group_by_year=True, group_by_month=True)
    assert result == Path("/out/2023/07")


def test_build_dest_dir_month_only():
    """Month only → base/MM."""
    from imagesorter.sorter import _build_dest_dir
    from datetime import datetime

    dt = datetime(2023, 7, 15)
    result = _build_dest_dir("/out", dt, group_by_year=False, group_by_month=True)
    assert result == Path("/out/07")


# ── _select_group ─────────────────────────────────────────────────────────────

COCO_NAMES = {
    0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus",
    7: "truck", 14: "bird", 15: "cat", 16: "dog",
}


def test_select_group_single_match():
    """Single matching group is returned."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="Family", tags=["person"], destination="/out",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"person"}, groups)
    assert result is not None
    assert result.name == "Family"


def test_select_group_no_match():
    """No group matches → None returned."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="Vehicles", tags=["car", "truck"], destination="/out",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"person"}, groups)
    assert result is None


def test_select_group_most_specific_wins():
    """Most-specific group (most tags) wins when multiple match."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="Generic", tags=["person"], destination="/generic",
                 group_by_year=False, group_by_month=False),
        TagGroup(name="Specific", tags=["person", "dog"], destination="/specific",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"person", "dog"}, groups)
    assert result is not None
    assert result.name == "Specific"


def test_select_group_earlier_wins_on_tie():
    """When same tag count matches, first in list wins."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="First", tags=["person"], destination="/first",
                 group_by_year=False, group_by_month=False),
        TagGroup(name="Second", tags=["dog"], destination="/second",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"person", "dog"}, groups)
    assert result is not None
    assert result.name == "First"


def test_select_group_and_logic_partial_no_match():
    """Partial match (only one of two required tags) → no match."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="Vehicles", tags=["car", "truck"], destination="/out",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"car"}, groups)
    assert result is None


def test_select_group_case_insensitive():
    """Tag matching is case-insensitive."""
    from imagesorter.sorter import _select_group
    from imagesorter.config import TagGroup

    groups = [
        TagGroup(name="Family", tags=["Person"], destination="/out",
                 group_by_year=False, group_by_month=False),
    ]
    result = _select_group({"person"}, groups)
    assert result is not None
    assert result.name == "Family"


# ── E1-AC3: config.load() validates max_image_dimension ──────────────────────

def test_config_load_raises_for_negative_max_image_dimension(tmp_path):
    """config.load() raises ValueError for negative max_image_dimension."""
    import yaml
    from imagesorter import config as cfg

    conf_path = tmp_path / "config.yaml"
    conf_path.write_text(yaml.dump({
        "mode": "GroupByTags",
        "source_folder": "./photos",
        "max_image_dimension": -1,
    }))

    with pytest.raises(ValueError, match="max_image_dimension"):
        cfg.load(str(conf_path))


def test_config_load_accepts_zero_max_image_dimension(tmp_path):
    """config.load() accepts max_image_dimension=0 (disabled) without error."""
    import yaml
    from imagesorter import config as cfg

    conf_path = tmp_path / "config.yaml"
    conf_path.write_text(yaml.dump({
        "mode": "GroupByTags",
        "source_folder": "./photos",
        "max_image_dimension": 0,
    }))

    loaded = cfg.load(str(conf_path))
    assert loaded.max_image_dimension == 0
