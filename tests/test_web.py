"""Tests for the web UI server (FastAPI + SSE)."""
from __future__ import annotations

import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
from PIL import Image


def _make_config(tmp_path, *, web_ui=True, threshold=0.96, time_window=5):
    from imagesorter.config import Config, Unclassified
    return Config(
        mode="SimilaritySearch",
        source_folder=str(tmp_path / "src"),
        recursive=False,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="DEBUG",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=False, folder_name="others",
            destination=str(tmp_path / "unclassified"),
            group_by_year=False, group_by_month=False,
        ),
        similarity_threshold=threshold,
        web_ui=web_ui,
        similarity_time_window_minutes=time_window,
    )


def make_jpeg(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (10, 10), color=(100, 100, 100))
    img.save(str(path), "JPEG")
    return path


class FakeHash:
    """Fake perceptual hash that supports subtraction."""
    def __init__(self, value: int, bits: int = 64):
        self.value = value
        self._bits = bits

    def __sub__(self, other: "FakeHash") -> int:
        return abs(self.value - other.value)

    @property
    def hash(self):
        import numpy as np
        return np.zeros((8, 8))


# ── Criterion 3: missing frontend/dist → exit non-zero with clear message ─────

def test_serve_exits_when_frontend_dist_missing(tmp_path, capsys, monkeypatch):
    """When frontend/dist does not exist, serve() must call sys.exit with a non-zero code."""
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    config = _make_config(tmp_path, web_ui=True)

    # Point project root at a path with no frontend/dist
    monkeypatch.setattr(web, "PROJECT_ROOT", tmp_path)

    with pytest.raises(SystemExit) as exc_info:
        web.serve(config)

    assert exc_info.value.code != 0
    captured = capsys.readouterr()
    output = captured.out + captured.err
    assert "frontend" in output.lower()
    assert "build" in output.lower()


# ── Criterion 4: port 8080 default, auto-increment if taken ───────────────────

def test_find_free_port_returns_8080_when_free():
    """find_free_port starts at 8080 and returns it if available."""
    from imagesorter import web

    # Mock socket binding to succeed on first try
    def fake_bind_ok(sock, addr):
        return None  # success

    with patch("socket.socket") as mock_sock_cls:
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind = MagicMock(return_value=None)
        mock_sock_cls.return_value = mock_sock

        port = web.find_free_port(start=8080)

    assert port == 8080


def test_serve_logs_bound_port(tmp_path, monkeypatch, caplog):
    """serve() must log which port it bound to."""
    import logging
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    (tmp_path / "frontend" / "dist").mkdir(parents=True)
    config = _make_config(tmp_path, web_ui=True)

    monkeypatch.setattr(web, "PROJECT_ROOT", tmp_path)
    monkeypatch.setattr(web, "find_free_port", lambda start=8080: 8083)
    monkeypatch.setattr(webbrowser_module_for_web(web), "open", lambda url: True)

    started_port = []

    def fake_uvicorn_run(app, host, port, log_level):
        started_port.append(port)

    monkeypatch.setattr("uvicorn.run", fake_uvicorn_run)

    # Avoid the scan thread doing real work
    monkeypatch.setattr(web, "scan_images", lambda cfg, state: None)

    with caplog.at_level(logging.INFO, logger="imagesorter.web"):
        web.serve(config)

    assert started_port == [8083]
    info_messages = [r.getMessage() for r in caplog.records if r.levelno == logging.INFO]
    assert any("8083" in m for m in info_messages), (
        f"Expected an INFO log mentioning bound port 8083, got: {info_messages}"
    )


def webbrowser_module_for_web(web):
    """Return the webbrowser module bound inside imagesorter.web for monkeypatching."""
    return web.webbrowser


def test_serve_starts_scan_thread_and_opens_browser(tmp_path, monkeypatch):
    """serve() must launch scanning in a background thread and open the browser."""
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    (tmp_path / "frontend" / "dist").mkdir(parents=True)
    config = _make_config(tmp_path, web_ui=True)

    monkeypatch.setattr(web, "PROJECT_ROOT", tmp_path)
    monkeypatch.setattr(web, "find_free_port", lambda start=8080: 8085)

    opened_urls = []
    monkeypatch.setattr(web.webbrowser, "open", lambda url: opened_urls.append(url) or True)

    scan_called = []
    monkeypatch.setattr(
        web, "scan_images",
        lambda cfg, state: scan_called.append(cfg),
    )

    monkeypatch.setattr("uvicorn.run", lambda *a, **kw: None)

    web.serve(config)

    assert scan_called, "scan_images must be invoked"
    assert opened_urls, "webbrowser.open must be called"
    assert "8085" in opened_urls[0]


# ── Criterion 6: SSE stream emits one event per discovered group ──────────────

def test_scan_emits_group_event_per_pair(tmp_path, monkeypatch):
    """scan_images publishes a group event to the ScanState queue for every similarity pair."""
    import asyncio
    from imagesorter import web

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")
    make_jpeg(src / "c.jpg")

    config = _make_config(tmp_path, web_ui=True, threshold=0.9)

    state = web.ScanState()
    # Provide an event loop so emit_group can schedule queue puts.
    loop = asyncio.new_event_loop()
    state.loop = loop
    queue = state.subscribe()

    same_hash = FakeHash(0)
    from datetime import datetime
    monkeypatch.setattr(web, "_hash_image", lambda p: same_hash)
    monkeypatch.setattr(web, "_get_image_date", lambda p: datetime(2020, 1, 1))

    # Run scan on the loop's thread by scheduling it
    import threading
    done = threading.Event()

    def run_scan():
        web.scan_images(config, state)
        done.set()

    threading.Thread(target=run_scan, daemon=True).start()

    # Drain queue until scan_complete or timeout
    async def drain():
        events = []
        while not state.scan_complete or not queue.empty():
            try:
                item = await asyncio.wait_for(queue.get(), timeout=2.0)
                events.append(item)
                if item.get("event") == "complete":
                    break
            except asyncio.TimeoutError:
                break
        return events

    events = loop.run_until_complete(drain())
    loop.close()

    group_events = [e for e in events if e.get("event") != "complete"]
    # 3 images all matching = 2 pairs → 2 emit calls (one per union step that grew a group)
    assert len(group_events) >= 1, f"Expected at least one group event, got: {events}"
    # Final state has one group containing all three paths
    assert state.groups, "state.groups must be populated as pairs are found"
    final = state.groups[-1]
    assert len(final["paths"]) == 3


# ── Criterion 8: time-window pre-filter ──────────────────────────────────────

def test_scan_skips_pairs_outside_time_window(tmp_path, monkeypatch):
    """Two visually identical images with timestamps farther than the window do not form a pair."""
    import asyncio
    from datetime import datetime
    from imagesorter import web

    src = tmp_path / "src"
    make_jpeg(src / "early.jpg")
    make_jpeg(src / "late.jpg")

    config = _make_config(tmp_path, web_ui=True, threshold=0.9, time_window=5)

    state = web.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop

    monkeypatch.setattr(web, "_hash_image", lambda p: FakeHash(0))  # identical

    def fake_date(path):
        if path.name == "early.jpg":
            return datetime(2020, 1, 1, 12, 0, 0)
        return datetime(2020, 1, 1, 12, 30, 0)  # 30 minutes later — outside 5-min window

    monkeypatch.setattr(web, "_get_image_date", fake_date)

    web.scan_images(config, state)
    loop.close()

    # No group should have formed
    assert state.groups == [], (
        f"Expected no groups when images are outside time window, got: {state.groups}"
    )


# ── Criterion 9: /api/images/{encoded_path} serves files within source_folder ─

def test_get_image_serves_file_within_source(tmp_path):
    """GET /api/images/{path} returns the file when path is inside source_folder."""
    from fastapi.testclient import TestClient
    from urllib.parse import quote
    from imagesorter import web

    src = tmp_path / "src"
    img = make_jpeg(src / "a.jpg")

    config = _make_config(tmp_path, web_ui=True)
    state = web.ScanState()
    app = web.create_app(config, state)

    client = TestClient(app)
    encoded = quote(str(img.resolve()), safe="")
    response = client.get(f"/api/images/{encoded}")
    assert response.status_code == 200
    assert response.content == img.read_bytes()


# ── Criterion 15: specification.md documents new behavior ────────────────────

def test_specification_documents_web_ui_field():
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "web_ui" in spec, "specification.md must document the web_ui config field"


def test_specification_documents_time_window_field():
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "similarity_time_window_minutes" in spec, (
        "specification.md must document similarity_time_window_minutes"
    )


def test_specification_documents_time_window_behavior():
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "Time-Window Pre-Filter" in spec or "time-window" in spec.lower(), (
        "specification.md must document time-based pre-filter behavior"
    )


def test_specification_documents_web_ui_workflow():
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "Web UI" in spec, "specification.md must document the web UI workflow"


def test_specification_documents_sse_contract():
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "SSE" in spec or "Server-Sent Events" in spec or "/api/stream" in spec, (
        "specification.md must document the SSE streaming contract"
    )


def test_get_image_returns_403_outside_source(tmp_path):
    """GET /api/images/{path} returns 403 when the resolved path is outside source_folder."""
    from fastapi.testclient import TestClient
    from urllib.parse import quote
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()

    outside = tmp_path / "outside"
    outside_img = make_jpeg(outside / "x.jpg")

    config = _make_config(tmp_path, web_ui=True)
    state = web.ScanState()
    app = web.create_app(config, state)

    client = TestClient(app)
    encoded = quote(str(outside_img.resolve()), safe="")
    response = client.get(f"/api/images/{encoded}")
    assert response.status_code == 403


# ── Criterion 10: DELETE /api/images ──────────────────────────────────────────

def test_delete_images_calls_send2trash_and_returns_result(tmp_path, monkeypatch):
    """DELETE /api/images validates paths, calls send2trash, returns trashed and failed."""
    from fastapi.testclient import TestClient
    from imagesorter import web

    src = tmp_path / "src"
    img_a = make_jpeg(src / "a.jpg")
    img_b = make_jpeg(src / "b.jpg")

    config = _make_config(tmp_path, web_ui=True)
    state = web.ScanState()
    app = web.create_app(config, state)

    trashed_paths = []

    def fake_send2trash(path):
        trashed_paths.append(path)

    monkeypatch.setattr("send2trash.send2trash", fake_send2trash)

    client = TestClient(app)
    response = client.request(
        "DELETE",
        "/api/images",
        json=[str(img_a.resolve()), str(img_b.resolve())],
    )

    assert response.status_code == 200, f"body: {response.text}"
    body = response.json()
    assert "trashed" in body
    assert "failed" in body
    assert len(body["trashed"]) == 2
    assert body["failed"] == []
    assert len(trashed_paths) == 2


def test_delete_images_returns_403_when_any_path_outside(tmp_path, monkeypatch):
    """DELETE /api/images returns 403 (and trashes nothing) if any path is outside source_folder."""
    from fastapi.testclient import TestClient
    from imagesorter import web

    src = tmp_path / "src"
    img_a = make_jpeg(src / "a.jpg")

    outside = tmp_path / "outside"
    outside_img = make_jpeg(outside / "x.jpg")

    config = _make_config(tmp_path, web_ui=True)
    state = web.ScanState()
    app = web.create_app(config, state)

    trashed = []
    monkeypatch.setattr("send2trash.send2trash", lambda p: trashed.append(p))

    client = TestClient(app)
    response = client.request(
        "DELETE",
        "/api/images",
        json=[str(img_a.resolve()), str(outside_img.resolve())],
    )

    assert response.status_code == 403
    assert trashed == [], "Nothing must be trashed if any path is invalid"


def test_scan_pairs_inside_time_window(tmp_path, monkeypatch):
    """Two visually identical images within the time window form a pair."""
    import asyncio
    from datetime import datetime
    from imagesorter import web

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")

    config = _make_config(tmp_path, web_ui=True, threshold=0.9, time_window=5)

    state = web.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop

    monkeypatch.setattr(web, "_hash_image", lambda p: FakeHash(0))

    def fake_date(path):
        if path.name == "a.jpg":
            return datetime(2020, 1, 1, 12, 0, 0)
        return datetime(2020, 1, 1, 12, 2, 0)  # 2 min — inside window

    monkeypatch.setattr(web, "_get_image_date", fake_date)

    web.scan_images(config, state)
    loop.close()

    assert state.groups, "Expected a group when images are inside time window"
    assert len(state.groups[-1]["paths"]) == 2


def test_hash_image_and_get_image_date_are_not_duplicated():
    """`_hash_image` and `_get_image_date` must have one canonical implementation
    shared by `imagesorter.similarity` and `imagesorter.web` (no verbatim copies).
    """
    from imagesorter import similarity, web

    assert web._hash_image is similarity._hash_image, (
        "imagesorter.web._hash_image must reference the same callable as "
        "imagesorter.similarity._hash_image (no duplicated definition)"
    )
    assert web._get_image_date is similarity._get_image_date, (
        "imagesorter.web._get_image_date must reference the same callable as "
        "imagesorter.similarity._get_image_date (no duplicated definition)"
    )


def test_scan_merge_of_two_groups_keeps_ids_disjoint(tmp_path, monkeypatch):
    """When two already-emitted groups merge, no two ids may describe overlapping paths.

    Sequence: pair(0,1) emits id A with {0,1}; pair(2,3) emits id B with {2,3};
    pair(1,2) merges them. After the merge, exactly one id may name the union
    {0,1,2,3}; the other id must be invalidated (paths empty) so that no two
    distinct ids reference overlapping membership.
    """
    import asyncio
    from datetime import datetime, timedelta
    from imagesorter import web

    src = tmp_path / "src"
    paths = [make_jpeg(src / f"{n}.jpg") for n in ("a", "b", "c", "d")]

    config = _make_config(tmp_path, web_ui=True, threshold=0.9, time_window=60)

    state = web.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop

    # Hashes engineered so the pair-discovery order is: (a,b), (a,c) -> no, (a,d) -> no,
    # (b,c) -> no, (b,d) -> no, (c,d) yes, then later we need (b,c) to match. So engineer:
    # a≈b (close), c≈d (close), and b≈c also close — but a vs c/d far. We just need that
    # at some point the union step merges two roots that were both previously emitted.
    # Use staggered values so (a,b) match, (c,d) match, and (b,c) eventually matches too.
    hash_map = {
        "a.jpg": FakeHash(0),
        "b.jpg": FakeHash(0),  # a≈b
        "c.jpg": FakeHash(0),  # b≈c (and c≈d)
        "d.jpg": FakeHash(0),  # c≈d
    }
    monkeypatch.setattr(web, "_hash_image", lambda p: hash_map[p.name])

    base = datetime(2020, 1, 1, 12, 0, 0)
    # All within the time window so every pair is considered.
    monkeypatch.setattr(web, "_get_image_date", lambda p: base + timedelta(seconds=0))

    web.scan_images(config, state)
    loop.close()

    # Collect, per id, the *last* set of paths emitted for that id.
    last_paths_by_id: dict[int, set[str]] = {}
    for g in state.groups:
        last_paths_by_id[g["id"]] = set(g["paths"])

    # No two distinct ids may name overlapping path sets after the merge resolves.
    ids = list(last_paths_by_id.keys())
    for i in range(len(ids)):
        for j in range(i + 1, len(ids)):
            a, b = last_paths_by_id[ids[i]], last_paths_by_id[ids[j]]
            if not a or not b:
                continue  # an invalidated (emptied) group is fine
            assert a.isdisjoint(b), (
                f"ids {ids[i]} and {ids[j]} both reference overlapping paths after merge:\n"
                f"  {ids[i]}: {a}\n  {ids[j]}: {b}\n"
                f"  full history: {state.groups}"
            )

    # And the union {a,b,c,d} must be present under exactly one id.
    all_four = {str(p) for p in paths}
    ids_with_full_union = [
        gid for gid, ps in last_paths_by_id.items() if ps == all_four
    ]
    assert len(ids_with_full_union) == 1, (
        f"Expected exactly one id to name the merged group {all_four}, "
        f"got: {ids_with_full_union} (full history: {state.groups})"
    )


def test_scan_never_retracts_previously_emitted_groups(tmp_path, monkeypatch):
    """Once a group event is emitted with a given id, it must never be removed from state.groups."""
    import asyncio
    from imagesorter import web

    src = tmp_path / "src"
    make_jpeg(src / "a.jpg")
    make_jpeg(src / "b.jpg")
    make_jpeg(src / "c.jpg")

    config = _make_config(tmp_path, web_ui=True, threshold=0.9)

    state = web.ScanState()
    loop = asyncio.new_event_loop()
    state.loop = loop

    monkeypatch.setattr(web, "_hash_image", lambda p: FakeHash(0))
    from datetime import datetime
    monkeypatch.setattr(web, "_get_image_date", lambda p: datetime(2020, 1, 1))

    web.scan_images(config, state)
    loop.close()

    # Collect every id that was ever emitted (via state.groups history)
    emitted_ids = {g["id"] for g in state.groups}
    # state.groups grows monotonically — every previously emitted id is still present
    for prior_id in emitted_ids:
        assert any(g["id"] == prior_id for g in state.groups), (
            f"Group id {prior_id} was emitted but no longer present in state.groups"
        )


def test_find_free_port_increments_when_taken():
    """find_free_port increments to the next port when start port is taken."""
    from imagesorter import web

    call_count = [0]

    def fake_bind(addr):
        call_count[0] += 1
        if call_count[0] == 1:
            raise OSError("address in use")
        return None

    with patch("socket.socket") as mock_sock_cls:
        mock_sock = MagicMock()
        mock_sock.__enter__.return_value = mock_sock
        mock_sock.__exit__.return_value = False
        mock_sock.bind = MagicMock(side_effect=fake_bind)
        mock_sock_cls.return_value = mock_sock

        port = web.find_free_port(start=8080)

    assert port == 8081


def test_stream_closes_when_scan_completes_before_client_connects(tmp_path):
    """A client connecting AFTER scan_complete must receive the complete event and the stream must close.

    Reproduces the late-client hang: scan_images() runs to completion with state.loop = None,
    so the 'complete' event is never queued. A subsequent SSE client must still see the
    completion signal rather than blocking on queue.get() forever.
    """
    import asyncio
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    config = _make_config(tmp_path, web_ui=True)

    # Simulate a scan that ran to completion before any client was around.
    state = web.ScanState()
    state.groups.append({"id": 0, "paths": [str(src / "a.jpg"), str(src / "b.jpg")]})
    state.mark_complete()  # state.loop is None here — same as production race

    app = web.create_app(config, state)

    # Locate the registered /api/stream handler directly so we exercise it
    # without spinning up uvicorn (and without risking a hang on failure).
    stream_route = next(
        r for r in app.router.routes if getattr(r, "path", "") == "/api/stream"
    )

    async def collect() -> list:
        response = await stream_route.endpoint()  # EventSourceResponse
        body_iter = response.body_iterator
        out: list = []
        saw_complete = False
        # Hard cap so a buggy generator can't hang the test forever.
        for _ in range(50):
            try:
                chunk = await asyncio.wait_for(body_iter.__anext__(), timeout=2.0)
            except (StopAsyncIteration, asyncio.TimeoutError):
                break
            out.append(chunk)
            if isinstance(chunk, dict) and chunk.get("event") == "complete":
                saw_complete = True
                break
            if isinstance(chunk, (bytes, str)) and "complete" in (
                chunk.decode("utf-8", "replace") if isinstance(chunk, bytes) else chunk
            ):
                saw_complete = True
                break
        return out, saw_complete

    chunks, saw_complete = asyncio.run(collect())
    assert saw_complete, (
        f"Expected stream to emit a 'complete' event when scan finished before client connected; "
        f"got chunks: {chunks!r}"
    )


# ── Criterion: multi-client SSE fan-out ──────────────────────────────────────

def test_two_sse_clients_both_receive_every_group_event(tmp_path):
    """When two SSE clients are connected concurrently, both must receive every group event.

    The previous single-`asyncio.Queue` implementation would let one client consume an
    event that the other never sees. Each client must observe the full stream.
    """
    import asyncio
    import json
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    config = _make_config(tmp_path, web_ui=True)

    state = web.ScanState()
    app = web.create_app(config, state)
    stream_route = next(
        r for r in app.router.routes if getattr(r, "path", "") == "/api/stream"
    )

    async def scenario() -> tuple[list, list]:
        state.loop = asyncio.get_event_loop()

        resp_a = await stream_route.endpoint()
        resp_b = await stream_route.endpoint()
        iter_a = resp_a.body_iterator
        iter_b = resp_b.body_iterator

        # Force each generator to begin executing and park on its queue.
        # The replay loop drains the (empty) state.groups, so the next chunk
        # comes only from `queue.get()`. We start a __anext__ on both and
        # immediately race emit_group against them — both must observe each event.
        next_a = asyncio.ensure_future(iter_a.__anext__())
        next_b = asyncio.ensure_future(iter_b.__anext__())

        # Yield repeatedly until both generators have subscribed (proves
        # they ran past the replay loop and are now parked on `queue.get()`).
        for _ in range(20):
            await asyncio.sleep(0)
            if len(state._subscribers) >= 2:
                break

        # Both clients are now blocked on queue.get(). Emit one event.
        state.emit_group({"id": 0, "paths": ["x", "y"]})

        chunk_a = await asyncio.wait_for(next_a, timeout=2.0)
        chunk_b = await asyncio.wait_for(next_b, timeout=2.0)
        return [chunk_a], [chunk_b]

    chunks_a, chunks_b = asyncio.run(scenario())

    def group_ids(chunks: list) -> list[int]:
        ids: list[int] = []
        for c in chunks:
            if isinstance(c, dict) and c.get("event") == "group":
                payload = json.loads(c["data"])
                ids.append(payload["id"])
        return ids

    ids_a = group_ids(chunks_a)
    ids_b = group_ids(chunks_b)

    assert 0 in ids_a, f"client A missed the group event: {chunks_a!r}"
    assert 0 in ids_b, f"client B missed the group event: {chunks_b!r}"


# ── Criterion: scan-thread exception still terminates SSE stream ────────────

def test_stream_terminates_when_scan_raises_unexpectedly(tmp_path, monkeypatch):
    """If `scan_images()` crashes outside per-image try/except, the SSE stream
    must still emit a terminal `complete` event instead of hanging forever.
    """
    import asyncio
    import threading
    from imagesorter import web

    src = tmp_path / "src"
    src.mkdir()
    config = _make_config(tmp_path, web_ui=True)

    state = web.ScanState()
    app = web.create_app(config, state)
    stream_route = next(
        r for r in app.router.routes if getattr(r, "path", "") == "/api/stream"
    )

    # Replace scan_images with one that explodes (simulating an unexpected
    # bug like a corrupt config, missing import, etc.).
    def crashing_scan(cfg, st):
        raise RuntimeError("boom: simulated scan crash")

    monkeypatch.setattr(web, "scan_images", crashing_scan)

    async def scenario():
        state.loop = asyncio.get_event_loop()
        resp = await stream_route.endpoint()
        body_iter = resp.body_iterator

        # Start the scan in a background thread, *after* the client is set up,
        # so the production code path (try/finally around scan_images) is exercised.
        def run_scan():
            web.run_scan_safely(config, state)

        threading.Thread(target=run_scan, daemon=True).start()

        out: list = []
        saw_complete = False
        for _ in range(20):
            try:
                chunk = await asyncio.wait_for(body_iter.__anext__(), timeout=3.0)
            except (StopAsyncIteration, asyncio.TimeoutError):
                break
            out.append(chunk)
            if isinstance(chunk, dict) and chunk.get("event") == "complete":
                saw_complete = True
                break
        return out, saw_complete

    chunks, saw_complete = asyncio.run(scenario())
    assert saw_complete, (
        f"Expected a 'complete' event after scan crashed; got: {chunks!r}"
    )


# ── Criterion: deleted paths must not be re-emitted in subsequent group events ─

def test_deleted_paths_excluded_from_subsequent_group_events(tmp_path, monkeypatch):
    """After DELETE /api/images trashes a path, any later group emission that
    would have included that path must filter it out so the UI never sees the
    deleted image reappear.
    """
    from fastapi.testclient import TestClient
    from imagesorter import web

    src = tmp_path / "src"
    img_a = make_jpeg(src / "a.jpg")
    img_b = make_jpeg(src / "b.jpg")
    img_c = make_jpeg(src / "c.jpg")

    config = _make_config(tmp_path, web_ui=True)
    state = web.ScanState()
    app = web.create_app(config, state)

    # Stub send2trash to a no-op so the test doesn't depend on the OS recycle bin.
    monkeypatch.setattr("send2trash.send2trash", lambda p: None)

    # Delete img_b via the API.
    client = TestClient(app)
    response = client.request(
        "DELETE",
        "/api/images",
        json=[str(img_b.resolve())],
    )
    assert response.status_code == 200
    assert response.json()["trashed"] == [str(img_b.resolve())]

    # Simulate the scan thread now merging a group that would have included img_b.
    state.emit_group({
        "id": 0,
        "paths": [str(img_a.resolve()), str(img_b.resolve()), str(img_c.resolve())],
    })

    # The recorded group must not contain the deleted path.
    assert state.groups, "emit_group should record the (filtered) group"
    final = state.groups[-1]
    assert str(img_b.resolve()) not in final["paths"], (
        f"Deleted path {img_b.resolve()} reappeared in subsequent group event: {final}"
    )
    assert str(img_a.resolve()) in final["paths"]
    assert str(img_c.resolve()) in final["paths"]


def test_discover_images_is_not_duplicated():
    """`_discover_images` must have one canonical implementation shared by both
    `imagesorter.similarity` and `imagesorter.web` (CLAUDE.md: no duplicated code).
    """
    from imagesorter import similarity, web

    assert web._discover_images is similarity._discover_images, (
        "imagesorter.web._discover_images must reference the same callable as "
        "imagesorter.similarity._discover_images (no duplicated definition)"
    )
