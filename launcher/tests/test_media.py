"""Tests for the server media-library index & incremental build (launcher/media.py)."""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

from PIL import Image

from launcher.media import (
    MediaIndexer,
    _image_metadata,
    generate_preview,
    generate_thumbnail,
    make_thumbnail_hook,
)


def make_oriented_image(path: Path, size=(400, 300), orientation=6) -> None:
    """Write a landscape JPEG tagged with an EXIF [orientation] (default 6 =
    ROTATE_90), so its *display* orientation differs from its stored pixels."""
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", size, (200, 30, 30))
    exif = img.getexif()
    exif[274] = orientation
    img.save(path, format="JPEG", exif=exif.tobytes())


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_image(path: Path, size=(8, 6), color=(120, 30, 200)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)


def make_indexer(tmp_path: Path, roots) -> MediaIndexer:
    return MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(r) for r in roots],
    )


# ---------------------------------------------------------------------------
# Build walks roots and inserts rows
# ---------------------------------------------------------------------------

def test_build_inserts_a_row_per_allowed_file_with_metadata(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg", size=(10, 4))
    make_image(root / "sub" / "b.png", size=(3, 7))
    (root / "ignore.txt").write_text("nope", encoding="utf-8")

    indexer = make_indexer(tmp_path, [root])
    indexer.build()

    rows = indexer.list_all()
    by_name = {Path(r["path"]).name: r for r in rows}
    assert set(by_name) == {"a.jpg", "b.png"}

    a = by_name["a.jpg"]
    assert a["kind"] == "image"
    assert a["root"] == str(root)
    assert a["size"] > 0
    assert a["mtime"] > 0
    assert a["width"] == 10 and a["height"] == 4
    # date_taken is a parseable ISO string (mtime fallback when no EXIF).
    datetime.fromisoformat(a["date_taken"])

    b = by_name["b.png"]
    assert b["width"] == 3 and b["height"] == 7


def test_build_uses_exif_datetimeoriginal_when_present(tmp_path):
    root = tmp_path / "lib"
    root.mkdir(parents=True, exist_ok=True)
    path = root / "exif.jpg"
    img = Image.new("RGB", (4, 4), (10, 20, 30))
    exif = img.getexif()
    exif[0x9003] = "2019:07:15 13:45:00"  # DateTimeOriginal
    img.save(path, exif=exif)

    indexer = make_indexer(tmp_path, [root])
    indexer.build()

    row = indexer.list_all()[0]
    assert row["date_taken"] == datetime(2019, 7, 15, 13, 45, 0).isoformat()


# ---------------------------------------------------------------------------
# Incremental: skip unchanged, reindex changed, prune vanished
# ---------------------------------------------------------------------------

def test_rebuild_skips_unchanged_files(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg")

    thumb_calls: list[int] = []

    def thumbnail(row_id, path, kind):
        thumb_calls.append(row_id)
        return True

    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        thumbnail=thumbnail,
    )
    indexer.build()
    first_id = indexer.list_all()[0]["id"]
    assert thumb_calls == [first_id]

    # Re-run: nothing changed on disk -> no re-thumbnail, same id, no churn.
    indexer.build()
    assert thumb_calls == [first_id]
    rows = indexer.list_all()
    assert len(rows) == 1 and rows[0]["id"] == first_id


def test_rebuild_reindexes_changed_file(tmp_path):
    root = tmp_path / "lib"
    img = root / "a.jpg"
    make_image(img, size=(8, 8))

    indexer = make_indexer(tmp_path, [root])
    indexer.build()
    before = indexer.list_all()[0]

    # Change the file (new size + bumped mtime).
    make_image(img, size=(20, 20))
    import os as _os
    new_mtime = before["mtime"] + 100
    _os.utime(img, (new_mtime, new_mtime))

    indexer.build()
    after = indexer.list_all()[0]
    assert after["width"] == 20 and after["height"] == 20
    assert after["mtime"] != before["mtime"]


def test_rebuild_prunes_vanished_files_and_their_cache(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    make_image(root / "b.jpg")

    thumbs = tmp_path / "thumbs"
    proxies = tmp_path / "proxies"
    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=thumbs,
        proxies_dir=proxies,
        folders=[str(root)],
    )
    indexer.build()
    rows = {Path(r["path"]).name: r for r in indexer.list_all()}
    gone_id = rows["b.jpg"]["id"]

    # Simulate cached artifacts for the file we are about to delete.
    thumbs.mkdir(parents=True, exist_ok=True)
    proxies.mkdir(parents=True, exist_ok=True)
    (thumbs / f"{gone_id}.jpg").write_bytes(b"x")
    (proxies / f"{gone_id}.mp4").write_bytes(b"y")

    (root / "b.jpg").unlink()
    indexer.build()

    remaining = {Path(r["path"]).name for r in indexer.list_all()}
    assert remaining == {"a.jpg"}
    assert not (thumbs / f"{gone_id}.jpg").exists()
    assert not (proxies / f"{gone_id}.mp4").exists()


# ---------------------------------------------------------------------------
# Single-item delete (drops the row and its cached artifacts)
# ---------------------------------------------------------------------------

def test_delete_removes_row_and_cached_artifacts(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    make_image(root / "b.jpg")

    thumbs = tmp_path / "thumbs"
    proxies = tmp_path / "proxies"
    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=thumbs,
        proxies_dir=proxies,
        folders=[str(root)],
    )
    indexer.build()
    rows = {Path(r["path"]).name: r for r in indexer.list_all()}
    gone_id = rows["b.jpg"]["id"]

    # Simulate the cached artifacts for the item we are about to delete.
    thumbs.mkdir(parents=True, exist_ok=True)
    proxies.mkdir(parents=True, exist_ok=True)
    (thumbs / f"{gone_id}.jpg").write_bytes(b"x")
    (proxies / f"{gone_id}.mp4").write_bytes(b"y")

    indexer.delete(gone_id)

    assert {Path(r["path"]).name for r in indexer.list_all()} == {"a.jpg"}
    assert not (thumbs / f"{gone_id}.jpg").exists()
    assert not (proxies / f"{gone_id}.mp4").exists()


def test_delete_unknown_id_is_a_noop(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    indexer = make_indexer(tmp_path, [root])
    indexer.build()

    indexer.delete(999999)  # must not raise
    assert len(indexer.list_all()) == 1


# ---------------------------------------------------------------------------
# Profile: which sync profile placed the file (for filtering)
# ---------------------------------------------------------------------------

def test_build_records_profile_from_the_injected_lookup(tmp_path):
    """The build stamps each row with the profile the lookup reports; files the
    lookup does not know (e.g. not phone-synced) stay NULL."""
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    make_image(root / "b.jpg")

    def profile_for(path):
        return "alice" if Path(path).name == "a.jpg" else None

    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        profile_for=profile_for,
    )
    indexer.build()

    by_name = {Path(r["path"]).name: r for r in indexer.list_all()}
    assert by_name["a.jpg"]["profile"] == "alice"
    assert by_name["b.jpg"]["profile"] is None


def test_build_defaults_profile_to_null_without_a_lookup(tmp_path):
    """Without an injected lookup the column is present and NULL — the CLI/no-sync
    case is unaffected."""
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    indexer = make_indexer(tmp_path, [root])
    indexer.build()
    assert indexer.list_all()[0]["profile"] is None


def test_timeline_filters_by_profile(tmp_path):
    """timeline(profile=...) returns only rows stamped with that profile."""
    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    make_image(root / "b.jpg")
    owners = {"a.jpg": "alice", "b.jpg": "bob"}

    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        profile_for=lambda path: owners[Path(path).name],
    )
    indexer.build()

    names = [Path(r["path"]).name for r in indexer.timeline(profile="alice")]
    assert names == ["a.jpg"]


def test_existing_db_without_profile_column_is_migrated(tmp_path):
    """A media.db created before the profile column gains it on open, and the
    next build populates it."""
    import sqlite3
    db = tmp_path / "media.db"
    con = sqlite3.connect(str(db))
    con.executescript(
        "CREATE TABLE media ("
        " id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL, root TEXT NOT NULL,"
        " kind TEXT NOT NULL, size INTEGER NOT NULL, mtime REAL NOT NULL,"
        " date_taken TEXT NOT NULL, width INTEGER, height INTEGER,"
        " video_websafe INTEGER, thumb_ready INTEGER NOT NULL DEFAULT 0);"
    )
    con.commit()
    con.close()

    root = tmp_path / "lib"
    make_image(root / "a.jpg")
    indexer = MediaIndexer(
        db_path=db,
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        profile_for=lambda path: "alice",
    )
    indexer.build()

    assert indexer.list_all()[0]["profile"] == "alice"


# ---------------------------------------------------------------------------
# Robustness: missing roots & per-file errors
# ---------------------------------------------------------------------------

def test_missing_root_is_skipped_and_surfaced(tmp_path):
    present = tmp_path / "lib"
    make_image(present / "a.jpg")
    missing = tmp_path / "not_mounted"

    indexer = make_indexer(tmp_path, [present, missing])
    status = indexer.build()

    assert str(missing) in status["skipped_roots"]
    assert {Path(r["path"]).name for r in indexer.list_all()} == {"a.jpg"}


def test_per_file_error_does_not_abort_build(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "good.jpg")
    # An unreadable "image": wrong bytes for the extension.
    (root / "broken.jpg").write_bytes(b"not an image")

    indexer = make_indexer(tmp_path, [root])
    indexer.build()

    names = {Path(r["path"]).name for r in indexer.list_all()}
    # The good file is indexed; the broken one does not abort the build (it is
    # still indexed with mtime-fallback metadata since stat succeeds).
    assert "good.jpg" in names


# ---------------------------------------------------------------------------
# Single build lock
# ---------------------------------------------------------------------------

def test_concurrent_build_does_not_run_twice(tmp_path):
    import threading

    root = tmp_path / "lib"
    make_image(root / "a.jpg")

    started = threading.Event()
    release = threading.Event()
    thumb_calls: list[int] = []

    def slow_thumbnail(row_id, path, kind):
        thumb_calls.append(row_id)
        started.set()
        release.wait(2)
        return False

    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        thumbnail=slow_thumbnail,
    )

    t = threading.Thread(target=indexer.build)
    t.start()
    assert started.wait(2)

    # Second trigger while the first is mid-build: must not start a second build;
    # returns the running status snapshot.
    status = indexer.build()
    assert status["state"] == "building"

    release.set()
    t.join(2)

    # Exactly one build ran -> the thumbnail hook saw the file once.
    assert len(thumb_calls) == 1


# ---------------------------------------------------------------------------
# Thumbnails (~320px square JPEG)
# ---------------------------------------------------------------------------

def test_generate_thumbnail_writes_320px_square_jpeg(tmp_path):
    src = tmp_path / "wide.jpg"
    make_image(src, size=(800, 400), color=(10, 200, 50))
    dest = tmp_path / "out" / "1.jpg"

    assert generate_thumbnail(src, "image", dest) is True
    assert dest.exists()
    with Image.open(dest) as img:
        assert img.format == "JPEG"
        assert img.size == (320, 320)


def test_preview_applies_exif_orientation(tmp_path):
    # A portrait photo stored as landscape pixels + a rotate tag (common off-Pixel)
    # must be served upright: the preview's long edge becomes its height, not width.
    src = tmp_path / "rotated.jpg"
    make_oriented_image(src, size=(400, 300), orientation=6)
    dest = tmp_path / "preview.jpg"

    assert generate_preview(src, dest) is True
    with Image.open(dest) as img:
        assert img.height > img.width


def test_thumbnail_applies_exif_orientation(tmp_path):
    # The square thumb is center-cropped after the rotation is applied, so the
    # subject is upright in the tile rather than lying on its side.
    src = tmp_path / "rotated.jpg"
    make_oriented_image(src, size=(400, 300), orientation=6)
    dest = tmp_path / "thumb.jpg"

    assert generate_thumbnail(src, "image", dest) is True
    with Image.open(dest) as img:
        assert img.size == (320, 320)


def test_metadata_reports_display_dimensions_for_oriented_image(tmp_path):
    # The recorded width/height reflect the display orientation, so a rotate-tagged
    # landscape file is indexed as the portrait it renders as.
    src = tmp_path / "rotated.jpg"
    make_oriented_image(src, size=(400, 300), orientation=6)

    width, height, _ = _image_metadata(src)
    assert (width, height) == (300, 400)


def test_build_writes_thumb_per_item_and_marks_ready(tmp_path):
    root = tmp_path / "lib"
    make_image(root / "a.jpg", size=(40, 40))
    make_image(root / "b.png", size=(60, 20))

    thumbs = tmp_path / "thumbs"
    indexer = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=thumbs,
        proxies_dir=tmp_path / "proxies",
        folders=[str(root)],
        thumbnail=make_thumbnail_hook(thumbs),
    )
    indexer.build()

    for row in indexer.list_all():
        assert row["thumb_ready"] == 1
        thumb = thumbs / f"{row['id']}.jpg"
        assert thumb.exists()
        with Image.open(thumb) as img:
            assert img.format == "JPEG"
            assert img.size == (320, 320)


# ---------------------------------------------------------------------------
# Timeline order: (date_taken DESC, id DESC), total & stable cursor
# ---------------------------------------------------------------------------

def test_timeline_orders_by_date_then_id_desc(tmp_path):
    indexer = make_indexer(tmp_path, [tmp_path / "lib"])

    # Insert rows directly with controlled date_taken; two share a date so the
    # id tiebreak (DESC) is exercised.
    indexer._upsert(str(tmp_path / "a"), str(tmp_path), "image", 1, 1.0,
                    "2024-01-01T00:00:00", 1, 1)
    indexer._upsert(str(tmp_path / "b"), str(tmp_path), "image", 1, 1.0,
                    "2024-03-01T00:00:00", 1, 1)
    same1 = indexer._upsert(str(tmp_path / "c"), str(tmp_path), "image", 1, 1.0,
                            "2024-02-01T00:00:00", 1, 1)
    same2 = indexer._upsert(str(tmp_path / "d"), str(tmp_path), "image", 1, 1.0,
                            "2024-02-01T00:00:00", 1, 1)

    items = indexer.timeline(limit=10)
    dates = [i["date_taken"] for i in items]
    assert dates == sorted(dates, reverse=True)
    # The two same-date rows are tie-broken by id DESC (later id first).
    pos = {i["id"]: n for n, i in enumerate(items)}
    assert pos[max(same1, same2)] < pos[min(same1, same2)]
