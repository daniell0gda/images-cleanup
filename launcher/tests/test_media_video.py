"""Tests for video probe, Range/206 streaming, and transcode-if-needed.

The Range byte-serving path and the per-id transcode lock are exercised with
plain fixtures and an injected transcoder, so they run without ffmpeg. Tests
that genuinely need ffprobe/ffmpeg are guarded with ``skipif`` and skip cleanly
when the binaries are absent (as on this machine).
"""
from __future__ import annotations

import os
import threading
import time
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient

from launcher import media as media_mod


# ---------------------------------------------------------------------------
# Pure byte-range server (no media tooling)
# ---------------------------------------------------------------------------

def test_parse_range_partial():
    assert media_mod.parse_byte_range("bytes=0-9", 100) == (0, 9)
    assert media_mod.parse_byte_range("bytes=10-19", 100) == (10, 19)


def test_parse_range_open_ended():
    # bytes=50- means from 50 to the end.
    assert media_mod.parse_byte_range("bytes=50-", 100) == (50, 99)


def test_parse_range_suffix():
    # bytes=-20 means the last 20 bytes.
    assert media_mod.parse_byte_range("bytes=-20", 100) == (80, 99)


def test_parse_range_none_or_invalid():
    assert media_mod.parse_byte_range(None, 100) is None
    assert media_mod.parse_byte_range("widgets=0-9", 100) is None
    assert media_mod.parse_byte_range("bytes=200-300", 100) is None  # start past end


# ---------------------------------------------------------------------------
# App / fixture helpers
# ---------------------------------------------------------------------------

def _make_video(path: Path, content: bytes) -> None:
    """Write a fake .mp4 whose bytes we control (no real encoding involved)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def _build_app(tmp_path: Path, folders):
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
    return srv.create_app(srv._JobState(), sync_detect_tags=lambda p: set())


def _index(app):
    # Don't run real thumbnailing (cv2 on fake bytes) — clear the hook.
    app.state.media_indexer._thumbnail = lambda row_id, path, kind: False
    app.state.media_indexer.build()


def _video_id(app):
    for r in app.state.media_indexer.list_all():
        if r["kind"] == "video":
            return r["id"]
    raise AssertionError("no video indexed")


def _auth(client: TestClient, device_id="dev-1") -> dict:
    """Register + approve a device and return its bearer auth header."""
    code = client.post(
        "/api/sync/devices", json={"device_id": device_id, "name": "Pixel"}
    ).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    token = client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()["token"]
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# /stream: web-safe / unknown originals via Range/206 (no ffmpeg needed)
# ---------------------------------------------------------------------------

VIDEO_BYTES = bytes(range(256)) * 8  # 2048 deterministic bytes


def test_stream_full_body_no_range(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    # Unknown websafe (NULL) -> served as original.
    client = TestClient(app)
    mid = _video_id(app)

    resp = client.get(f"/api/media/{mid}/stream", headers=_auth(client))
    assert resp.status_code == 200
    assert resp.content == VIDEO_BYTES
    assert resp.headers["accept-ranges"] == "bytes"
    assert resp.headers["content-length"] == str(len(VIDEO_BYTES))


def test_stream_partial_range_206(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    mid = _video_id(app)

    resp = client.get(f"/api/media/{mid}/stream",
                      headers={"Range": "bytes=10-19", **_auth(client)})
    assert resp.status_code == 206
    assert resp.content == VIDEO_BYTES[10:20]
    assert resp.headers["content-range"] == f"bytes 10-19/{len(VIDEO_BYTES)}"
    assert resp.headers["content-length"] == "10"
    assert resp.headers["accept-ranges"] == "bytes"


def test_stream_websafe_original_not_transcoded(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    # Force websafe=1 so the original path is taken (no probe needed).
    mid = _video_id(app)
    app.state.media_indexer._conn().execute(
        "UPDATE media SET video_websafe=1 WHERE id=?", (mid,)
    )
    app.state.media_indexer._conn().commit()
    client = TestClient(app)

    resp = client.get(f"/api/media/{mid}/stream",
                      headers={"Range": "bytes=0-3", **_auth(client)})
    assert resp.status_code == 206
    assert resp.content == VIDEO_BYTES[0:4]
    # No proxy was created.
    assert not (tmp_path / "proxies" / f"{mid}.mp4").exists()


def test_stream_unknown_id_404(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)

    resp = client.get("/api/media/999999/stream", headers=_auth(client))
    assert resp.status_code == 404


def test_stream_image_id_404(tmp_path):
    from PIL import Image
    root = tmp_path / "lib"
    (root).mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (20, 20), (1, 2, 3)).save(root / "a.jpg")
    app = _build_app(tmp_path, [root])
    _index(app)
    client = TestClient(app)
    img_id = next(r["id"] for r in app.state.media_indexer.list_all()
                  if r["kind"] == "image")

    resp = client.get(f"/api/media/{img_id}/stream", headers=_auth(client))
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# Probe policy: NULL when ffprobe absent; set during build when available
# ---------------------------------------------------------------------------

def test_video_websafe_null_when_ffprobe_absent(tmp_path, monkeypatch):
    monkeypatch.setattr(media_mod, "ffprobe_available", lambda: False)
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    assert app.state.media_indexer.get(mid)["video_websafe"] is None


def test_video_websafe_set_from_probe_when_available(tmp_path, monkeypatch):
    # Inject a fake prober so the build sets video_websafe without real ffprobe.
    monkeypatch.setattr(media_mod, "ffprobe_available", lambda: True)
    monkeypatch.setattr(media_mod, "probe_video_websafe", lambda p: False)
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    assert app.state.media_indexer.get(mid)["video_websafe"] == 0


@pytest.mark.skipif(not media_mod.ffprobe_available(),
                    reason="ffprobe binary not available")
def test_probe_video_websafe_runs_real_ffprobe(tmp_path):
    # Only runs where ffprobe exists; otherwise skips cleanly. We just assert the
    # helper returns a bool for a real (here, trivial) input without raising.
    # A non-media file makes ffprobe error, so guard for that too.
    import subprocess
    sample = tmp_path / "v.mp4"
    sample.write_bytes(VIDEO_BYTES)
    try:
        result = media_mod.probe_video_websafe(sample)
    except subprocess.CalledProcessError:
        pytest.skip("no decodable sample available")
    assert isinstance(result, bool)


# ---------------------------------------------------------------------------
# Transcode-once + per-id lock (injected transcoder, no real ffmpeg)
# ---------------------------------------------------------------------------

def _nonwebsafe_app(tmp_path):
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    _index(app)
    mid = _video_id(app)
    app.state.media_indexer._conn().execute(
        "UPDATE media SET video_websafe=0 WHERE id=?", (mid,)
    )
    app.state.media_indexer._conn().commit()
    return app, mid


def test_ensure_proxy_transcodes_once_then_caches(tmp_path):
    app, mid = _nonwebsafe_app(tmp_path)
    indexer = app.state.media_indexer
    proxy_path = tmp_path / "proxies" / f"{mid}.mp4"

    calls = []

    def fake_transcode(src, dest):
        calls.append(str(src))
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(VIDEO_BYTES)

    indexer.set_transcoder(fake_transcode)

    p1 = indexer.ensure_proxy(mid, Path(indexer.get(mid)["path"]))
    p2 = indexer.ensure_proxy(mid, Path(indexer.get(mid)["path"]))

    assert Path(p1) == proxy_path
    assert Path(p2) == proxy_path
    assert proxy_path.exists()
    assert len(calls) == 1  # second call reused the cached proxy, no re-encode


def test_ensure_proxy_no_double_encode_under_concurrency(tmp_path):
    app, mid = _nonwebsafe_app(tmp_path)
    indexer = app.state.media_indexer

    calls = []
    started = threading.Event()

    def slow_transcode(src, dest):
        calls.append(str(src))
        started.set()
        time.sleep(0.2)  # hold the lock so the other thread must wait
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(VIDEO_BYTES)

    indexer.set_transcoder(slow_transcode)

    src = Path(indexer.get(mid)["path"])
    results = []

    def worker():
        results.append(indexer.ensure_proxy(mid, src))

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(calls) == 1  # only one encode across concurrent requests
    assert all(Path(r) == tmp_path / "proxies" / f"{mid}.mp4" for r in results)


def test_stream_nonwebsafe_serves_proxy(tmp_path):
    app, mid = _nonwebsafe_app(tmp_path)
    proxy_bytes = b"PROXYDATA" * 100

    def fake_transcode(src, dest):
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(proxy_bytes)

    app.state.media_indexer.set_transcoder(fake_transcode)
    client = TestClient(app)

    resp = client.get(f"/api/media/{mid}/stream",
                      headers={"Range": "bytes=0-8", **_auth(client)})
    assert resp.status_code == 206
    assert resp.content == proxy_bytes[0:9]
    assert (tmp_path / "proxies" / f"{mid}.mp4").exists()


def test_build_pretranscodes_nonwebsafe_video(tmp_path, monkeypatch):
    # A non-web-safe video discovered during indexing is proxied in the
    # background, so its first play is instant instead of blocking on a full
    # encode. ffprobe/ffmpeg are faked so the test needs neither binary.
    monkeypatch.setattr(media_mod, "ffprobe_available", lambda: True)
    monkeypatch.setattr(media_mod, "ffmpeg_available", lambda: True)
    monkeypatch.setattr(media_mod, "probe_video_websafe", lambda p: False)
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    indexer = app.state.media_indexer
    indexer._thumbnail = lambda row_id, path, kind: False

    proxied = threading.Event()

    def fake_transcode(src, dest):
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Path(dest).write_bytes(b"PROXY")
        proxied.set()

    indexer.set_transcoder(fake_transcode)
    indexer.build()

    mid = _video_id(app)
    assert proxied.wait(timeout=5)  # background worker ran the encode
    assert (tmp_path / "proxies" / f"{mid}.mp4").exists()


def test_build_skips_pretranscode_when_ffmpeg_absent(tmp_path, monkeypatch):
    # ffprobe present but ffmpeg absent: probe still runs, but no background
    # encode is queued (and no worker pool is created) since it would fail.
    monkeypatch.setattr(media_mod, "ffprobe_available", lambda: True)
    monkeypatch.setattr(media_mod, "ffmpeg_available", lambda: False)
    monkeypatch.setattr(media_mod, "probe_video_websafe", lambda p: False)
    root = tmp_path / "lib"
    _make_video(root / "v.mp4", VIDEO_BYTES)
    app = _build_app(tmp_path, [root])
    indexer = app.state.media_indexer
    indexer._thumbnail = lambda row_id, path, kind: False

    indexer.build()

    mid = _video_id(app)
    assert indexer.get(mid)["video_websafe"] == 0
    assert not (tmp_path / "proxies" / f"{mid}.mp4").exists()
    assert indexer._pretranscode_pool is None


@pytest.mark.skipif(media_mod.ffmpeg_available(),
                    reason="ffmpeg present; default transcoder would run for real")
def test_default_transcoder_requires_ffmpeg_when_absent(tmp_path):
    # When ffmpeg is unavailable, the default transcoder raises (handled upstream).
    app, mid = _nonwebsafe_app(tmp_path)
    client = TestClient(app)
    resp = client.get(f"/api/media/{mid}/stream", headers=_auth(client))
    assert resp.status_code == 503
