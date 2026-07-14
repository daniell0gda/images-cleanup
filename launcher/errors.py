"""SQLite-backed error log store and logging capture.

Records emitted at WARNING or above by an allowlisted set of loggers are
persisted into an ``errors`` table so the launcher UI can surface them. The
module lives in ``launcher`` but has no launcher dependencies, so both the
launcher and the sorter (``imagesorter.logging_setup``) import from here
without creating an import cycle.
"""
from __future__ import annotations

import logging
import os
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Logger-name prefixes whose WARNING+ records are worth persisting.
ALLOWLIST_PREFIXES: tuple[str, ...] = (
    "imagesorter",
    "launcher",
    "uvicorn",
    "fastapi",
    "ultralytics",
    "PIL",
    "httpx",
    "croniter",
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS errors (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         TEXT NOT NULL,
    source     TEXT NOT NULL,
    level      TEXT NOT NULL,
    logger     TEXT NOT NULL,
    message    TEXT NOT NULL,
    traceback  TEXT,
    device_id  TEXT
);
"""


def errors_db_path() -> Path:
    """Filesystem path of the errors DB (env ``ERRORS_DB``, default under data)."""
    return Path(os.environ.get("ERRORS_DB", "./data/errors.db"))


def open_db(path: str | Path) -> sqlite3.Connection:
    """Open (creating if needed) the errors DB in WAL mode with the schema applied."""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(p), check_same_thread=False)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript(_SCHEMA)
    con.commit()
    return con


def _is_allowlisted(logger_name: str) -> bool:
    return any(
        logger_name == prefix or logger_name.startswith(prefix + ".")
        for prefix in ALLOWLIST_PREFIXES
    )


class SqliteErrorHandler(logging.Handler):
    """Logging handler that stores WARNING+ allowlisted records into the errors DB."""

    def __init__(self, db_path: str | Path, source: str) -> None:
        super().__init__(level=logging.WARNING)
        self.db_path = Path(db_path)
        self.source = source
        self._con: sqlite3.Connection | None = None

    def _conn(self) -> sqlite3.Connection:
        if self._con is None:
            self._con = open_db(self.db_path)
        return self._con

    def emit(self, record: logging.LogRecord) -> None:
        if not _is_allowlisted(record.name):
            return
        try:
            ts = datetime.fromtimestamp(record.created, timezone.utc).isoformat()
            traceback = self._format_traceback(record)
            con = self._conn()
            con.execute(
                "INSERT INTO errors (ts, source, level, logger, message, traceback, device_id) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (ts, self.source, record.levelname, record.name,
                 record.getMessage(), traceback, None),
            )
            con.commit()
        except Exception:
            self.handleError(record)

    @staticmethod
    def _format_traceback(record: logging.LogRecord) -> str | None:
        if record.exc_info:
            return logging.Formatter().formatException(record.exc_info)
        return None


def attach_error_handler(db_path: str | Path, source: str) -> SqliteErrorHandler:
    """Attach a :class:`SqliteErrorHandler` to the root logger and return it."""
    handler = SqliteErrorHandler(db_path, source)
    logging.getLogger().addHandler(handler)
    return handler


def purge_old(db_path: str | Path, days: int = 30) -> int:
    """Delete rows older than ``days`` and return the number removed."""
    cutoff = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
    con = open_db(db_path)
    try:
        cur = con.execute("DELETE FROM errors WHERE ts < ?", (cutoff,))
        con.commit()
        return cur.rowcount
    finally:
        con.close()
