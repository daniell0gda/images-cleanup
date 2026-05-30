"""Integration tests for sorter.run()."""
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
from PIL import Image


def make_jpeg(path: Path) -> Path:
    """Create a minimal JPEG image at path."""
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (10, 10), color=(128, 128, 128))
    img.save(str(path), "JPEG")
    return path


def _make_config(tmp_path, tag_groups, *, copy=False, recursive=False,
                 unclassified_enabled=False, unclassified_dest=None):
    from imagesorter.config import Config, TagGroup, Unclassified
    return Config(
        mode="GroupByTags",
        source_folder=str(tmp_path / "src"),
        recursive=recursive,
        copy_instead_of_move=copy,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=unclassified_enabled,
            folder_name="others",
            destination=str(unclassified_dest or tmp_path / "unclassified"),
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )


def _make_yolo_result(class_names: list[str], all_names: dict):
    """Build a mock YOLO result with the given detected class names."""
    result = MagicMock()
    # Map class names to indices
    name_to_id = {v: k for k, v in all_names.items()}
    cls_ids = [name_to_id[n] for n in class_names]
    import torch
    result.boxes.cls = torch.tensor(cls_ids, dtype=torch.float32)
    return result


COCO_NAMES = {
    0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus",
    7: "truck", 14: "bird", 15: "cat", 16: "dog",
}


# ── Criterion 6: AND logic, most-specific wins, earlier-listed tie-break ──────

def test_image_moved_to_matching_group(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest = tmp_path / "family"

    tag_groups = [
        TagGroup(name="Family", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    result = _make_yolo_result(["person"], COCO_NAMES)
    mock_model.return_value = [result]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "photo.jpg").exists()
    assert not img.exists()  # moved, not copied


def test_and_logic_all_tags_required(tmp_path):
    """Image with only one of two required tags should NOT match."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest = tmp_path / "vehicles"

    tag_groups = [
        TagGroup(name="Vehicles", tags=["car", "truck"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    # Only 'car' detected, not 'truck'
    result = _make_yolo_result(["car"], COCO_NAMES)
    mock_model.return_value = [result]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    # Image should not be moved (no unclassified enabled)
    assert img.exists()
    assert not (dest / "photo.jpg").exists()


def test_most_specific_rule_wins(tmp_path):
    """When multiple groups match, most tags wins."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest_generic = tmp_path / "has_person"
    dest_specific = tmp_path / "person_and_dog"

    tag_groups = [
        TagGroup(name="Generic", tags=["person"], destination=str(dest_generic),
                 group_by_year=False, group_by_month=False),
        TagGroup(name="Specific", tags=["person", "dog"], destination=str(dest_specific),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    result = _make_yolo_result(["person", "dog"], COCO_NAMES)
    mock_model.return_value = [result]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest_specific / "photo.jpg").exists()
    assert not (dest_generic / "photo.jpg").exists()


def test_earlier_listed_rule_wins_tie(tmp_path):
    """When two groups have same tag count, first in list wins."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest_first = tmp_path / "first"
    dest_second = tmp_path / "second"

    tag_groups = [
        TagGroup(name="First", tags=["person"], destination=str(dest_first),
                 group_by_year=False, group_by_month=False),
        TagGroup(name="Second", tags=["dog"], destination=str(dest_second),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    result = _make_yolo_result(["person", "dog"], COCO_NAMES)
    mock_model.return_value = [result]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest_first / "photo.jpg").exists()
    assert not (dest_second / "photo.jpg").exists()


# ── Criterion 7: group_by_year / group_by_month sub-paths ─────────────────────

def test_flat_when_both_false(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "photo.jpg").exists()


def test_year_subpath_when_year_only(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run
    from datetime import datetime

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=True, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    fixed_dt = datetime(2023, 7, 15)
    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=fixed_dt):
        run(config)

    assert (dest / "2023" / "photo.jpg").exists()


def test_year_month_subpath_when_both_true(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run
    from datetime import datetime

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=True, group_by_month=True),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    fixed_dt = datetime(2023, 7, 15)
    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=fixed_dt):
        run(config)

    assert (dest / "2023" / "07" / "photo.jpg").exists()


def test_month_only_subpath(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run
    from datetime import datetime

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=True),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    fixed_dt = datetime(2023, 7, 15)
    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=fixed_dt):
        run(config)

    assert (dest / "07" / "photo.jpg").exists()


# ── Criterion 8: EXIF date vs mtime fallback ──────────────────────────────────

def test_exif_date_used_when_present(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter import sorter
    from datetime import datetime

    src = tmp_path / "src"
    src.mkdir()
    img_path = make_jpeg(src / "photo.jpg")

    # Patch _get_image_date to simulate EXIF present
    exif_date = datetime(2020, 3, 5, 12, 0, 0)
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=True, group_by_month=True),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=exif_date):
        sorter.run(config)

    assert (dest / "2020" / "03" / "photo.jpg").exists()


# ── Criterion 9: unclassified images ──────────────────────────────────────────

def test_unclassified_image_placed_in_unclassified_folder(tmp_path):
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "unknown.jpg")
    unclassified_dest = tmp_path / "sorted"

    config = _make_config(
        tmp_path,
        tag_groups=[],  # no groups
        unclassified_enabled=True,
        unclassified_dest=unclassified_dest,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result([], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (unclassified_dest / "others" / "unknown.jpg").exists()
    assert not img.exists()


def test_unclassified_disabled_image_stays(tmp_path):
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "unknown.jpg")

    config = _make_config(tmp_path, tag_groups=[], unclassified_enabled=False)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result([], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert img.exists()


# ── Criterion (pending): unclassified path ordering ───────────────────────────

def test_unclassified_path_folder_name_before_year_month(tmp_path):
    """Path must be destination/folder_name/YYYY/MM, not destination/YYYY/MM/folder_name."""
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run
    from datetime import datetime

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "unknown.jpg")
    unclassified_dest = tmp_path / "sorted"

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
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
            destination=str(unclassified_dest),
            group_by_year=True,
            group_by_month=True,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result([], COCO_NAMES)]
    fixed_dt = datetime(2023, 7, 15)

    with patch("imagesorter.sorter.YOLO", return_value=mock_model), \
         patch("imagesorter.sorter._get_image_date", return_value=fixed_dt):
        run(config)

    # Correct: destination / folder_name / YYYY / MM
    assert (unclassified_dest / "others" / "2023" / "07" / "unknown.jpg").exists(), (
        "Expected destination/folder_name/YYYY/MM, got wrong path order"
    )
    # Make sure it's NOT under the wrong path
    assert not (unclassified_dest / "2023" / "07" / "others" / "unknown.jpg").exists()


# ── Criterion (pending): recursive flag ───────────────────────────────────────

def test_recursive_false_ignores_subdirectory_images(tmp_path):
    """Images in subdirectories must NOT be processed when recursive=False."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    top_img = make_jpeg(src / "top.jpg")
    sub_img = make_jpeg(src / "sub" / "nested.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups, recursive=False)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    # Only one image is scanned — top.jpg
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "top.jpg").exists()
    assert sub_img.exists()  # subdirectory image untouched


def test_recursive_true_includes_subdirectory_images(tmp_path):
    """Images in subdirectories must be processed when recursive=True."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    top_img = make_jpeg(src / "top.jpg")
    sub_img = make_jpeg(src / "sub" / "nested.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups, recursive=True)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    # Two images scanned
    mock_model.return_value = [
        _make_yolo_result(["person"], COCO_NAMES),
        _make_yolo_result(["person"], COCO_NAMES),
    ]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "top.jpg").exists()
    assert (dest / "nested.jpg").exists()


# ── Criterion (pending): thread-safety of counters ────────────────────────────

def test_counter_accuracy_with_multiple_threads(tmp_path, caplog):
    """Counters must be accurate when N images are processed concurrently."""
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter import sorter
    import logging

    src = tmp_path / "src"
    src.mkdir()
    n = 20
    for i in range(n):
        make_jpeg(src / f"photo_{i:02d}.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=8,  # force concurrency
        log_level="DEBUG",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)] * n

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            sorter.run(config)

    summary_messages = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_messages, "No summary log emitted"
    summary = summary_messages[-1]
    # All n images should be counted as moved
    assert f"moved={n}" in summary, f"Expected moved={n} in summary, got: {summary}"
    assert "errors=0" in summary


# ── Criterion (pending): tag case-insensitivity ────────────────────────────────

def test_mixed_case_tag_matches_lowercase_detection(tmp_path):
    """Tag 'Person' in a rule must match YOLO-detected label 'person'."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest = tmp_path / "family"

    # Rule tag uses mixed case — should still match lowercase YOLO output
    tag_groups = [
        TagGroup(name="Family", tags=["Person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES  # returns lowercase "person"
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "photo.jpg").exists(), "Mixed-case tag 'Person' should match detected 'person'"


def test_uppercase_tag_matches_lowercase_detection(tmp_path):
    """Tag 'CAR' in a rule must match YOLO-detected label 'car'."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest = tmp_path / "vehicles"

    tag_groups = [
        TagGroup(name="Vehicles", tags=["CAR"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["car"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "photo.jpg").exists(), "Uppercase tag 'CAR' should match detected 'car'"


# ── Criterion 24: empty / zero-match source folder ────────────────────────────

def test_empty_source_logs_summary_group_by_tags(tmp_path, caplog):
    """GroupByTags: empty source completes without error and logs a run summary."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()  # empty — no images at all

    dest = tmp_path / "out"
    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = []

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)  # must not raise

    summary_messages = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_messages, "Expected a run summary log when source has no matching images"


def test_unsupported_formats_only_logs_summary_group_by_tags(tmp_path, caplog):
    """GroupByTags: source with only unsupported formats logs a run summary."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    (src / "notes.txt").write_text("hello")  # not in include_formats

    dest = tmp_path / "out"
    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = []

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    summary_messages = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_messages, "Expected a run summary log when source has no matching images"


# ── Criterion 23: include_formats filtering ───────────────────────────────────

def test_non_listed_extension_not_processed(tmp_path):
    """Files whose extension is not in include_formats must not be moved or passed to YOLO."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    # Only .jpg is in include_formats; .txt and .png must be skipped
    txt_file = src / "notes.txt"
    txt_file.write_text("hello")
    png_file = src / "image.png"
    make_jpeg(png_file)  # valid image content, but wrong extension relative to config

    dest = tmp_path / "out"
    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    # include_formats only has .jpg — png is excluded
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = []  # no images fed to YOLO

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    # Non-listed files must remain untouched
    assert txt_file.exists(), ".txt file should not be moved"
    assert png_file.exists(), ".png file should not be moved when not in include_formats"
    # Destination folder should not be created (nothing processed)
    assert not dest.exists() or list(dest.rglob("*")) == []


# ── Criterion: copy_instead_of_move=True in GroupByTags (end-to-end) ─────────

def test_copy_instead_of_move_keeps_source_files(tmp_path):
    """When copy_instead_of_move=True, source files remain in place after sorter.run()."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img1 = make_jpeg(src / "photo1.jpg")
    img2 = make_jpeg(src / "photo2.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups, copy=True)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [
        _make_yolo_result(["person"], COCO_NAMES),
        _make_yolo_result(["person"], COCO_NAMES),
    ]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    # Destination must have copies
    assert (dest / "photo1.jpg").exists(), "photo1.jpg should be copied to destination"
    assert (dest / "photo2.jpg").exists(), "photo2.jpg should be copied to destination"
    # Source files must still exist (copy, not move)
    assert img1.exists(), "photo1.jpg source must remain after copy_instead_of_move=True"
    assert img2.exists(), "photo2.jpg source must remain after copy_instead_of_move=True"


# ── Criterion: YOLO batch call (model called once per run()) ─────────────────

def test_yolo_called_once_with_full_list(tmp_path):
    """YOLO model must be called exactly once with a list of all images (default batch_size=16, 5 images)."""
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    for i in range(5):
        make_jpeg(src / f"img_{i}.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)] * 5

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    # The model must have been called exactly once (batch, not per-image)
    assert mock_model.call_count == 1, (
        f"Expected model to be called once (batch), got {mock_model.call_count} calls"
    )
    # The single call must have received a list (all images together)
    call_args = mock_model.call_args
    batch_arg = call_args[0][0]
    assert isinstance(batch_arg, list), f"Expected list argument, got {type(batch_arg)}"
    assert len(batch_arg) == 5, f"Expected 5 images in batch, got {len(batch_arg)}"


# ── Criterion: chunked YOLO calls with batch_size ─────────────────────────────

def test_yolo_called_once_per_chunk(tmp_path):
    """5 images + batch_size=2 → 3 YOLO calls (chunks of 2, 2, 1)."""
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    for i in range(5):
        make_jpeg(src / f"img_{i}.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
        batch_size=2,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)] * 2

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert mock_model.call_count == 3, (
        f"Expected 3 YOLO calls for 5 images with batch_size=2, got {mock_model.call_count}"
    )


# ── Criterion: conf= forwarded on every YOLO call ────────────────────────────

def test_conf_kwarg_forwarded_to_yolo(tmp_path):
    """confidence_threshold is passed as conf= on every YOLO call."""
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    for i in range(3):
        make_jpeg(src / f"img_{i}.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
        batch_size=2,
        confidence_threshold=0.7,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)] * 2

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    for call in mock_model.call_args_list:
        assert call.kwargs.get("conf") == 0.7, (
            f"Expected conf=0.7 in YOLO call, got kwargs={call.kwargs}"
        )


# ── Criterion: INFO log per batch ─────────────────────────────────────────────

def test_info_log_emitted_per_batch(tmp_path, caplog):
    """INFO log 'Processing batch N (images A–B)' emitted per batch."""
    import logging
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    for i in range(5):
        make_jpeg(src / f"img_{i}.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
        batch_size=2,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)] * 2

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    batch_logs = [r.message for r in caplog.records if "Processing batch" in r.message]
    assert len(batch_logs) == 3, f"Expected 3 batch log lines, got {len(batch_logs)}: {batch_logs}"
    # Check the new streaming format: "Processing batch N (images A–B)"
    assert "Processing batch 1 (images 1" in batch_logs[0]
    assert "Processing batch 2 " in batch_logs[1]
    assert "Processing batch 3 " in batch_logs[2]


# ── Criterion: result.boxes is None → zero detections, no error ──────────────

def test_none_boxes_no_attribute_error(tmp_path, caplog):
    """result.boxes=None must not raise AttributeError and must not increment errors."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")

    tag_groups = []
    config = _make_config(tmp_path, tag_groups, unclassified_enabled=False)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    result = MagicMock()
    result.boxes = None
    mock_model.return_value = [result]

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)  # must not raise

    summary = next(r.message for r in caplog.records if "Run summary" in r.message)
    assert "errors=0" in summary, f"Expected errors=0, got: {summary}"
    assert "skipped=1" in summary, f"Expected skipped=1, got: {summary}"


# ── Criterion: DEBUG log per image with detected labels ───────────────────────

def test_debug_log_with_detections(tmp_path, caplog):
    """DEBUG log 'photo.jpg: detected [person, dog]' emitted per image."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")

    tag_groups = []
    config = _make_config(tmp_path, tag_groups, unclassified_enabled=False)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person", "dog"], COCO_NAMES)]

    with caplog.at_level(logging.DEBUG, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    debug_logs = [r.message for r in caplog.records if "detected" in r.message and r.levelno == logging.DEBUG]
    assert debug_logs, f"Expected at least one DEBUG 'detected' log, got none. All logs: {[r.message for r in caplog.records]}"
    log = debug_logs[0]
    assert "photo.jpg" in log
    assert "person" in log
    assert "dog" in log


# ── Criterion 10: source == destination error ──────────────────────────────────

def test_error_when_destination_same_as_source(tmp_path):
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(src),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)
    config = config.__class__(
        mode=config.mode,
        source_folder=str(src),
        recursive=config.recursive,
        copy_instead_of_move=config.copy_instead_of_move,
        include_formats=config.include_formats,
        threads=config.threads,
        log_level=config.log_level,
        log_file=config.log_file,
        tag_groups=tag_groups,
        unclassified=config.unclassified,
        similarity_threshold=config.similarity_threshold,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        with pytest.raises(SystemExit):
            run(config)


# ── AC4: SystemExit when unclassified.destination == source_folder and enabled=True ─

def test_system_exit_when_unclassified_dest_same_as_source_enabled(tmp_path):
    """sorter.run() must raise SystemExit when enabled=True and destination resolves to source."""
    from imagesorter.config import Config, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=True,
            folder_name=src.name,       # folder_name = "src"
            destination=str(src.parent),  # destination/folder_name = tmp_path/src == source
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    with pytest.raises(SystemExit) as exc_info:
        run(config)

    msg = str(exc_info.value)
    assert "unclassified.destination" in msg
    assert "source_folder" in msg


# ── AC5: No SystemExit when destination==source but enabled=False ──────────────

def test_no_system_exit_when_unclassified_dest_same_as_source_disabled(tmp_path):
    """sorter.run() must NOT raise when enabled=False, even if destination == source."""
    from imagesorter.config import Config, Unclassified
    from imagesorter.sorter import run
    from unittest.mock import patch, MagicMock

    src = tmp_path / "src"
    src.mkdir()

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=False,
            folder_name="others",
            destination=str(src),  # same as source_folder — but disabled
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = []

    # Must NOT raise
    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)


def test_debug_log_empty_detections(tmp_path, caplog):
    """DEBUG log with empty list when no detections."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")

    tag_groups = []
    config = _make_config(tmp_path, tag_groups, unclassified_enabled=False)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result([], COCO_NAMES)]

    with caplog.at_level(logging.DEBUG, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    debug_logs = [r.message for r in caplog.records if "detected" in r.message and r.levelno == logging.DEBUG]
    assert debug_logs, "Expected a DEBUG 'detected' log even for empty detections"
    log = debug_logs[0]
    assert "photo.jpg" in log
    assert "[]" in log


# ── AC7: single collision → exactly one renamed file, no second copy ──────────

def test_no_duplicate_rename_when_single_collision(tmp_path):
    """One source image + same-named file already at dest → exactly photo_1.jpg, no photo_2.jpg."""
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")

    dest = tmp_path / "out"
    dest.mkdir()
    # Pre-place a file with the same name at the destination to trigger a collision
    make_jpeg(dest / "photo.jpg")

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
        on_collision="rename",
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (dest / "photo_1.jpg").exists(), "Expected photo_1.jpg to be created for the renamed collision"
    assert not (dest / "photo_2.jpg").exists(), "photo_2.jpg must not exist — source was processed only once"


# ── AC5: discovery / model-loading INFO logged before first "Processing batch" ─

def test_discovery_info_logged_before_processing_batch(tmp_path, caplog):
    """An INFO record mentioning discovery or model loading precedes 'Processing batch'."""
    import logging
    from imagesorter.config import TagGroup
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = _make_config(tmp_path, tag_groups)

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    records = caplog.records
    batch_indices = [i for i, r in enumerate(records) if "Processing batch" in r.message]
    assert batch_indices, "Expected at least one 'Processing batch' log record"
    first_batch_idx = batch_indices[0]

    discovery_before_batch = [
        r for r in records[:first_batch_idx]
        if r.levelno == logging.INFO and (
            "Discovering" in r.message
            or "Found" in r.message
            or "Loading YOLO" in r.message
            or "Model ready" in r.message
        )
    ]
    assert discovery_before_batch, (
        "Expected at least one INFO record mentioning discovery or model loading "
        "before the first 'Processing batch' record"
    )


# ── AC8: duplicate include_formats entry → image processed exactly once ───────

def test_duplicate_include_formats_processes_image_once(tmp_path, caplog):
    """include_formats=['.jpg', '.jpg'] must not cause the same image to be processed twice."""
    import logging
    from imagesorter.config import Config, TagGroup, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")
    dest = tmp_path / "out"

    tag_groups = [
        TagGroup(name="All", tags=["person"], destination=str(dest),
                 group_by_year=False, group_by_month=False),
    ]
    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg", ".jpg"],  # duplicate entry
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=tag_groups,
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result(["person"], COCO_NAMES)]

    with caplog.at_level(logging.INFO, logger="imagesorter.sorter"):
        with patch("imagesorter.sorter.YOLO", return_value=mock_model):
            run(config)

    # Image must be moved exactly once — it should exist at dest and not at source
    assert (dest / "photo.jpg").exists(), "Image should have been moved to destination"
    assert not img.exists(), "Source image should no longer exist (moved, not duplicated)"

    # Summary must show total=1 (processed once, not twice)
    summary_msgs = [r.message for r in caplog.records if "Run summary" in r.message]
    assert summary_msgs, "Expected a run summary log"
    summary = summary_msgs[-1]
    assert "total=1" in summary, f"Expected total=1 in summary (processed once), got: {summary}"


# ── AC2: destination/folder_name != source → no startup SystemExit ────────────

def test_unclassified_dest_with_subfolder_does_not_raise(tmp_path):
    """destination=src and folder_name='others' → full path src/others != src → no startup error."""
    from imagesorter.config import Config, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
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
            destination=str(src),  # destination == source, but folder_name adds a subdirectory
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = []

    # Must NOT raise SystemExit at startup validation
    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        try:
            run(config)
        except SystemExit as exc:
            # Only fail if it's the startup validation error, not some later error
            msg = str(exc)
            if "unclassified.destination" in msg and "source_folder" in msg:
                raise AssertionError(
                    f"Unexpected startup validation SystemExit: {msg}"
                ) from exc


# ── AC3 (rescan fix): unclassified dest inside source is not re-processed ─────

def test_unclassified_inside_source_not_reprocessed(tmp_path):
    """With recursive=True and unclassified dest inside source, image is processed exactly once."""
    from imagesorter.config import Config, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()
    img = make_jpeg(src / "photo.jpg")

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=True,
            folder_name="others",
            destination=str(src),  # destination == source; full path = source/others
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    mock_model = MagicMock()
    mock_model.names = COCO_NAMES
    mock_model.return_value = [_make_yolo_result([], COCO_NAMES)]

    with patch("imagesorter.sorter.YOLO", return_value=mock_model):
        run(config)

    assert (src / "others" / "photo.jpg").exists(), "Image should be in source/others/"
    assert not (src / "photo.jpg").exists(), "Original image should be moved, not copied"
    assert not (src / "others" / "photo_1.jpg").exists(), "Image must not be re-processed"
    assert not (src / "others" / "photo_2.jpg").exists(), "Image must not be re-processed multiple times"


# ── AC3: destination/folder_name == source (empty folder_name) → SystemExit ──

def test_unclassified_empty_folder_name_raises_system_exit(tmp_path):
    """destination=src and folder_name='' → full path src == source → startup error."""
    from imagesorter.config import Config, Unclassified
    from imagesorter.sorter import run

    src = tmp_path / "src"
    src.mkdir()

    config = Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=True,
            folder_name="",  # empty → destination / "" resolves to destination itself
            destination=str(src),
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
    )

    with pytest.raises(SystemExit):
        run(config)
