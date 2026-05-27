"""Tests for file_ops: collision handling and copy-vs-move."""
import logging
import pytest
from pathlib import Path
from PIL import Image


def make_file(path: Path, content: bytes = b"data") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


# ── Criterion 11: collision handling ──────────────────────────────────────────

def test_no_collision_moves_file(tmp_path):
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg")
    dest_dir = tmp_path / "dest"
    result = transfer(src, dest_dir, copy=False)
    assert result == dest_dir / "photo.jpg"
    assert result.exists()
    assert not src.exists()


def test_collision_renames_with_suffix(tmp_path):
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg", b"new content")
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    # Pre-existing file
    (dest_dir / "photo.jpg").write_bytes(b"old content")

    result = transfer(src, dest_dir, copy=False)
    assert result.name == "photo_1.jpg"
    assert (dest_dir / "photo.jpg").read_bytes() == b"old content"
    assert result.read_bytes() == b"new content"


def test_collision_increments_suffix(tmp_path):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"orig")
    (dest_dir / "photo_1.jpg").write_bytes(b"first")

    src = make_file(tmp_path / "src" / "photo.jpg", b"second")
    result = transfer(src, dest_dir, copy=False)
    assert result.name == "photo_2.jpg"


def test_collision_logs_warning(tmp_path, caplog):
    from imagesorter.file_ops import transfer
    dest_dir = tmp_path / "dest"
    dest_dir.mkdir()
    (dest_dir / "photo.jpg").write_bytes(b"existing")

    src = make_file(tmp_path / "src" / "photo.jpg", b"incoming")
    with caplog.at_level(logging.WARNING, logger="imagesorter.file_ops"):
        transfer(src, dest_dir, copy=False)
    assert any("Collision" in r.message or "collision" in r.message.lower()
                for r in caplog.records)


# ── Criterion 12: copy_instead_of_move ───────────────────────────────────────

def test_copy_leaves_source_intact(tmp_path):
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg", b"data")
    dest_dir = tmp_path / "dest"
    result = transfer(src, dest_dir, copy=True)
    assert result.exists()
    assert src.exists()  # source still in place


def test_move_removes_source_after_confirmed_copy(tmp_path):
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg", b"data")
    dest_dir = tmp_path / "dest"
    result = transfer(src, dest_dir, copy=False)
    assert result.exists()
    assert not src.exists()
