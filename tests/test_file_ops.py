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


# ── Atomicity: rollback on delete failure ─────────────────────────────────────

def test_move_rolls_back_dest_when_src_delete_fails(tmp_path):
    """If os.remove(src) raises, the destination copy must be deleted (no duplicate)."""
    from unittest.mock import patch
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg", b"data")
    dest_dir = tmp_path / "dest"

    with patch("imagesorter.file_ops.os.remove", side_effect=PermissionError("Access is denied")):
        with pytest.raises(PermissionError):
            transfer(src, dest_dir, copy=False)

    assert not (dest_dir / "photo.jpg").exists()


def test_move_leaves_source_intact_when_src_delete_fails(tmp_path):
    """If os.remove(src) raises, the source file must still exist."""
    from unittest.mock import patch
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg", b"data")
    dest_dir = tmp_path / "dest"

    with patch("imagesorter.file_ops.os.remove", side_effect=PermissionError("Access is denied")):
        with pytest.raises(PermissionError):
            transfer(src, dest_dir, copy=False)

    assert src.exists()


# ── Thread-safe collision detection ──────────────────────────────────────────

def test_concurrent_transfer_no_data_loss(tmp_path):
    """Two threads transferring same-named files concurrently must not overwrite each other."""
    import threading
    from imagesorter.file_ops import transfer

    src1 = make_file(tmp_path / "src1" / "photo.jpg", b"content-from-source-1")
    src2 = make_file(tmp_path / "src2" / "photo.jpg", b"content-from-source-2")
    dest_dir = tmp_path / "dest"

    results = []
    errors = []

    def move(src):
        try:
            result = transfer(src, dest_dir, copy=False)
            results.append(result)
        except Exception as exc:
            errors.append(exc)

    t1 = threading.Thread(target=move, args=(src1,))
    t2 = threading.Thread(target=move, args=(src2,))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    assert not errors, f"Unexpected errors: {errors}"
    # Both sources moved
    assert not src1.exists(), "src1 should have been moved"
    assert not src2.exists(), "src2 should have been moved"
    # Total bytes in dest equals total bytes from both sources
    total_source_bytes = len(b"content-from-source-1") + len(b"content-from-source-2")
    dest_files = list(dest_dir.iterdir())
    assert len(dest_files) == 2, f"Expected 2 files in dest, got {len(dest_files)}: {dest_files}"
    total_dest_bytes = sum(f.read_bytes().__len__() for f in dest_files)
    assert total_dest_bytes == total_source_bytes, "File content was overwritten"


# ── Windows path length limit ────────────────────────────────────────────────

def test_transfer_raises_oserror_when_path_exceeds_260_chars(tmp_path):
    """transfer() must raise explicit OSError when resolved destination path exceeds 260 characters."""
    from imagesorter.file_ops import transfer
    src = make_file(tmp_path / "src" / "photo.jpg")
    # Build a dest_dir whose resolved path + filename exceeds 260 chars
    long_segment = "a" * 200
    dest_dir = tmp_path / long_segment / long_segment
    with pytest.raises(OSError) as exc_info:
        transfer(src, dest_dir, copy=False)
    assert str(dest_dir / src.name) in str(exc_info.value) or len(str(dest_dir / src.name)) > 260


# ── Bounded loop in _collision_free_path() ────────────────────────────────────

def test_collision_free_path_raises_after_max_iterations(tmp_path):
    """_collision_free_path() must raise RuntimeError when all candidates exist."""
    from unittest.mock import patch
    from imagesorter.file_ops import _collision_free_path

    dest = tmp_path / "photo.jpg"
    dest.write_bytes(b"existing")

    with patch("pathlib.Path.exists", return_value=True):
        with pytest.raises(RuntimeError):
            _collision_free_path(dest)
