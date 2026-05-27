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
