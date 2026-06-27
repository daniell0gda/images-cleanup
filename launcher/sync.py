"""Android phone-sync server side: storage, identity, routing, lanes.

This module backs the ``/api/sync/*`` endpoints. It keeps a SQLite-backed
index of trusted devices and synced files (the server is authoritative), runs
file placement in a lane independent from the manual-sort job, and manages the
temp upload-session lifecycle.
"""
from __future__ import annotations

import enum
import json
import os
import re
import secrets
import shutil
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


# ---------------------------------------------------------------------------
# Failure-reason taxonomy
# ---------------------------------------------------------------------------

class FailureReason(enum.Enum):
    """Per-file processing failure reasons, classified for retry behaviour."""

    SIZE_MISMATCH = "size_mismatch"          # assembled bytes != declared size
    UNREADABLE = "unreadable"                # file could not be opened/decoded
    PLACEMENT_ERROR = "placement_error"      # move to destination failed
    INTERNAL_ERROR = "internal_error"        # unexpected server error
    NO_VIDEO_DESTINATION = "no_video_destination"  # profile has no video section

    @property
    def retryable(self) -> bool:
        """Whether the phone may auto-retry this file later."""
        return self in _RETRYABLE_REASONS


_RETRYABLE_REASONS = {
    FailureReason.SIZE_MISMATCH,
    FailureReason.PLACEMENT_ERROR,
    FailureReason.INTERNAL_ERROR,
}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Profiles
# ---------------------------------------------------------------------------

_GROUPBY_RE = re.compile(r"^config_(.+?)_groupby\.yaml$")


def list_profiles(configs_dir: Path) -> list[dict]:
    """List sync-capable (GroupByTags) profiles; SimilaritySearch is excluded."""
    profiles: list[dict] = []
    if configs_dir.is_dir():
        for f in sorted(configs_dir.iterdir()):
            m = _GROUPBY_RE.match(f.name)
            if m:
                user = m.group(1)
                profiles.append({"profile_id": f"{user}_groupby", "display_name": user})
    return profiles


# ---------------------------------------------------------------------------
# Persistent store (SQLite on the NAS)
# ---------------------------------------------------------------------------

class SyncStore:
    """SQLite-backed device registry and synced-file index.

    All access is serialized through a lock so the store is safe to share
    across request handlers and the processing lane.
    """

    def __init__(self, db_path: Path):
        self._db_path = Path(db_path)
        self._lock = threading.RLock()
        self._con: sqlite3.Connection | None = None

    def _conn(self) -> sqlite3.Connection:
        """Open the SQLite connection on first use.

        Lazy so that merely constructing the store — e.g. when the launcher app
        is built without sync configured, or in a non-sync test — never creates
        the database file. The parent directory is created on first connect.
        """
        with self._lock:
            con = self._con
            if con is None:
                self._db_path.parent.mkdir(parents=True, exist_ok=True)
                con = sqlite3.connect(str(self._db_path), check_same_thread=False)
                con.row_factory = sqlite3.Row
                self._con = con
                self._init_schema(con)
            return con

    def _init_schema(self, con: sqlite3.Connection) -> None:
        with self._lock:
            con.executescript(
                """
                CREATE TABLE IF NOT EXISTS devices (
                    device_id     TEXT PRIMARY KEY,
                    name          TEXT NOT NULL,
                    token         TEXT,
                    status        TEXT NOT NULL,
                    pairing_code  TEXT,
                    created_at    TEXT NOT NULL,
                    approved_at   TEXT
                );
                CREATE TABLE IF NOT EXISTS synced_files (
                    name          TEXT NOT NULL,
                    created_on    TEXT NOT NULL,
                    size          INTEGER NOT NULL,
                    mime_type     TEXT NOT NULL,
                    stored_path   TEXT NOT NULL,
                    device_id     TEXT NOT NULL,
                    profile_id    TEXT NOT NULL,
                    synced_at     TEXT NOT NULL,
                    PRIMARY KEY (name, created_on, size)
                );
                CREATE TABLE IF NOT EXISTS outcomes (
                    session_id    TEXT NOT NULL,
                    file_id       TEXT NOT NULL,
                    name          TEXT NOT NULL,
                    status        TEXT NOT NULL,
                    reason        TEXT,
                    retryable     INTEGER,
                    PRIMARY KEY (session_id, file_id)
                );
                """
            )
            con.commit()

    # -- devices ---------------------------------------------------------

    def register_device(self, device_id: str, name: str) -> str:
        """Create (or reset) a pending device and return its pairing code."""
        code = f"{secrets.randbelow(1_000_000):06d}"
        with self._lock:
            self._conn().execute(
                "INSERT OR REPLACE INTO devices "
                "(device_id, name, token, status, pairing_code, created_at, approved_at) "
                "VALUES (?, ?, NULL, 'pending', ?, ?, NULL)",
                (device_id, name, code, _now()),
            )
            self._conn().commit()
        return code

    def get_device(self, device_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._conn().execute(
                "SELECT * FROM devices WHERE device_id=?", (device_id,)
            ).fetchone()

    def approve_device(self, device_id: str) -> str | None:
        """Transition a device to trusted, issue a token, return the token."""
        token = secrets.token_urlsafe(32)
        with self._lock:
            cur = self._conn().execute(
                "UPDATE devices SET status='trusted', token=?, approved_at=? "
                "WHERE device_id=?",
                (token, _now(), device_id),
            )
            self._conn().commit()
            if cur.rowcount == 0:
                return None
        return token

    def revoke_device(self, device_id: str) -> None:
        with self._lock:
            self._conn().execute(
                "UPDATE devices SET status='revoked' WHERE device_id=?",
                (device_id,),
            )
            self._conn().commit()

    def device_for_token(self, token: str) -> sqlite3.Row | None:
        """Return a trusted device matching the bearer token, else None."""
        if not token:
            return None
        with self._lock:
            return self._conn().execute(
                "SELECT * FROM devices WHERE token=? AND status='trusted'",
                (token,),
            ).fetchone()

    def list_devices(self) -> list[dict]:
        """List all devices for the management UI, newest first. The bearer
        token is never exposed — only pairing/identity fields."""
        with self._lock:
            rows = self._conn().execute(
                "SELECT device_id, name, status, pairing_code, created_at, approved_at "
                "FROM devices ORDER BY created_at DESC"
            ).fetchall()
        return [dict(r) for r in rows]

    # -- synced-file index ----------------------------------------------

    def is_synced(self, name: str, created_on: str, size: int) -> bool:
        # Identity is global by (name, created_on, size): a file synced by one
        # device is "already synced" for every other device. The primary key on
        # synced_files enforces one row per identity (not per device).
        with self._lock:
            return self._conn().execute(
                "SELECT 1 FROM synced_files WHERE name=? AND created_on=? AND size=?",
                (name, created_on, size),
            ).fetchone() is not None

    def record_synced(
        self,
        name: str,
        created_on: str,
        size: int,
        mime_type: str,
        stored_path: str,
        device_id: str,
        profile_id: str,
    ) -> None:
        with self._lock:
            self._conn().execute(
                "INSERT OR REPLACE INTO synced_files "
                "(name, created_on, size, mime_type, stored_path, device_id, profile_id, synced_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (name, created_on, size, mime_type, stored_path, device_id, profile_id, _now()),
            )
            self._conn().commit()

    # -- outcomes --------------------------------------------------------

    def record_outcome(self, session_id: str, outcome: dict) -> None:
        with self._lock:
            self._conn().execute(
                "INSERT OR REPLACE INTO outcomes "
                "(session_id, file_id, name, status, reason, retryable) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (
                    session_id,
                    outcome["file_id"],
                    outcome["name"],
                    outcome["status"],
                    outcome.get("reason"),
                    1 if outcome.get("retryable") else 0,
                ),
            )
            self._conn().commit()

    def has_outcomes(self, session_id: str) -> bool:
        with self._lock:
            return self._conn().execute(
                "SELECT 1 FROM outcomes WHERE session_id=? LIMIT 1", (session_id,)
            ).fetchone() is not None

    def outcomes_for(self, session_id: str) -> list[dict]:
        with self._lock:
            rows = self._conn().execute(
                "SELECT file_id, name, status, reason, retryable FROM outcomes "
                "WHERE session_id=? ORDER BY file_id",
                (session_id,),
            ).fetchall()
        results: list[dict] = []
        for r in rows:
            entry = {"file_id": r["file_id"], "name": r["name"], "status": r["status"]}
            if r["status"] == "failed":
                entry["reason"] = r["reason"]
                entry["retryable"] = bool(r["retryable"])
            results.append(entry)
        return results

    def stored_path_for(self, name: str, created_on: str, size: int) -> str | None:
        with self._lock:
            row = self._conn().execute(
                "SELECT stored_path FROM synced_files "
                "WHERE name=? AND created_on=? AND size=?",
                (name, created_on, size),
            ).fetchone()
        return row["stored_path"] if row else None


# ---------------------------------------------------------------------------
# Upload-session lifecycle
# ---------------------------------------------------------------------------

_COMPLETE_MARKER = ".complete"
_SESSION_META = "session.json"


@dataclass
class FileMeta:
    name: str
    created_on: str
    size: int
    mime_type: str


class SessionManager:
    """Manages temp upload sessions under ``{inbox}/{device_id}/{session_id}/``.

    Per-file uploads are resumable: bytes are appended to a ``.part`` file and
    the offset is the on-disk length. A per-session completion marker on disk
    lets a restart tell complete sessions from in-progress ones.
    """

    def __init__(self, inbox_base: Path):
        self._inbox = Path(inbox_base)
        self._lock = threading.RLock()

    # -- session dirs ----------------------------------------------------

    def session_dir(self, device_id: str, session_id: str) -> Path:
        return self._inbox / device_id / session_id

    def _find_session(self, session_id: str) -> Path | None:
        if not self._inbox.is_dir():
            return None
        for device_dir in self._inbox.iterdir():
            candidate = device_dir / session_id
            # A malicious session_id with .. must not resolve outside the inbox.
            if candidate.is_dir() and _is_path_within(candidate, self._inbox):
                return candidate
        return None

    def create_session(self, device_id: str, profile_id: str) -> str:
        session_id = secrets.token_hex(8)
        with self._lock:
            sdir = self.session_dir(device_id, session_id)
            sdir.mkdir(parents=True, exist_ok=True)
            (sdir / _SESSION_META).write_text(
                json.dumps(
                    {"device_id": device_id, "profile_id": profile_id, "created_at": _now()}
                ),
                encoding="utf-8",
            )
        return session_id

    def session_meta(self, session_id: str) -> dict | None:
        sdir = self._find_session(session_id)
        if sdir is None:
            return None
        meta_file = sdir / _SESSION_META
        if not meta_file.exists():
            return None
        return json.loads(meta_file.read_text(encoding="utf-8"))

    # -- file uploads ----------------------------------------------------

    def _safe_child(self, sdir: Path, file_id: str, suffix: str) -> Path:
        """Build ``{sdir}/{file_id}{suffix}``, rejecting any file_id that is not a
        bare name or that would escape the session dir."""
        if Path(file_id).name != file_id:
            raise ValueError(f"invalid file_id: {file_id!r}")
        child = sdir / f"{file_id}{suffix}"
        if not _is_path_within(child, sdir):
            raise ValueError(f"invalid file_id: {file_id!r}")
        return child

    def _part_path(self, sdir: Path, file_id: str) -> Path:
        return self._safe_child(sdir, file_id, ".part")

    def _meta_path(self, sdir: Path, file_id: str) -> Path:
        return self._safe_child(sdir, file_id, ".meta")

    def file_offset(self, session_id: str, file_id: str) -> int:
        sdir = self._find_session(session_id)
        if sdir is None:
            return 0
        try:
            part = self._part_path(sdir, file_id)
        except ValueError:
            return 0
        return part.stat().st_size if part.exists() else 0

    def write_chunk(
        self,
        session_id: str,
        file_id: str,
        meta: FileMeta,
        offset: int,
        data: bytes,
    ) -> int:
        """Append a chunk at ``offset`` and return the new offset.

        Writes are idempotent on the offset: a chunk whose offset matches the
        current length is appended; a stale offset is ignored.
        """
        with self._lock:
            sdir = self._find_session(session_id)
            if sdir is None:
                raise FileNotFoundError(session_id)
            self._meta_path(sdir, file_id).write_text(
                json.dumps(
                    {
                        "name": meta.name,
                        "created_on": meta.created_on,
                        "size": meta.size,
                        "mime_type": meta.mime_type,
                    }
                ),
                encoding="utf-8",
            )
            part = self._part_path(sdir, file_id)
            current = part.stat().st_size if part.exists() else 0
            if offset != current:
                return current
            with open(part, "ab") as fh:
                fh.write(data)
            return part.stat().st_size

    def uploaded_offsets(
        self, device_id: str
    ) -> dict[tuple[str, str, int], tuple[str, str, int]]:
        """Map identity ``(name, created_on, size)`` -> ``(session_id, file_id,
        uploaded_bytes)`` across this device's **incomplete** sessions.

        Lets a fresh sync run resume an upload that was interrupted before its
        session completed, instead of re-sending bytes the server already holds.
        Completed sessions are excluded (they are finalized by
        ``startup_reconcile``). When the same identity appears in several
        incomplete sessions, the one with the most bytes uploaded wins.
        """
        result: dict[tuple[str, str, int], tuple[str, str, int]] = {}
        device_dir = self._inbox / device_id
        if not device_dir.is_dir():
            return result
        for sdir in device_dir.iterdir():
            if not sdir.is_dir() or (sdir / _COMPLETE_MARKER).exists():
                continue
            for file_id in self.file_ids(sdir):
                fmeta = self.file_meta(sdir, file_id)
                if fmeta is None:
                    continue
                part = self._part_path(sdir, file_id)
                offset = part.stat().st_size if part.exists() else 0
                if offset <= 0:
                    continue
                key = (fmeta.name, fmeta.created_on, fmeta.size)
                prev = result.get(key)
                if prev is None or offset > prev[2]:
                    result[key] = (sdir.name, file_id, offset)
        return result

    def file_meta(self, sdir: Path, file_id: str) -> FileMeta | None:
        mp = self._meta_path(sdir, file_id)
        if not mp.exists():
            return None
        raw = json.loads(mp.read_text(encoding="utf-8"))
        return FileMeta(raw["name"], raw["created_on"], raw["size"], raw["mime_type"])

    def file_ids(self, sdir: Path) -> list[str]:
        return sorted(p.stem for p in sdir.glob("*.meta"))

    # -- completion ------------------------------------------------------

    def complete(self, session_id: str) -> None:
        sdir = self._find_session(session_id)
        if sdir is None:
            raise FileNotFoundError(session_id)
        (sdir / _COMPLETE_MARKER).write_text(_now(), encoding="utf-8")

    def is_complete(self, session_id: str) -> bool:
        sdir = self._find_session(session_id)
        return sdir is not None and (sdir / _COMPLETE_MARKER).exists()

    def find_session(self, session_id: str) -> Path | None:
        return self._find_session(session_id)

    def list_sessions(self) -> list[tuple[str, Path]]:
        """Return (session_id, dir) for every session under the inbox."""
        result: list[tuple[str, Path]] = []
        if not self._inbox.is_dir():
            return result
        for device_dir in self._inbox.iterdir():
            if not device_dir.is_dir():
                continue
            for sdir in device_dir.iterdir():
                if sdir.is_dir():
                    result.append((sdir.name, sdir))
        return result

    def last_activity(self, sdir: Path) -> datetime:
        """Most-recent mtime among the session's files (its freshness)."""
        latest = sdir.stat().st_mtime
        for p in sdir.iterdir():
            latest = max(latest, p.stat().st_mtime)
        return datetime.fromtimestamp(latest, tz=timezone.utc)

    def remove_session(self, sdir: Path) -> None:
        shutil.rmtree(sdir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Routing & placement
# ---------------------------------------------------------------------------

def _is_path_within(child: Path, parent: Path) -> bool:
    """True iff ``child`` resolves to a location inside ``parent`` (no escape)."""
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def _is_video(mime_type: str) -> bool:
    return mime_type.lower().startswith("video/")


def _parse_created_on(value: str) -> datetime:
    """Parse the phone-authoritative created_on; fall back to epoch on garbage."""
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return datetime.fromtimestamp(0)


def place_file(
    part_path: Path,
    meta: FileMeta,
    config,
    detect_tags,
) -> tuple[bool, Path | None, FailureReason | None]:
    """Route a single uploaded file to its destination, honoring the profile's
    ``on_collision`` policy on a genuine filename clash.

    With ``on_collision='rename'`` (the default) a clash is renamed so both files
    survive. With ``on_collision='skip'`` a clash leaves the existing file in place
    and the uploaded file is treated as already backed up: ``ok`` is True and the
    returned path is the existing destination file.

    ``detect_tags`` is a callable ``(Path) -> set[str]`` used to classify
    images; it is never called for videos. Returns
    ``(ok, stored_path, failure_reason)``.
    """
    from imagesorter.sorter import _select_group, _build_dest_dir, _transfer_with_policy

    if part_path.stat().st_size != meta.size:
        return False, None, FailureReason.SIZE_MISMATCH

    # The phone-supplied name is untrusted: reject anything that is not a bare
    # basename so it can never escape the session folder during the rename.
    session_dir = part_path.parent
    named = session_dir / meta.name
    if Path(meta.name).name != meta.name or not _is_path_within(named, session_dir):
        return False, None, FailureReason.UNREADABLE
    if named != part_path:
        os.replace(part_path, named)

    dt = _parse_created_on(meta.created_on)
    try:
        if _is_video(meta.mime_type):
            video = config.video
            if video is None:
                # No video destination configured: this never improves on retry.
                return False, None, FailureReason.NO_VIDEO_DESTINATION
            dest_dir = _build_dest_dir(
                video.destination, dt, video.group_by_year, video.group_by_month
            )
        else:
            try:
                detected = detect_tags(named)
            except Exception:
                # A file the classifier cannot decode will not improve on retry.
                return False, None, FailureReason.UNREADABLE
            group = _select_group(detected, config.tag_groups)
            if group is not None:
                dest_dir = _build_dest_dir(
                    group.destination, dt, group.group_by_year, group.group_by_month
                )
            else:
                base = str(
                    Path(config.unclassified.destination) / config.unclassified.folder_name
                )
                dest_dir = _build_dest_dir(
                    base, dt, config.unclassified.group_by_year, config.unclassified.group_by_month
                )
        # Honor the profile's collision policy ('rename' default, or 'skip').
        stored = _transfer_with_policy(named, dest_dir, copy=False, on_collision=config.on_collision)
    except Exception:
        return False, None, FailureReason.PLACEMENT_ERROR
    if stored is None:
        # transfer() returns None only under on_collision='skip' when the name is
        # already present. The file is treated as already backed up; point the
        # synced index at the existing destination file.
        if config.on_collision == "skip":
            return True, dest_dir / named.name, None
        return False, None, FailureReason.PLACEMENT_ERROR
    return True, stored, None


# ---------------------------------------------------------------------------
# Processing lane (independent of the manual-sort job)
# ---------------------------------------------------------------------------

class SyncLane:
    """Processes completed upload sessions in its own thread.

    This lane is entirely separate from the launcher's single manual-sort
    ``_JobState``, so a manual sort and a phone backup never block each other.
    Classification is injected via ``detect_tags`` so tests never load YOLO.
    """

    def __init__(
        self, store: SyncStore, sessions: SessionManager, load_config, detect_tags,
        max_attempts: int = 2,
    ):
        self._store = store
        self._sessions = sessions
        self._load_config = load_config
        self._detect_tags = detect_tags
        self._max_attempts = max_attempts
        self._queue: list[str] = []
        self._lock = threading.Lock()
        self._cv = threading.Condition(self._lock)
        self._worker: threading.Thread | None = None
        self._running = False

    def enqueue(self, session_id: str) -> None:
        with self._cv:
            self._queue.append(session_id)
            self._cv.notify()

    def start(self) -> None:
        if self._worker is not None:
            return
        self._running = True
        self._worker = threading.Thread(target=self._run, daemon=True)
        self._worker.start()

    def _run(self) -> None:
        while True:
            with self._cv:
                while self._running and not self._queue:
                    self._cv.wait()
                if not self._running and not self._queue:
                    return
                session_id = self._queue.pop(0)
            self.process_session(session_id)

    def process_session(self, session_id: str) -> list[dict]:
        """Place every uploaded file in a session, commit index rows, write
        per-file outcomes, and clean up the session folder when emptied."""
        sdir = self._sessions.find_session(session_id)
        if sdir is None:
            return []
        meta = self._sessions.session_meta(session_id)
        if meta is None:
            return []
        config = self._load_config(meta["profile_id"])
        device_id = meta["device_id"]
        profile_id = meta["profile_id"]

        outcomes: list[dict] = []
        for file_id in self._sessions.file_ids(sdir):
            fmeta = self._sessions.file_meta(sdir, file_id)
            if fmeta is None:
                continue
            part = sdir / f"{file_id}.part"
            ok, stored, reason = self._attempt_with_retry(part, fmeta, config)
            if ok and stored is not None:
                self._store.record_synced(
                    fmeta.name, fmeta.created_on, fmeta.size, fmeta.mime_type,
                    str(stored), device_id, profile_id,
                )
                outcome = {"file_id": file_id, "name": fmeta.name, "status": "synced"}
            else:
                # Failed: delete the temp copy (phone keeps the original).
                for leftover in (part, sdir / fmeta.name):
                    if leftover.exists():
                        leftover.unlink()
                outcome = {
                    "file_id": file_id,
                    "name": fmeta.name,
                    "status": "failed",
                    "reason": reason.value if reason else FailureReason.INTERNAL_ERROR.value,
                    "retryable": reason.retryable if reason else False,
                }
            self._store.record_outcome(session_id, outcome)
            outcomes.append(outcome)

        self._cleanup_if_empty(sdir)
        return outcomes

    def _attempt_with_retry(self, part: Path, fmeta: FileMeta, config):
        """Place a file, auto-retrying retryable failures up to max_attempts.

        A terminal failure (e.g. an unreadable file) is never retried.
        """
        ok, stored, reason = False, None, FailureReason.INTERNAL_ERROR
        for _ in range(self._max_attempts):
            try:
                ok, stored, reason = place_file(part, fmeta, config, self._detect_tags)
            except Exception:
                ok, stored, reason = False, None, FailureReason.INTERNAL_ERROR
            if ok:
                return ok, stored, reason
            if reason is None or not reason.retryable:
                return ok, stored, reason
        return ok, stored, reason

    def read_outcomes(self, session_id: str) -> list[dict]:
        return self._store.outcomes_for(session_id)

    def _cleanup_if_empty(self, sdir: Path) -> None:
        """Remove the session folder once every uploaded file has moved out."""
        if not list(sdir.glob("*.part")):
            shutil.rmtree(sdir, ignore_errors=True)

    def startup_reconcile(self, ttl: timedelta) -> None:
        """Re-enqueue complete-but-unprocessed sessions, preserve in-progress
        ones, and drop incomplete sessions past the abandoned TTL."""
        now = datetime.now(timezone.utc)
        for session_id, sdir in self._sessions.list_sessions():
            if self._sessions.is_complete(session_id):
                if not self._store.has_outcomes(session_id):
                    self.process_session(session_id)
                continue
            # Incomplete: keep if fresh, drop if abandoned past the TTL.
            if now - self._sessions.last_activity(sdir) >= ttl:
                self._sessions.remove_session(sdir)

    def janitor(self, ttl: timedelta) -> None:
        """Remove abandoned incomplete sessions older than the TTL; never touch
        an in-progress (fresh) upload."""
        now = datetime.now(timezone.utc)
        for session_id, sdir in self._sessions.list_sessions():
            if self._sessions.is_complete(session_id):
                continue
            if now - self._sessions.last_activity(sdir) >= ttl:
                self._sessions.remove_session(sdir)
