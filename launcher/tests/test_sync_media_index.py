"""Wiring of synced files into the media gallery index."""
from __future__ import annotations

from pathlib import Path
from launcher.tests._sync_helpers import (
    make_app,
    _write_e2e_config,
    _build_lane,
    _dated_jpeg_bytes,
    _media_indexer,
    _run_synced_session,
)


def test_process_session_indexes_synced_file_without_a_build(tmp_path):
    """A file placed by process_session is in the media index immediately: its
    timeline row is present with no build() call."""
    indexer, dest = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(), "2021-01-01T00:00:00"
    )

    rows = indexer.timeline()
    paths = [r["path"] for r in rows]
    placed = str(list((dest / "people").rglob("shot*.jpg"))[0])
    assert placed in paths


def test_synced_image_without_exif_uses_created_on_for_date_taken(tmp_path):
    """With no EXIF capture date, date_taken comes from the phone's created_on,
    not from the sync-time file mtime."""
    from datetime import datetime

    indexer, _ = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date=None), "2018-05-04T09:08:07"
    )

    row = indexer.timeline()[0]
    assert row["date_taken"] == datetime(2018, 5, 4, 9, 8, 7).isoformat()


def test_synced_image_with_exif_keeps_exif_date_over_created_on(tmp_path):
    """A present EXIF capture date wins: created_on does not override it."""
    from datetime import datetime

    indexer, _ = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date="2015:03:02 01:00:00"), "2021-01-01T00:00:00"
    )

    row = indexer.timeline()[0]
    assert row["date_taken"] == datetime(2015, 3, 2, 1, 0, 0).isoformat()


def test_synced_image_unparseable_created_on_falls_back_to_mtime(tmp_path):
    """A missing/garbage created_on indexes without error and date_taken falls
    back to the file mtime (never the 1970 epoch)."""
    from datetime import datetime

    indexer, dest = _run_synced_session(
        tmp_path, _dated_jpeg_bytes(exif_date=None), "not-a-date"
    )

    row = indexer.timeline()[0]
    placed = list((dest / "people").rglob("shot*.jpg"))[0]
    from launcher.media import _mtime_iso
    assert row["date_taken"] == _mtime_iso(placed.stat().st_mtime)
    assert not row["date_taken"].startswith("1970")


def test_synced_photo_lands_at_chronological_position_in_timeline(tmp_path):
    """A newly synced photo is ordered by its capture date relative to
    pre-existing media, not by sync time (which is 'now')."""
    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})
    indexer = _media_indexer(tmp_path, [dest])
    lane.media_indexer = indexer

    # Pre-existing media: one older (2010), one newer (2030) than the synced photo.
    people = dest / "people"
    (people).mkdir(parents=True, exist_ok=True)
    (people / "old.jpg").write_bytes(_dated_jpeg_bytes(exif_date="2010:01:01 00:00:00"))
    (people / "new.jpg").write_bytes(_dated_jpeg_bytes(exif_date="2030:01:01 00:00:00"))
    indexer.index_path(people / "old.jpg")
    indexer.index_path(people / "new.jpg")

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    data = _dated_jpeg_bytes(exif_date=None)
    sessions.write_chunk(
        sid, "f1", FileMeta("shot.jpg", "2020-06-15T12:00:00", len(data), "image/jpeg"), 0, data
    )
    sessions.complete(sid)
    lane.process_session(sid)

    names = [Path(r["path"]).name for r in indexer.timeline()]
    # Newest-first: 2030, then the 2020 synced photo, then 2010.
    assert names.index("new.jpg") < names.index("shot.jpg") < names.index("old.jpg")
