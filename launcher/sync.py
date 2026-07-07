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


def is_safe_device_id(device_id: str) -> bool:
    """True iff ``device_id`` is a bare safe token usable as a directory name.

    A device_id is used verbatim as a path component for its inbox/session
    directories, so anything with path separators or a ``..`` component could
    escape the inbox and must be rejected.
    """
    return bool(device_id) and Path(device_id).name == device_id and device_id not in (".", "..")


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
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id    TEXT PRIMARY KEY,
                    device_id     TEXT NOT NULL,
                    profile_id    TEXT NOT NULL,
                    created_at    TEXT NOT NULL
                );
                """
            )
            con.commit()

    # -- devices ---------------------------------------------------------

    def register_device(self, device_id: str, name: str) -> dict:
        """Register a device and return its resulting pairing state.

        - Absent device_id: inserted as ``pending`` with a fresh 6-digit code;
          returns ``{"status": "pending", "pairing_code": code}``.
        - Existing ``pending`` device: refreshes its pairing code; returns the
          same pending shape.
        - Existing ``trusted`` device: a SAFE NO-OP. The token and ``trusted``
          status are left intact (never reset), no new code is minted, and it
          returns ``{"status": "trusted"}`` so a re-register can never remotely
          de-authenticate an approved phone.

        Rejects a device_id that is not a bare safe token, since it is used as a
        directory component for the device's inbox/session folders.
        """
        if not is_safe_device_id(device_id):
            raise ValueError(f"invalid device_id: {device_id!r}")
        with self._lock:
            existing = self.get_device(device_id)
            if existing is not None and existing["status"] == "trusted":
                # Preserve trust: do not touch token, status, or pairing code.
                return {"status": "trusted"}
            code = f"{secrets.randbelow(1_000_000):06d}"
            if existing is None:
                self._conn().execute(
                    "INSERT INTO devices "
                    "(device_id, name, token, status, pairing_code, created_at, approved_at) "
                    "VALUES (?, ?, NULL, 'pending', ?, ?, NULL)",
                    (device_id, name, code, _now()),
                )
            else:
                # Existing non-trusted device (pending/revoked): refresh its code
                # without wiping the row wholesale.
                self._conn().execute(
                    "UPDATE devices SET name=?, pairing_code=? WHERE device_id=?",
                    (name, code, device_id),
                )
            self._conn().commit()
        return {"status": "pending", "pairing_code": code}

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

    # -- session registry (durable session->device ownership) ------------

    def record_session(self, session_id: str, device_id: str, profile_id: str) -> None:
        """Durably record which device owns a session.

        Kept independent of the on-disk session folder so ownership checks and
        idempotent completion still work after the folder is cleaned up.
        """
        with self._lock:
            self._conn().execute(
                "INSERT OR REPLACE INTO sessions "
                "(session_id, device_id, profile_id, created_at) VALUES (?, ?, ?, ?)",
                (session_id, device_id, profile_id, _now()),
            )
            self._conn().commit()

    def session_owner(self, session_id: str) -> str | None:
        """Return the device_id that owns a session, or None if unknown."""
        with self._lock:
            row = self._conn().execute(
                "SELECT device_id FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
        return row["device_id"] if row else None

    def stored_path_for(self, name: str, created_on: str, size: int) -> str | None:
        with self._lock:
            row = self._conn().execute(
                "SELECT stored_path FROM synced_files "
                "WHERE name=? AND created_on=? AND size=?",
                (name, created_on, size),
            ).fetchone()
        return row["stored_path"] if row else None

    def profile_for_path(self, stored_path: str) -> str | None:
        """Return the display name of the profile that synced the file at
        ``stored_path``, or None if the file was not placed via phone sync.

        Backs the media index's ``profile`` column so the gallery can filter by
        who backed a photo up. Matches ``stored_path`` verbatim, matching how
        :meth:`refresh_index` reconciles the index against disk.
        """
        with self._lock:
            row = self._conn().execute(
                "SELECT profile_id FROM synced_files WHERE stored_path=?",
                (stored_path,),
            ).fetchone()
        return profile_display_name(row["profile_id"]) if row else None

    def refresh_index(self) -> dict:
        """Re-validate the synced-file index against the destination on disk.

        A file deleted from the destination outside the app (manually, or by
        another tool) would otherwise stay "synced" forever and never be
        re-uploaded. This drops the index row for every recorded file whose
        ``stored_path`` is gone, so the next device reconcile re-queues it.

        Returns ``{"checked": N, "removed": M}``. Disk stats run outside the
        lock — a full destination may live on a slow share — and each prune
        takes the lock only briefly.
        """
        with self._lock:
            rows = self._conn().execute(
                "SELECT name, created_on, size, stored_path FROM synced_files"
            ).fetchall()
        removed = 0
        for r in rows:
            stored = r["stored_path"]
            if stored and Path(stored).exists():
                continue
            with self._lock:
                self._conn().execute(
                    "DELETE FROM synced_files WHERE name=? AND created_on=? AND size=?",
                    (r["name"], r["created_on"], r["size"]),
                )
                self._conn().commit()
            removed += 1
        return {"checked": len(rows), "removed": removed}


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
        # Per-session write locks so chunk appends to different sessions never
        # serialize against each other; a tiny meta-lock guards the registry.
        self._write_locks: dict[str, threading.Lock] = {}
        self._write_locks_guard = threading.Lock()

    def _session_write_lock(self, session_id: str) -> threading.Lock:
        with self._write_locks_guard:
            lock = self._write_locks.get(session_id)
            if lock is None:
                lock = threading.Lock()
                self._write_locks[session_id] = lock
            return lock

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

    def create_session(
        self, device_id: str, profile_id: str, force_place: bool = False
    ) -> str:
        session_id = secrets.token_hex(8)
        with self._lock:
            sdir = self.session_dir(device_id, session_id)
            sdir.mkdir(parents=True, exist_ok=True)
            (sdir / _SESSION_META).write_text(
                json.dumps(
                    {
                        "device_id": device_id,
                        "profile_id": profile_id,
                        "force_place": force_place,
                        "created_at": _now(),
                    }
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
        current length is appended; a stale offset is ignored. Held under a
        per-session lock so two sessions can be written concurrently while a
        single session's appends stay serialized.
        """
        with self._session_write_lock(session_id):
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


class _Discarded:
    """Sentinel returned by :func:`place_file` in the ``stored`` slot when an
    image is discarded (matched no tag_group under ``discard_unclassified``).

    It marks a result that is neither synced (``ok`` + a real path) nor failed
    (a ``FailureReason``): the file was intentionally not placed anywhere.
    """

    __slots__ = ()

    def __repr__(self) -> str:  # pragma: no cover - debug aid
        return "DISCARDED"


DISCARDED = _Discarded()


def _is_video(mime_type: str) -> bool:
    return mime_type.lower().startswith("video/")


def _parse_created_on(value: str) -> datetime:
    """Parse the phone-authoritative created_on; fall back to epoch on garbage."""
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return datetime.fromtimestamp(0)


def profile_display_name(profile_id: str) -> str:
    """Human-facing profile name embedded in tags and shown in the gallery.

    Profiles are keyed as ``<user>_groupby`` (see :func:`list_profiles`); the
    display name is just ``<user>``.
    """
    suffix = "_groupby"
    return profile_id[: -len(suffix)] if profile_id.endswith(suffix) else profile_id


_JPEG_SUFFIXES = {".jpg", ".jpeg"}


def embed_profile_tag(path: Path, display_name: str) -> None:
    """Record which sync profile placed this photo in its EXIF ``XPKeywords``
    (the "Tags" field Windows Explorer shows), as ``profile:<display_name>``.

    Best-effort and lossless: only JPEG files are tagged — ``piexif.insert``
    rewrites the metadata segment without re-encoding pixels — and any failure
    (a non-JPEG payload, an unreadable file) is swallowed so tagging can never
    turn a completed backup into a failure.
    """
    if path.suffix.lower() not in _JPEG_SUFFIXES:
        return
    try:
        import piexif

        exif = piexif.load(str(path))
        # XPKeywords is a UTF-16LE, null-terminated byte string.
        exif["0th"][piexif.ImageIFD.XPKeywords] = (
            f"profile:{display_name}".encode("utf-16le") + b"\x00\x00"
        )
        piexif.insert(piexif.dump(exif), str(path))
    except Exception:
        pass


def place_file(
    part_path: Path,
    meta: FileMeta,
    config,
    detect_tags,
    discard_unclassified: bool = False,
    force_place: bool = False,
) -> tuple[bool, Path | None | _Discarded, FailureReason | None]:
    """Route a single uploaded file to its destination, honoring the profile's
    ``on_collision`` policy on a genuine filename clash.

    With ``on_collision='rename'`` (the default) a clash is renamed so both files
    survive. With ``on_collision='skip'`` a clash leaves the existing file in place
    and the uploaded file is treated as already backed up: ``ok`` is True and the
    returned path is the existing destination file.

    ``detect_tags`` is a callable ``(Path) -> set[str]`` used to classify
    images; it is never called for videos. Returns
    ``(ok, stored_path, failure_reason)``.

    The sync lane passes ``discard_unclassified=True`` so an image matching no
    tag_group is not placed in the unclassified folder; instead the result is
    ``(False, DISCARDED, None)`` — distinguishable from synced and failed. The
    default (``discard_unclassified=False``) keeps the CLI sorter's behavior of
    placing such images in the unclassified destination.

    ``force_place=True`` skips image classification entirely and places every
    image into the primary tag_group (``config.tag_groups[0]``).
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
        elif force_place:
            # Force mode: skip classification and place into the primary group.
            primary = config.tag_groups[0]
            dest_dir = _build_dest_dir(
                primary.destination, dt, primary.group_by_year, primary.group_by_month
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
            elif discard_unclassified:
                # Sync lane: do not place unclassified images anywhere. Remove
                # the renamed copy so it does not linger in the session folder.
                if named.exists():
                    named.unlink()
                return False, DISCARDED, None
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
        # Per-session processing locks so two callers (e.g. the complete route
        # and startup_reconcile) never place the same session's files twice.
        self._session_locks: dict[str, threading.Lock] = {}
        self._session_locks_guard = threading.Lock()

    def _session_lock(self, session_id: str) -> threading.Lock:
        with self._session_locks_guard:
            lock = self._session_locks.get(session_id)
            if lock is None:
                lock = threading.Lock()
                self._session_locks[session_id] = lock
            return lock

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
        per-file outcomes, and clean up the session folder when emptied.

        A per-session lock plus an idempotency short-circuit make this safe to
        call concurrently or back-to-back for the same session: only the first
        caller places the files; later callers return the recorded outcomes.
        """
        with self._session_lock(session_id):
            if self._store.has_outcomes(session_id):
                return self._store.outcomes_for(session_id)
            return self._process_session_locked(session_id)

    def _process_session_locked(self, session_id: str) -> list[dict]:
        sdir = self._sessions.find_session(session_id)
        if sdir is None:
            return []
        meta = self._sessions.session_meta(session_id)
        if meta is None:
            return []
        config = self._load_config(meta["profile_id"])
        device_id = meta["device_id"]
        profile_id = meta["profile_id"]
        force_place = bool(meta.get("force_place"))

        outcomes: list[dict] = []
        for file_id in self._sessions.file_ids(sdir):
            fmeta = self._sessions.file_meta(sdir, file_id)
            if fmeta is None:
                continue
            part = sdir / f"{file_id}.part"
            ok, stored, reason = self._attempt_with_retry(part, fmeta, config, force_place)
            if stored is DISCARDED:
                # Matched no tag_group in the sync lane: not placed, not failed.
                # place_file already removed the renamed copy; drop the .part too.
                if part.exists():
                    part.unlink()
                outcome = {"file_id": file_id, "name": fmeta.name, "status": "unclassified"}
            elif ok and stored is not None:
                # Stamp the syncing profile into the photo's own metadata so it
                # travels with the file and can later be used to filter photos.
                embed_profile_tag(stored, profile_display_name(profile_id))
                self._store.record_synced(
                    fmeta.name, fmeta.created_on, fmeta.size, fmeta.mime_type,
                    str(stored), device_id, profile_id,
                )
                outcome = {"file_id": file_id, "name": fmeta.name, "status": "synced"}
            else:
                # Failed: delete the temp copy (phone keeps the original). The
                # stored name is untrusted; only ever unlink bare-basename
                # children that stay inside the session dir so a traversal name
                # like "../../x" can never reach a file outside it.
                leftovers = [part]
                if Path(fmeta.name).name == fmeta.name:
                    named = sdir / fmeta.name
                    if _is_path_within(named, sdir):
                        leftovers.append(named)
                for leftover in leftovers:
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

    def _attempt_with_retry(self, part: Path, fmeta: FileMeta, config, force_place: bool = False):
        """Place a file, auto-retrying retryable failures up to max_attempts.

        A terminal failure (e.g. an unreadable file) is never retried. The sync
        lane never places unclassified images (``discard_unclassified=True``); in
        ``force_place`` mode images skip classification and go to the primary group.
        """
        ok, stored, reason = False, None, FailureReason.INTERNAL_ERROR
        for _ in range(self._max_attempts):
            try:
                ok, stored, reason = place_file(
                    part, fmeta, config, self._detect_tags,
                    discard_unclassified=True, force_place=force_place,
                )
            except Exception:
                ok, stored, reason = False, None, FailureReason.INTERNAL_ERROR
            if ok or stored is DISCARDED:
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
