"""Tests for collision-policy feature (AC1-AC10)."""
from __future__ import annotations

import logging
import yaml
import pytest
from pathlib import Path


def make_file(path: Path, content: bytes = b"data") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def _base_config_data(tmp_path: Path) -> dict:
    return {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others", "destination": str(tmp_path)},
    }


# ── AC1 — Config field with default ───────────────────────────────────────────

def test_config_on_collision_default_is_rename():
    from imagesorter.config import Config, Unclassified
    config = Config(
        mode="GroupByTags",
        source_folder="./photos",
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(enabled=False, folder_name="others", destination="./sorted"),
        similarity_threshold=0.96,
    )
    assert config.on_collision == "rename"


# ── AC2 — load() reads on_collision from YAML ─────────────────────────────────

def test_load_reads_on_collision_skip(tmp_path):
    from imagesorter.config import load
    data = _base_config_data(tmp_path)
    data["on_collision"] = "skip"
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(data))
    config = load(str(cfg_file))
    assert config.on_collision == "skip"


def test_load_on_collision_defaults_to_rename_when_absent(tmp_path):
    from imagesorter.config import load
    data = _base_config_data(tmp_path)
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(data))
    config = load(str(cfg_file))
    assert config.on_collision == "rename"


# ── AC3 — load() rejects invalid values ───────────────────────────────────────

def test_load_raises_for_invalid_on_collision(tmp_path):
    from imagesorter.config import load
    data = _base_config_data(tmp_path)
    data["on_collision"] = "overwrite"
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(data))
    with pytest.raises(ValueError, match="on_collision"):
        load(str(cfg_file))


# ── AC4 — TEMPLATE declares the key with a comment ────────────────────────────

def test_template_contains_on_collision():
    from imagesorter.config import TEMPLATE
    assert "on_collision" in TEMPLATE


def test_template_on_collision_parses_to_rename():
    from imagesorter.config import TEMPLATE
    parsed = yaml.safe_load(TEMPLATE)
    assert parsed["on_collision"] == "rename"


def test_template_on_collision_has_inline_comment():
    from imagesorter.config import TEMPLATE
    for line in TEMPLATE.splitlines():
        if "on_collision" in line and "#" in line:
            assert "rename" in line and "skip" in line
            return
    raise AssertionError("TEMPLATE on_collision line has no inline comment with rename/skip")


# ── AC5 — --generate-config emits the key ─────────────────────────────────────

def test_generate_config_emits_on_collision():
    import subprocess
    import sys
    result = subprocess.run(
        [sys.executable, "-m", "imagesorter", "--generate-config"],
        capture_output=True,
        text=True,
        cwd="X:\\projekty\\image-sorter",
    )
    assert "on_collision" in result.stdout


# ── AC6 — rename policy (existing behaviour, unchanged) ───────────────────────

def test_transfer_rename_policy_renames_on_collision(tmp_path):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"old")
    src = make_file(tmp_path / "src" / "photo.jpg", b"new")
    result = transfer(src, dest_dir, copy=False, on_collision="rename")
    assert result is not None
    assert result.name == "photo_1.jpg"
    assert (dest_dir / "photo.jpg").read_bytes() == b"old"


def test_transfer_rename_policy_logs_warning(tmp_path, caplog):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"old")
    src = make_file(tmp_path / "src" / "photo.jpg", b"new")
    with caplog.at_level(logging.WARNING, logger="imagesorter.file_ops"):
        transfer(src, dest_dir, copy=False, on_collision="rename")
    assert any("Collision" in r.message or "collision" in r.message.lower() for r in caplog.records)


# ── AC7 — skip policy leaves file at source and logs WARNING ──────────────────

def test_transfer_skip_returns_none_on_collision(tmp_path):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")
    src = make_file(tmp_path / "src" / "photo.jpg", b"incoming")
    result = transfer(src, dest_dir, copy=False, on_collision="skip")
    assert result is None


def test_transfer_skip_leaves_source_intact(tmp_path):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")
    src = make_file(tmp_path / "src" / "photo.jpg", b"incoming")
    transfer(src, dest_dir, copy=False, on_collision="skip")
    assert src.exists()


def test_transfer_skip_leaves_destination_unchanged(tmp_path):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")
    src = make_file(tmp_path / "src" / "photo.jpg", b"incoming")
    transfer(src, dest_dir, copy=False, on_collision="skip")
    assert (dest_dir / "photo.jpg").read_bytes() == b"existing"


def test_transfer_skip_logs_warning(tmp_path, caplog):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")
    src = make_file(tmp_path / "src" / "photo.jpg", b"incoming")
    with caplog.at_level(logging.WARNING, logger="imagesorter.file_ops"):
        transfer(src, dest_dir, copy=False, on_collision="skip")
    assert any("skip" in r.message.lower() for r in caplog.records)


def test_transfer_skip_no_collision_still_moves(tmp_path):
    """When there is no collision, skip policy behaves normally."""
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    src = make_file(tmp_path / "src" / "photo.jpg", b"data")
    result = transfer(src, dest_dir, copy=False, on_collision="skip")
    assert result is not None
    assert result.exists()
    assert not src.exists()


# ── AC8 — GroupByTags counts skipped-by-collision in skipped counter ──────────

def test_sorter_skip_policy_increments_skipped(tmp_path, caplog):
    from unittest.mock import MagicMock, patch
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter import sorter

    src_dir = tmp_path / "src"
    src_dir.mkdir()
    img = make_file(src_dir / "photo.jpg", b"img")

    dest_dir = tmp_path / "dest" / "others"
    dest_dir.mkdir(parents=True)
    (dest_dir / "photo.jpg").write_bytes(b"existing")

    config = Config(
        mode="GroupByTags",
        source_folder=str(src_dir),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=True,
            folder_name="others",
            destination=str(tmp_path / "dest"),
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
        on_collision="skip",
    )

    mock_result = MagicMock()
    mock_result.boxes = None
    mock_model = MagicMock(return_value=[mock_result])
    mock_model.names = {}

    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=MagicMock(year=2024, month=1)), \
         caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        sorter.run(config)

    summary = next(r.message for r in caplog.records if "Run summary" in r.message)
    assert "skipped=1" in summary
    assert "moved=0" in summary


# ── AC9 — SimilaritySearch counts skipped-by-collision in skipped counter ─────

def test_similarity_skip_policy_increments_skipped(tmp_path, caplog):
    from unittest.mock import MagicMock, patch
    from imagesorter.config import Config, Unclassified
    from imagesorter import similarity

    src_dir = tmp_path / "src"
    src_dir.mkdir()
    img1 = make_file(src_dir / "a.jpg", b"img1")
    img2 = make_file(src_dir / "b.jpg", b"img2")

    config = Config(
        mode="SimilaritySearch",
        source_folder=str(src_dir),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(enabled=False, folder_name="others", destination=str(tmp_path)),
        similarity_threshold=0.5,
        on_collision="skip",
    )

    fake_hash = MagicMock()
    fake_hash.__sub__ = MagicMock(return_value=0)
    fake_hash.hash = [[0] * 8] * 8

    # Pre-create a collision: b.jpg exists in the subfolder named after a (the representative)
    # Representative is earliest date — patch _get_image_date so img1 is earliest
    from datetime import datetime
    dates = {img1: datetime(2020, 1, 1), img2: datetime(2021, 1, 1)}

    subfolder = img1.parent / img1.stem
    subfolder.mkdir(parents=True)
    (subfolder / "b.jpg").write_bytes(b"existing")

    def fake_get_date(p):
        return dates[p]

    with patch("imagesorter.similarity._hash_image", return_value=fake_hash), \
         patch("imagesorter.similarity._get_image_date", side_effect=fake_get_date), \
         caplog.at_level(logging.INFO, logger="imagesorter.similarity"):
        similarity.run(config)

    summary = next(r.message for r in caplog.records if "Run summary" in r.message)
    # moved=1 for a.jpg (no collision), skipped=1 for b.jpg (collision)
    # But actually skipped starts at 0 for SimilaritySearch (no solo groups here)
    # Both files are in a group; a.jpg moves fine, b.jpg collides => skipped
    assert "skipped=1" in summary
