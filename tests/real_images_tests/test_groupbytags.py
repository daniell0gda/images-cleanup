"""Integration tests using real YOLO inference against real photos."""
import shutil
import pytest
from pathlib import Path

from imagesorter import sorter
from imagesorter.config import Config, TagGroup, Unclassified

pytestmark = pytest.mark.real

TEST_IMAGES_DIR = Path(__file__).parent.parent / "test_images"

NON_PERSON_IMAGES = ["non_person.jpg", "non_person_2.jpg"]
PERSON_IMAGES = [
    "DSCN8168.JPG", "DSCN8169_1.JPG", "DSCN8170.JPG",
    "DSCN8216.JPG", "DSCN8217.JPG", "DSCN8301.JPG",
    "DSCN8307.JPG", "DSCN8315.JPG", "hand.jpg",
]
ALL_IMAGES = NON_PERSON_IMAGES + PERSON_IMAGES


def copy_images(names: list[str], dest_dir: Path) -> None:
    """Copy named photos from TEST_IMAGES_DIR into dest_dir. Originals are never modified."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    for name in names:
        shutil.copy2(TEST_IMAGES_DIR / name, dest_dir / name)


def _make_config(
    src: Path,
    person_dest: Path,
    uncl_dest: Path,
    *,
    copy_instead_of_move: bool = False,
    recursive: bool = False,
    on_collision: str = "rename",
) -> Config:
    return Config(
        mode="GroupByTags",
        source_folder=str(src),
        recursive=recursive,
        copy_instead_of_move=copy_instead_of_move,
        include_formats=[".jpg", ".JPG"],
        threads=1,
        log_level="WARNING",
        log_file=None,
        tag_groups=[
            TagGroup(
                name="person",
                tags=["person"],
                destination=str(person_dest),
                group_by_year=False,
                group_by_month=False,
            )
        ],
        unclassified=Unclassified(
            enabled=True,
            folder_name="others",
            destination=str(uncl_dest),
            group_by_year=False,
            group_by_month=False,
        ),
        similarity_threshold=0.96,
        batch_size=4,
        confidence_threshold=0.5,
        on_collision=on_collision,
        max_image_dimension=1920,
    )


@pytest.mark.real
def test_non_person_images_go_to_unclassified(tmp_path):
    src = tmp_path / "src"
    copy_images(NON_PERSON_IMAGES, src)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"

    config = _make_config(src, person_dest, uncl_dest, copy_instead_of_move=False)
    sorter.run(config)

    assert (uncl_dest / "others" / "non_person.jpg").exists()
    assert (uncl_dest / "others" / "non_person_2.jpg").exists()

    jpg_files_in_person = list(person_dest.rglob("*.jpg")) + list(person_dest.rglob("*.JPG"))
    assert jpg_files_in_person == [], (
        f"Non-person images must not be in person_dest; found: {jpg_files_in_person}"
    )


@pytest.mark.real
def test_move_mode_removes_originals(tmp_path):
    src = tmp_path / "src"
    copy_images(ALL_IMAGES, src)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"

    config = _make_config(src, person_dest, uncl_dest, copy_instead_of_move=False)
    sorter.run(config)

    remaining = list(src.rglob("*.jpg")) + list(src.rglob("*.JPG"))
    assert remaining == [], f"All images should be moved from src; remaining: {remaining}"

    assert (uncl_dest / "others" / "non_person.jpg").exists()
    assert (uncl_dest / "others" / "non_person_2.jpg").exists()

    person_files = list(person_dest.rglob("*.jpg")) + list(person_dest.rglob("*.JPG"))
    assert person_files, "Person destination must have at least one image (YOLO detected people)"


@pytest.mark.real
def test_copy_mode_preserves_originals(tmp_path):
    src = tmp_path / "src"
    copy_images(NON_PERSON_IMAGES, src)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"

    config = _make_config(src, person_dest, uncl_dest, copy_instead_of_move=True)
    sorter.run(config)

    assert (src / "non_person.jpg").exists(), "Original must remain in src after copy mode"
    assert (src / "non_person_2.jpg").exists(), "Original must remain in src after copy mode"

    assert (uncl_dest / "others" / "non_person.jpg").exists()
    assert (uncl_dest / "others" / "non_person_2.jpg").exists()


@pytest.mark.real
def test_recursive_mode(tmp_path):
    src = tmp_path / "src"
    subdir = src / "subdir"
    copy_images(["non_person.jpg"], subdir)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"

    config = _make_config(src, person_dest, uncl_dest, recursive=True)
    sorter.run(config)

    assert (uncl_dest / "others" / "non_person.jpg").exists(), (
        "non_person.jpg in subdirectory must be found and routed to unclassified when recursive=True"
    )


@pytest.mark.real
def test_on_collision_skip(tmp_path):
    src = tmp_path / "src"
    copy_images(["non_person.jpg"], src)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"
    others_dir = uncl_dest / "others"
    others_dir.mkdir(parents=True, exist_ok=True)

    sentinel_content = b"original sentinel content"
    dest_file = others_dir / "non_person.jpg"
    dest_file.write_bytes(sentinel_content)

    config = _make_config(src, person_dest, uncl_dest, on_collision="skip")
    sorter.run(config)

    assert (src / "non_person.jpg").exists(), (
        "Source must remain when on_collision=skip and destination already has the file"
    )
    assert dest_file.read_bytes() == sentinel_content, (
        "Destination file must be unchanged when on_collision=skip"
    )


@pytest.mark.real
def test_recursive_false_ignores_subdirectory_images(tmp_path):
    src = tmp_path / "src"
    subdir = src / "subdir"
    copy_images(["non_person.jpg"], subdir)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"

    config = _make_config(src, person_dest, uncl_dest, recursive=False)
    sorter.run(config)

    assert (subdir / "non_person.jpg").exists(), (
        "non_person.jpg in subdirectory must remain untouched when recursive=False"
    )
    uncl_others = uncl_dest / "others"
    moved_files = list(uncl_others.rglob("*")) if uncl_others.exists() else []
    assert moved_files == [], (
        f"Unclassified destination must be empty when recursive=False and src root has no images; found: {moved_files}"
    )


@pytest.mark.real
def test_on_collision_rename_creates_renamed_file(tmp_path):
    src = tmp_path / "src"
    copy_images(["non_person.jpg"], src)

    person_dest = tmp_path / "person_dest"
    uncl_dest = tmp_path / "uncl_dest"
    others_dir = uncl_dest / "others"
    others_dir.mkdir(parents=True, exist_ok=True)

    sentinel_content = b"sentinel file - must not be overwritten"
    dest_file = others_dir / "non_person.jpg"
    dest_file.write_bytes(sentinel_content)

    config = _make_config(src, person_dest, uncl_dest, on_collision="rename", copy_instead_of_move=False)
    sorter.run(config)

    assert not (src / "non_person.jpg").exists(), (
        "Source non_person.jpg must be removed after move with on_collision=rename"
    )
    assert dest_file.read_bytes() == sentinel_content, (
        "Pre-existing destination non_person.jpg must be untouched when on_collision=rename"
    )
    renamed_files = [f for f in others_dir.iterdir() if f.name != "non_person.jpg"]
    assert len(renamed_files) == 1, (
        f"Exactly one renamed file must exist in destination; found: {renamed_files}"
    )
    assert renamed_files[0].suffix.lower() == ".jpg", (
        f"Renamed file must have .jpg extension; got: {renamed_files[0].name}"
    )
