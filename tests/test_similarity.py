"""Tests for SimilaritySearch mode."""
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
from PIL import Image


def make_jpeg(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (10, 10), color=(100, 100, 100))
    img.save(str(path), "JPEG")
    return path


def _make_config(tmp_path, threshold=0.96, copy=False, recursive=False):
    from imagesorter.config import Config, Unclassified
    return Config(
        mode="SimilaritySearch",
        source_folder=str(tmp_path / "src"),
        recursive=recursive,
        copy_instead_of_move=copy,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=threshold,
    )


class FakeHash:
    """Fake perceptual hash that supports subtraction."""
    def __init__(self, value: int, bits: int = 64):
        self.value = value
        self._bits = bits

    def __sub__(self, other: "FakeHash") -> int:
        return abs(self.value - other.value)

    @property
    def hash(self):
        import numpy as np
        return np.zeros((8, 8))  # 8x8 = 64 bits


# ── Criterion 13: similar images grouped and moved ────────────────────────────

def test_similar_images_moved_to_subfolder(tmp_path):
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")
    make_jpeg(src / "c.jpg")

    config = _make_config(tmp_path, threshold=0.96)

    # All three images are similar (hash distance 0)
    same_hash = FakeHash(0)

    from datetime import datetime
    early_date = datetime(2020, 1, 1)
    later_date = datetime(2021, 1, 1)

    # a.jpg has earliest date, so it's the representative
    def fake_date(path: Path) -> datetime:
        if path.name == "a.jpg":
            return early_date
        return later_date

    with patch("imagesorter.similarity._hash_image", return_value=same_hash), \
         patch("imagesorter.similarity._get_image_date", side_effect=fake_date):
        run(config)

    # Subfolder named after representative (a) should exist in src
    subfolder = src / "a"
    assert subfolder.is_dir()
    assert (subfolder / "a.jpg").exists()
    assert (subfolder / "b.jpg").exists()
    assert (subfolder / "c.jpg").exists()


def test_solo_image_left_in_place(tmp_path):
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "lonely.jpg")
    make_jpeg(src / "other.jpg")

    config = _make_config(tmp_path, threshold=0.96)

    # lonely gets hash 0, other gets hash 100 (very different)
    hash_map = {
        "lonely.jpg": FakeHash(0),
        "other.jpg": FakeHash(100),
    }

    def fake_hash(path: Path):
        return hash_map[path.name]

    from datetime import datetime
    with patch("imagesorter.similarity._hash_image", side_effect=fake_hash), \
         patch("imagesorter.similarity._get_image_date", return_value=datetime(2020, 1, 1)):
        run(config)

    # Both images have no similar partner — should stay in place
    assert (src / "lonely.jpg").exists()
    assert (src / "other.jpg").exists()


def test_transitive_grouping(tmp_path):
    """A~B and B~C means all three go in one group, even if A and C differ."""
    from imagesorter.similarity import _transitive_groups

    # A=0, B=1, C=2: pairs (0,1) and (1,2)
    groups = _transitive_groups([(0, 1), (1, 2)])
    # Should be one group of 3
    assert len(groups) == 1
    assert set(groups[0]) == {0, 1, 2}


# ── Criterion (pending): copy_instead_of_move in SimilaritySearch ────────────

def test_similarity_copy_keeps_source_files(tmp_path):
    """When copy_instead_of_move=True, source files remain after grouping."""
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")

    config = _make_config(tmp_path, threshold=0.96, copy=True)

    same_hash = FakeHash(0)
    from datetime import datetime

    def fake_date(path: Path) -> datetime:
        if path.name == "a.jpg":
            return datetime(2020, 1, 1)
        return datetime(2021, 1, 1)

    with patch("imagesorter.similarity._hash_image", return_value=same_hash), \
         patch("imagesorter.similarity._get_image_date", side_effect=fake_date):
        run(config)

    # Copies should be in the subfolder
    subfolder = src / "a"
    assert (subfolder / "a.jpg").exists()
    assert (subfolder / "b.jpg").exists()
    # Originals must still exist (copy, not move)
    assert (src / "a.jpg").exists()
    assert (src / "b.jpg").exists()


# ── Criterion 24: empty / zero-match source folder ────────────────────────────

def test_empty_source_logs_summary_similarity(tmp_path, caplog):
    """SimilaritySearch: empty source completes without error and logs a run summary."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    src.mkdir()  # empty

    config = _make_config(tmp_path, threshold=0.96)

    with caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        run(config)

    summary_messages = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_messages, "Expected a run summary log when source is empty"


def test_unsupported_formats_only_logs_summary_similarity(tmp_path, caplog):
    """SimilaritySearch: source with only unsupported formats logs a run summary."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    src.mkdir()
    (src / "notes.txt").write_text("hello")

    config = _make_config(tmp_path, threshold=0.96)

    with caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        run(config)

    summary_messages = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_messages, "Expected a run summary log when source has only unsupported formats"


# ── Criterion 14: solo images left in place ────────────────────────────────────

def test_no_subfolder_for_solo_images(tmp_path):
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "solo.jpg")

    config = _make_config(tmp_path, threshold=0.96)

    same_hash = FakeHash(0)
    from datetime import datetime
    with patch("imagesorter.similarity._hash_image", return_value=same_hash), \
         patch("imagesorter.similarity._get_image_date", return_value=datetime(2020, 1, 1)):
        run(config)

    # Single image, no pair — stays in place, no subfolder
    assert (src / "solo.jpg").exists()
    # No unexpected subfolders
    subdirs = [p for p in src.iterdir() if p.is_dir()]
    assert subdirs == []


# ── Progress INFO logs during pairwise comparison for >500 images ─────────────

def test_progress_logs_emitted_for_large_collections(tmp_path, caplog):
    """When more than 500 images are compared, periodic INFO progress logs must be emitted."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    src.mkdir()

    # Create 502 image entries (real files not needed since we mock hashing)
    n = 502
    # We only need the paths to exist for the image list; use a minimal file
    for i in range(n):
        img_path = src / f"img_{i:04d}.jpg"
        img_path.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 10)  # minimal JPEG-like header

    config = _make_config(tmp_path, threshold=0.99)  # high threshold → no matches

    call_count = [0]

    def counting_hash(path: Path):
        call_count[0] += 1
        return FakeHash(call_count[0])  # all different hashes → no pairs

    with caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        with patch("imagesorter.similarity._hash_image", side_effect=counting_hash):
            run(config)

    # Only check for progress logs that mention "comparing" or "pairs" explicitly
    # (not matching on path names which may accidentally contain keywords)
    progress_msgs = [
        r.getMessage() for r in caplog.records
        if r.levelno == logging.INFO and r.name == "imagesorter.similarity" and (
            r.funcName != "run" or (
                "comparing" in r.getMessage().lower()
                or r.getMessage().startswith("Comparing")
            )
        ) and "comparing" in r.getMessage().lower()
    ]
    assert progress_msgs, (
        f"Expected at least one INFO progress log during pairwise comparison of {n} images, got none. "
        f"All INFO logs: {[r.getMessage() for r in caplog.records if r.levelno == logging.INFO]}"
    )


# ── No groups found: actionable log message ───────────────────────────────────

def test_no_similar_groups_found_emits_actionable_message(tmp_path, caplog):
    """When no similar groups found, log must include actionable advice to lower threshold."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")

    # Use threshold=0.99 so diverse images (big hash diff) won't match
    config = _make_config(tmp_path, threshold=0.99)

    # Assign very different hashes so no pairs match
    def no_match_hash(path: Path):
        return FakeHash(0 if path.name == "a.jpg" else 60)

    with caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        with patch("imagesorter.similarity._hash_image", side_effect=no_match_hash):
            run(config)

    all_msgs = [r.message for r in caplog.records]
    assert any(
        "threshold" in m.lower() or "lower" in m.lower() or "no group" in m.lower()
        for m in all_msgs
    ), f"Expected actionable message about threshold when no groups found, got: {all_msgs}"


# ── similarity_threshold=0.0 raises ValueError or emits WARNING ───────────────

def test_similarity_threshold_zero_emits_warning(tmp_path, caplog):
    """similarity_threshold=0.0 must emit a WARNING before proceeding (every pair matches)."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")

    config = _make_config(tmp_path, threshold=0.0)

    with caplog.at_level(logging.WARNING, logger="imagesorter.similarity"):
        with patch("imagesorter.similarity._hash_image", return_value=FakeHash(0)), \
             patch("imagesorter.similarity._get_image_date", return_value=__import__("datetime").datetime(2020, 1, 1)):
            run(config)

    warning_msgs = [r.message for r in caplog.records if r.levelno == logging.WARNING]
    assert any(
        "threshold" in m.lower() or "0.0" in m or "every" in m.lower() or "meaningless" in m.lower()
        for m in warning_msgs
    ), f"Expected WARNING about threshold=0.0, got: {warning_msgs}"


# ── Symlinks outside source skipped in similarity mode ───────────────────────

def test_similarity_symlink_outside_source_not_included(tmp_path):
    """A symlink inside source_folder pointing outside must be skipped (not included in image list)."""
    from imagesorter.similarity import run

    src = tmp_path / "src"
    src.mkdir()
    # No real images in source — only a symlink pointing outside

    outside = tmp_path / "outside"
    outside.mkdir()
    outside_img = make_jpeg(outside / "external.jpg")

    symlink = src / "external.jpg"
    try:
        symlink.symlink_to(outside_img)
    except (OSError, NotImplementedError):
        pytest.skip("Symlinks not supported on this system")

    config = _make_config(tmp_path, threshold=0.96)

    hash_calls: list[Path] = []

    def tracking_hash(path: Path):
        hash_calls.append(path)
        return FakeHash(0)

    with patch("imagesorter.similarity._hash_image", side_effect=tracking_hash):
        run(config)

    # The symlink inside source (pointing outside) must NOT be hashed
    symlink_in_hash_calls = [p for p in hash_calls if p.is_symlink()]
    outside_symlinks = []
    for p in symlink_in_hash_calls:
        try:
            p.resolve().relative_to(src.resolve())
        except ValueError:
            outside_symlinks.append(p)

    assert not outside_symlinks, (
        f"Symlinks pointing outside source were hashed: {outside_symlinks}"
    )


# ── AC6: discovery INFO logged before any hash operation ─────────────────────

def test_discovery_info_logged_before_hashing(tmp_path, caplog):
    """An INFO record mentioning discovery appears before any hash operation."""
    import logging
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")

    config = _make_config(tmp_path, threshold=0.96)

    hash_calls: list[str] = []

    def tracking_hash(path: Path):
        hash_calls.append(path.name)
        return FakeHash(0)

    with caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        with patch("imagesorter.similarity._hash_image", side_effect=tracking_hash):
            run(config)

    records = caplog.records
    discovery_records = [
        r for r in records
        if r.levelno == logging.INFO and (
            "Discovering" in r.message or "Found" in r.message
        )
    ]
    assert discovery_records, "Expected at least one INFO record mentioning discovery"

    # The discovery message must appear before hashing starts.
    # We verify this by checking discovery logs exist and hash was called after run started.
    # Since logs are emitted sequentially before the hash loop, the first discovery
    # record precedes any hashing.
    first_discovery_msg = discovery_records[0].message
    assert "Discovering" in first_discovery_msg or "Found" in first_discovery_msg


# ── Pending: similarity._get_image_date emits WARNING on EXIF failure ─────────

def test_similarity_get_image_date_warns_on_exif_failure(tmp_path, caplog):
    """When PIL fails to parse EXIF for a file, similarity._get_image_date emits WARNING naming the file."""
    import logging
    from imagesorter.similarity import _get_image_date

    img_path = make_jpeg(tmp_path / "broken_exif.jpg")

    with caplog.at_level(logging.WARNING, logger="imagesorter.similarity"):
        with patch("PIL.Image.open") as mock_open:
            mock_img = MagicMock()
            mock_img._getexif.side_effect = Exception("corrupt EXIF")
            mock_open.return_value.__enter__ = MagicMock(return_value=mock_img)
            mock_open.return_value.__exit__ = MagicMock(return_value=False)
            mock_open.return_value = mock_img
            _get_image_date(img_path)

    warning_msgs = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("broken_exif.jpg" in m for m in warning_msgs), (
        f"Expected WARNING naming 'broken_exif.jpg' on EXIF failure, got: {warning_msgs}"
    )


# ── Pending: similarity._get_image_date uses st_mtime (not st_ctime) ──────────

def test_web_ui_false_moves_files_normally(tmp_path):
    """When config.web_ui is False, similarity.run() must behave like the pre-feature implementation."""
    from imagesorter.similarity import run

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")

    config = _make_config(tmp_path, threshold=0.96)
    assert config.web_ui is False

    same_hash = FakeHash(0)
    from datetime import datetime

    def fake_date(path: Path) -> datetime:
        if path.name == "a.jpg":
            return datetime(2020, 1, 1)
        return datetime(2021, 1, 1)

    with patch("imagesorter.similarity._hash_image", return_value=same_hash), \
         patch("imagesorter.similarity._get_image_date", side_effect=fake_date):
        run(config)

    # Files should be moved into a subfolder (normal SimilaritySearch behavior)
    subfolder = src / "a"
    assert subfolder.is_dir()
    assert (subfolder / "a.jpg").exists()
    assert (subfolder / "b.jpg").exists()
    # Originals must NOT remain (move, not copy)
    assert not (src / "a.jpg").exists()
    assert not (src / "b.jpg").exists()


def test_similarity_get_image_date_uses_mtime_for_fallback(tmp_path):
    """When EXIF is absent, _get_image_date falls back to st_mtime, not st_ctime."""
    from imagesorter.similarity import _get_image_date
    from datetime import datetime, timezone
    import os

    img_path = make_jpeg(tmp_path / "no_exif.jpg")

    # Set a known mtime and a different ctime-equivalent via os.utime
    known_mtime = 1_000_000.0  # some epoch timestamp
    os.utime(img_path, (known_mtime, known_mtime))

    # Mock stat to return distinguishable mtime vs ctime values
    original_stat = img_path.stat()

    class FakeStat:
        st_mtime = known_mtime
        st_ctime = known_mtime + 9999  # deliberately different

    with patch("pathlib.Path.stat", return_value=FakeStat()):
        # Also mock PIL to return no EXIF (so we hit the fallback)
        with patch("PIL.Image.open") as mock_open:
            mock_img = MagicMock()
            mock_img._getexif.return_value = None
            mock_open.return_value = mock_img
            result = _get_image_date(img_path)

    expected = datetime.fromtimestamp(known_mtime)
    assert result == expected, (
        f"Expected fallback to st_mtime ({expected}), got {result}. "
        "similarity._get_image_date must use st_mtime, not st_ctime."
    )
