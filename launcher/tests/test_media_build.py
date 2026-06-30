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
