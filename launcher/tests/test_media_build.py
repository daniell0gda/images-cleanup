"""Tests for the build status + force-refresh routes (Cluster 6).

Covers:
- ``GET /api/media/build/status`` snapshot shape (idle vs building) with
  ``processed``/``total``/``added``/``removed``/``skipped_roots``/``last_built``/
  ``last_count``.
- ``POST /api/media/build`` runs the build in a background daemon thread and
  returns immediately; a second POST while a build is in flight returns the busy
  status without starting a second build (single in-process lock).
- The scheduled media cron and ``/api/settings``'s ``media_build`` field reflect
  the real indexer snapshot after a build.

The background-build test is made deterministic by injecting a build function
that blocks on an Event, so timing is controlled instead of slept on.
"""
from __future__ import annotations

import os
import threading
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image


def _make_image(path: Path, size=(20, 20), color=(10, 120, 200)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(path)


def _make_corrupt_jpeg(path: Path) -> None:
    """Write a JPEG SOI marker followed by garbage so PIL raises on decode."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 64)


def _build_app(tmp_path: Path, folders, sync_scheduler=None):
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    os.environ["MEDIA_DB"] = str(tmp_path / "media.db")
    os.environ["MEDIA_THUMBS_DIR"] = str(tmp_path / "thumbs")
    os.environ["MEDIA_PROXIES_DIR"] = str(tmp_path / "proxies")
    os.environ["MEDIA_PREVIEWS_DIR"] = str(tmp_path / "previews")
    (configs / "server.yaml").write_text(
        yaml.safe_dump({"media_library": {"enabled": True,
                                          "folders": [str(f) for f in folders],
                                          "schedule": "0 2 * * *"}}),
        encoding="utf-8",
    )
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=lambda p: set(),
                          sync_scheduler=sync_scheduler)


_STATUS_FIELDS = {
    "state", "processed", "total", "added", "removed",
    "skipped_roots", "last_built", "last_count",
}


# ---------------------------------------------------------------------------
# GET /api/media/build/status
# ---------------------------------------------------------------------------

def test_build_status_idle_shape(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    client = TestClient(app)

    body = client.get("/api/media/build/status").json()
    assert set(body) == _STATUS_FIELDS
    assert body["state"] == "idle"
    assert body["processed"] == 0
    assert body["added"] == 0
    assert body["removed"] == 0
    assert body["skipped_roots"] == []
    assert body["last_built"] is None
    assert body["last_count"] is None


def test_build_status_reports_last_built_after_build(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    _make_image(root / "b.jpg")
    app = _build_app(tmp_path, [root])
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    app.state.media_indexer.build()
    client = TestClient(app)

    body = client.get("/api/media/build/status").json()
    assert body["state"] == "idle"
    assert body["last_built"] is not None
    assert body["last_count"] == 2
    assert body["added"] == 2


# ---------------------------------------------------------------------------
# POST /api/media/build — background thread + single-build lock
# ---------------------------------------------------------------------------

def test_force_build_runs_in_background_and_lock_prevents_second(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    indexer = app.state.media_indexer

    # Replace the indexer's build with a deterministic blocking stand-in that
    # respects the same single-build lock the real build uses.
    started = threading.Event()
    release = threading.Event()
    calls = []

    real_build = indexer.build

    def fake_run():
        calls.append(1)
        started.set()
        release.wait(2.0)

    # Drive the lock + status transitions through the public build() by swapping
    # the internal build body. Keep the single-lock semantics intact.
    def patched_build(force=False):
        if not indexer._build_lock.acquire(blocking=False):
            return indexer._status.snapshot()
        try:
            indexer._status = indexer._status.__class__(state="building")
            fake_run()
            indexer._status.state = "idle"
            return indexer._status.snapshot()
        finally:
            indexer._build_lock.release()

    indexer.build = patched_build
    client = TestClient(app)

    first = client.post("/api/media/build")
    assert first.status_code == 200
    assert started.wait(2.0), "background build did not start"
    assert first.json()["state"] == "building"

    # A concurrent POST while building returns busy and does NOT invoke a 2nd build.
    second = client.post("/api/media/build")
    assert second.json()["state"] == "building"
    assert len(calls) == 1, "a second build was started despite the lock"

    release.set()
    indexer.build = real_build  # restore so cleanup is clean


def test_force_build_populates_status_after_completion(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    _make_image(root / "b.jpg")
    app = _build_app(tmp_path, [root])
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    client = TestClient(app)

    client.post("/api/media/build")
    # Wait for the background thread to finish via the status endpoint.
    deadline = threading.Event()
    for _ in range(200):
        body = client.get("/api/media/build/status").json()
        if body["state"] == "idle" and body["last_built"] is not None:
            break
        deadline.wait(0.02)
    body = client.get("/api/media/build/status").json()
    assert body["state"] == "idle"
    assert body["last_built"] is not None
    assert body["last_count"] == 2


# ---------------------------------------------------------------------------
# /api/settings.media_build reflects the real snapshot after a build
# ---------------------------------------------------------------------------

def test_settings_media_build_null_before_then_real_after(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    client = TestClient(app)

    # Backward-compatible: null before any build has run.
    assert client.get("/api/settings").json()["media_build"] is None

    app.state.media_indexer.build()
    # Status endpoint sets app.state.media_build_status the settings payload reads.
    client.get("/api/media/build/status")
    mb = client.get("/api/settings").json()["media_build"]
    assert mb is not None
    assert mb["last_count"] == 1
    assert mb["last_built"] is not None


# ---------------------------------------------------------------------------
# Corrupt/truncated image does not abort the build
# ---------------------------------------------------------------------------

def test_corrupt_jpeg_does_not_abort_build_and_valid_row_present(tmp_path):
    root = tmp_path / "lib"
    _make_corrupt_jpeg(root / "broken.jpg")
    _make_image(root / "good.jpg")
    app = _build_app(tmp_path, [root])
    indexer = app.state.media_indexer

    # Build must not raise even though one image is unreadable.
    snap = indexer.build()
    assert snap["state"] == "idle"

    rows = {Path(r["path"]).name: r for r in indexer.list_all()}
    assert "good.jpg" in rows, "valid image was not indexed"
    good = rows["good.jpg"]
    assert good["width"] == 20 and good["height"] == 20


def test_corrupt_jpeg_recorded_with_null_dims_and_mtime_date(tmp_path):
    root = tmp_path / "lib"
    corrupt = root / "broken.jpg"
    _make_corrupt_jpeg(corrupt)
    app = _build_app(tmp_path, [root])
    indexer = app.state.media_indexer

    indexer.build()

    rows = {Path(r["path"]).name: r for r in indexer.list_all()}
    assert "broken.jpg" in rows, "corrupt image was not recorded"
    broken = rows["broken.jpg"]
    assert broken["width"] is None
    assert broken["height"] is None
    import launcher.media as media
    assert broken["date_taken"] == media._mtime_iso(corrupt.stat().st_mtime)


# ---------------------------------------------------------------------------
# Scheduled media cron runs the real build
# ---------------------------------------------------------------------------

def test_media_cron_runs_real_build(tmp_path):
    def scheduler(interval, fn):
        pass

    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    app = _build_app(tmp_path, [root], sync_scheduler=scheduler)
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False

    # The cron callable is the real build, not a placeholder no-op.
    app.state.media_cron._fn()
    assert app.state.media_indexer.list_all()
    snap = app.state.media_indexer.status()
    assert snap["last_count"] == 1


# ---------------------------------------------------------------------------
# Single-file incremental indexing (index_path)
# ---------------------------------------------------------------------------

def _make_indexer(tmp_path: Path, roots):
    from launcher.media import MediaIndexer

    return MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(r) for r in roots],
    )


def test_index_path_indexes_single_file_without_full_walk(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    # A second file exists but is never handed to index_path.
    _make_image(root / "b.jpg")

    indexer = _make_indexer(tmp_path, [root])
    indexer.index_path(root / "a.jpg")

    assert {Path(r["path"]).name for r in indexer.list_all()} == {"a.jpg"}
    assert {Path(r["path"]).name for r in indexer.timeline()} == {"a.jpg"}


def test_index_path_sets_root_to_containing_configured_folder(tmp_path):
    root_a = tmp_path / "one"
    root_b = tmp_path / "two"
    _make_image(root_a / "x.jpg")
    _make_image(root_b / "nested" / "y.jpg")

    indexer = _make_indexer(tmp_path, [root_a, root_b])
    indexer.index_path(root_b / "nested" / "y.jpg")

    row = indexer.list_all()[0]
    assert row["root"] == str(root_b)


def test_index_path_upserts_on_repeat_without_duplicate_row(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")

    indexer = _make_indexer(tmp_path, [root])
    first_id = indexer.index_path(root / "a.jpg")
    second_id = indexer.index_path(root / "a.jpg")

    rows = indexer.list_all()
    assert len(rows) == 1
    assert first_id == second_id == rows[0]["id"]


def test_full_build_after_incremental_leaves_exactly_one_row(tmp_path):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")

    indexer = _make_indexer(tmp_path, [root])
    indexer.index_path(root / "a.jpg")
    indexer.build(force=True)

    rows = [r for r in indexer.list_all() if Path(r["path"]).name == "a.jpg"]
    assert len(rows) == 1


def test_timeline_without_before_is_newest_first(tmp_path):
    root = tmp_path / "lib"
    for name in ("a.jpg", "b.jpg", "c.jpg"):
        _make_image(root / name)

    indexer = _make_indexer(tmp_path, [root])
    for name in ("a.jpg", "b.jpg", "c.jpg"):
        indexer.index_path(root / name)

    # Assign known capture dates; c shares b's date to exercise the id tiebreak.
    ids = {Path(r["path"]).name: r["id"] for r in indexer.list_all()}
    conn = indexer._conn()
    conn.execute("UPDATE media SET date_taken=? WHERE id=?",
                 ("2024-01-01T00:00:00+00:00", ids["a.jpg"]))
    conn.execute("UPDATE media SET date_taken=? WHERE id=?",
                 ("2024-03-01T00:00:00+00:00", ids["b.jpg"]))
    conn.execute("UPDATE media SET date_taken=? WHERE id=?",
                 ("2024-03-01T00:00:00+00:00", ids["c.jpg"]))
    conn.commit()

    ordered = [Path(r["path"]).name for r in indexer.timeline()]
    # Newest date first; equal dates tiebreak by id DESC (c indexed after b).
    assert ordered == ["c.jpg", "b.jpg", "a.jpg"]
