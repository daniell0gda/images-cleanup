"""Tests for the errors store and logging capture (cluster 1)."""
from __future__ import annotations

import logging
import os
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest


def _rows(db_path: Path) -> list[sqlite3.Row]:
    if not db_path.exists():
        return []
    con = sqlite3.connect(str(db_path))
    con.row_factory = sqlite3.Row
    try:
        return con.execute("SELECT * FROM errors ORDER BY id").fetchall()
    except sqlite3.OperationalError:
        return []
    finally:
        con.close()


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    return tmp_path / "errors.db"


def test_warning_from_allowlisted_logger_is_stored(db_path: Path) -> None:
    from launcher import errors

    handler = errors.SqliteErrorHandler(db_path, source="launcher")
    log = logging.getLogger("launcher.something")
    log.addHandler(handler)
    log.setLevel(logging.DEBUG)
    log.propagate = False
    try:
        log.warning("disk almost full")
    finally:
        log.removeHandler(handler)

    rows = _rows(db_path)
    assert len(rows) == 1
    row = rows[0]
    assert row["source"] == "launcher"
    assert row["level"] == "WARNING"
    assert row["logger"] == "launcher.something"
    assert row["message"] == "disk almost full"
    # ts must be a valid UTC ISO-8601 string
    parsed = datetime.fromisoformat(row["ts"])
    assert parsed.tzinfo is not None


def test_below_warning_or_non_allowlisted_is_not_stored(db_path: Path) -> None:
    from launcher import errors

    handler = errors.SqliteErrorHandler(db_path, source="launcher")

    allowed = logging.getLogger("imagesorter.sorter")
    allowed.addHandler(handler)
    allowed.setLevel(logging.DEBUG)
    allowed.propagate = False

    outside = logging.getLogger("random.thirdparty")
    outside.addHandler(handler)
    outside.setLevel(logging.DEBUG)
    outside.propagate = False
    try:
        allowed.info("just informational")  # below WARNING
        outside.error("noisy but not allowlisted")  # non-allowlisted logger
    finally:
        allowed.removeHandler(handler)
        outside.removeHandler(handler)
        # Restore propagation so this test does not leak into others that rely on
        # these loggers reaching the root handler (e.g. caplog in the sorter suite).
        allowed.propagate = True
        outside.propagate = True

    assert _rows(db_path) == []


def test_exc_info_stored_as_traceback_else_null(db_path: Path) -> None:
    from launcher import errors

    handler = errors.SqliteErrorHandler(db_path, source="sorter")
    log = logging.getLogger("launcher.exc")
    log.addHandler(handler)
    log.setLevel(logging.DEBUG)
    log.propagate = False
    try:
        try:
            raise ValueError("boom")
        except ValueError:
            log.error("with traceback", exc_info=True)
        log.warning("no traceback")
    finally:
        log.removeHandler(handler)

    rows = _rows(db_path)
    assert len(rows) == 2
    assert rows[0]["traceback"] is not None
    assert "ValueError: boom" in rows[0]["traceback"]
    assert "Traceback" in rows[0]["traceback"]
    assert rows[1]["traceback"] is None


def test_db_failure_does_not_raise_from_logging(tmp_path: Path) -> None:
    from launcher import errors

    # Point the handler at a directory path: sqlite cannot open it as a DB.
    unwritable = tmp_path / "adir"
    unwritable.mkdir()

    handler = errors.SqliteErrorHandler(unwritable, source="launcher")
    logging.raiseExceptions = False  # keep handleError quiet during the test
    log = logging.getLogger("launcher.dbfail")
    log.addHandler(handler)
    log.setLevel(logging.DEBUG)
    log.propagate = False
    try:
        # Must complete without raising even though the insert cannot happen.
        log.warning("this should not blow up")
    finally:
        log.removeHandler(handler)
        logging.raiseExceptions = True


def test_purge_deletes_rows_older_than_30_days(db_path: Path) -> None:
    from launcher import errors

    con = errors.open_db(db_path)
    now = datetime.now(timezone.utc)
    old_ts = (now - timedelta(days=31)).isoformat()
    new_ts = (now - timedelta(days=1)).isoformat()
    con.execute(
        "INSERT INTO errors (ts, source, level, logger, message) VALUES (?,?,?,?,?)",
        (old_ts, "launcher", "ERROR", "launcher.a", "old one"),
    )
    con.execute(
        "INSERT INTO errors (ts, source, level, logger, message) VALUES (?,?,?,?,?)",
        (new_ts, "launcher", "ERROR", "launcher.b", "new one"),
    )
    con.commit()
    con.close()

    removed = errors.purge_old(db_path, days=30)

    assert removed == 1
    rows = _rows(db_path)
    assert [r["message"] for r in rows] == ["new one"]


def _sqlite_handlers() -> list[logging.Handler]:
    from launcher.errors import SqliteErrorHandler
    return [h for h in logging.getLogger().handlers if isinstance(h, SqliteErrorHandler)]


def test_logging_setup_attaches_handler_when_errors_db_set(db_path: Path, monkeypatch) -> None:
    from imagesorter import logging_setup

    monkeypatch.setenv("ERRORS_DB", str(db_path))
    try:
        logging_setup.setup("INFO")
        handlers = _sqlite_handlers()
        assert len(handlers) == 1
        assert handlers[0].source == "sorter"
    finally:
        for h in _sqlite_handlers():
            logging.getLogger().removeHandler(h)


def test_logging_setup_attaches_no_handler_when_errors_db_unset(monkeypatch) -> None:
    from imagesorter import logging_setup

    monkeypatch.delenv("ERRORS_DB", raising=False)
    try:
        logging_setup.setup("INFO")
        assert _sqlite_handlers() == []
    finally:
        for h in _sqlite_handlers():
            logging.getLogger().removeHandler(h)


# ---------------------------------------------------------------------------
# Cluster 2: launcher app + HTTP API
# ---------------------------------------------------------------------------

def _make_app(tmp_path: Path, monkeypatch, scheduler=None):
    """Build a launcher app with sync + errors storage isolated under tmp_path."""
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    monkeypatch.setenv("CONFIGS_DIR", str(configs))
    monkeypatch.setenv("INBOX_BASE", str(inbox))
    monkeypatch.setenv("SYNC_DB", str(tmp_path / "sync.db"))
    monkeypatch.setenv("ERRORS_DB", str(tmp_path / "errors.db"))
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_scheduler=scheduler)


def _trust(client, device_id="dev-1", name="Pixel") -> str:
    """Register + approve a device and return its bearer token."""
    code = client.post(
        "/api/sync/devices", json={"device_id": device_id, "name": name}
    ).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    status = client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()
    return status["token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_startup_attaches_launcher_handler_purges_and_schedules(tmp_path, monkeypatch):
    """create_app with a scheduler attaches the launcher capture handler, trims
    old rows once, and registers a recurring retention janitor."""
    db = tmp_path / "errors.db"
    con = errors_open_db_helper(db)
    old_ts = (datetime.now(timezone.utc) - timedelta(days=40)).isoformat()
    con.execute(
        "INSERT INTO errors (ts, source, level, logger, message) VALUES (?,?,?,?,?)",
        (old_ts, "launcher", "ERROR", "launcher.a", "ancient"),
    )
    con.commit()
    con.close()

    scheduled = []

    def fake_scheduler(interval, fn):
        scheduled.append((interval, fn))

    try:
        _make_app(tmp_path, monkeypatch, scheduler=fake_scheduler)

        handlers = _sqlite_handlers()
        assert any(h.source == "launcher" for h in handlers)
        # The one-shot purge removed the 40-day-old row on startup.
        assert _rows(db) == []
        # A recurring janitor was registered in addition to the sync janitor;
        # invoking the errors one purges again without raising.
        assert len(scheduled) >= 2, "errors retention janitor not registered"
        _, janitor = scheduled[-1]
        janitor()
    finally:
        for h in _sqlite_handlers():
            logging.getLogger().removeHandler(h)


def errors_open_db_helper(db: Path):
    from launcher import errors
    return errors.open_db(db)


def test_report_inserts_android_rows_with_level_from_retryable(tmp_path, monkeypatch):
    """POST /api/errors/report with a valid token inserts one android row per item,
    tagging the device and mapping retryable->WARNING, non-retryable->ERROR."""
    from fastapi.testclient import TestClient

    app = _make_app(tmp_path, monkeypatch)
    client = TestClient(app)
    token = _trust(client)

    items = [
        {"name": "a.jpg", "createdOn": "2026-01-01", "size": 10,
         "reason": "timeout", "retryable": True, "message": "slow net",
         "failedAt": 1000},
        {"name": "b.jpg", "createdOn": "2026-01-02", "size": 20,
         "reason": "corrupt", "retryable": False, "message": None,
         "failedAt": 2000},
    ]
    r = client.post("/api/errors/report", json=items, headers=_auth(token))
    assert r.status_code == 200, r.text

    rows = _rows(tmp_path / "errors.db")
    assert len(rows) == 2
    assert {row["source"] for row in rows} == {"android"}
    assert {row["device_id"] for row in rows} == {"dev-1"}
    by_level = {row["level"]: row for row in rows}
    assert "WARNING" in by_level and "ERROR" in by_level
    assert "timeout" in by_level["WARNING"]["message"]
    assert "slow net" in by_level["WARNING"]["message"]
    assert "corrupt" in by_level["ERROR"]["message"]


def test_report_requires_valid_token(tmp_path, monkeypatch):
    """A missing or invalid bearer token yields 401 and stores nothing."""
    from fastapi.testclient import TestClient

    app = _make_app(tmp_path, monkeypatch)
    client = TestClient(app)

    payload = [{"name": "a.jpg", "createdOn": "x", "size": 1,
                "reason": "timeout", "retryable": True, "message": None,
                "failedAt": 1}]
    assert client.post("/api/errors/report", json=payload).status_code == 401
    assert client.post(
        "/api/errors/report", json=payload, headers=_auth("bogus")
    ).status_code == 401
    assert _rows(tmp_path / "errors.db") == []


def _seed(db: Path, records: list[tuple]) -> None:
    con = errors_open_db_helper(db)
    con.executemany(
        "INSERT INTO errors (ts, source, level, logger, message) VALUES (?,?,?,?,?)",
        records,
    )
    con.commit()
    con.close()


def test_list_newest_first_with_filters_and_pagination(tmp_path, monkeypatch):
    """GET /api/errors returns rows newest-first, honours source/level filters,
    and paginates via limit/offset."""
    from fastapi.testclient import TestClient

    db = tmp_path / "errors.db"
    _seed(db, [
        ("t1", "launcher", "ERROR", "launcher.a", "first"),
        ("t2", "android", "WARNING", "android", "second"),
        ("t3", "launcher", "WARNING", "launcher.b", "third"),
        ("t4", "android", "ERROR", "android", "fourth"),
    ])

    app = _make_app(tmp_path, monkeypatch)
    client = TestClient(app)

    # Newest-first: last inserted (highest id) comes first.
    msgs = [r["message"] for r in client.get("/api/errors").json()]
    assert msgs == ["fourth", "third", "second", "first"]

    # source filter
    src = client.get("/api/errors", params={"source": "android"}).json()
    assert [r["message"] for r in src] == ["fourth", "second"]

    # level filter
    lvl = client.get("/api/errors", params={"level": "WARNING"}).json()
    assert [r["message"] for r in lvl] == ["third", "second"]

    # combined filter
    combo = client.get(
        "/api/errors", params={"source": "android", "level": "ERROR"}
    ).json()
    assert [r["message"] for r in combo] == ["fourth"]

    # pagination
    page = client.get("/api/errors", params={"limit": 2, "offset": 1}).json()
    assert [r["message"] for r in page] == ["third", "second"]


def test_delete_all_and_delete_one(tmp_path, monkeypatch):
    """DELETE /api/errors clears every row; DELETE /api/errors/{id} removes only
    the addressed row."""
    from fastapi.testclient import TestClient

    db = tmp_path / "errors.db"
    _seed(db, [
        ("t1", "launcher", "ERROR", "launcher.a", "one"),
        ("t2", "launcher", "ERROR", "launcher.b", "two"),
        ("t3", "launcher", "ERROR", "launcher.c", "three"),
    ])

    app = _make_app(tmp_path, monkeypatch)
    client = TestClient(app)

    rows = client.get("/api/errors").json()
    target = next(r for r in rows if r["message"] == "two")

    assert client.delete(f"/api/errors/{target['id']}").status_code == 200
    remaining = [r["message"] for r in client.get("/api/errors").json()]
    assert remaining == ["three", "one"]

    assert client.delete("/api/errors").status_code == 200
    assert client.get("/api/errors").json() == []
