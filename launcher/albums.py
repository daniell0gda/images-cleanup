"""Album data layer: CRUD, membership, and share-token operations.

Albums live in the same ``media.db`` as the media index (spec §3, §4) so that
membership cascades when a media row is pruned. ``AlbumStore`` opens its own
connection with ``PRAGMA foreign_keys=ON`` so membership/prune cascades fire.
"""
from __future__ import annotations

import secrets
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class AlbumStore:
    """SQLite-backed store for albums, membership, and share tokens."""

    def __init__(self, db_path: Path):
        self._db_path = Path(db_path)
        self._lock = threading.RLock()
        self._con: sqlite3.Connection | None = None

    def _conn(self) -> sqlite3.Connection:
        with self._lock:
            con = self._con
            if con is None:
                con = sqlite3.connect(str(self._db_path), check_same_thread=False)
                con.row_factory = sqlite3.Row
                con.execute("PRAGMA foreign_keys=ON")
                self._con = con
            return con

    def create(self, name: str, created_by: str | None = None) -> dict:
        """Create an unshared album and return its row as a dict."""
        with self._lock:
            con = self._conn()
            cur = con.execute(
                "INSERT INTO album (name, created_by, created_at, share_token) "
                "VALUES (?, ?, ?, NULL)",
                (name, created_by, _now_iso()),
            )
            con.commit()
            album_id = cur.lastrowid
        return self._row(album_id)

    def list_albums(self) -> list[dict]:
        """All album rows, newest-created first."""
        with self._lock:
            rows = self._conn().execute(
                "SELECT id, name, created_by, created_at, share_token "
                "FROM album ORDER BY created_at DESC, id DESC"
            ).fetchall()
        return [dict(r) for r in rows]

    def rename(self, album_id: int, name: str) -> None:
        """Set the album's display name."""
        with self._lock:
            con = self._conn()
            con.execute("UPDATE album SET name=? WHERE id=?", (name, album_id))
            con.commit()

    def add_items(self, album_id: int, media_ids: list[int]) -> None:
        """Add ``media_ids`` to the album. Idempotent; unknown ids are ignored."""
        if not media_ids:
            return
        added_at = _now_iso()
        with self._lock:
            con = self._conn()
            known = {
                r["id"]
                for r in con.execute(
                    "SELECT id FROM media WHERE id IN (%s)"
                    % ",".join("?" * len(media_ids)),
                    media_ids,
                ).fetchall()
            }
            con.executemany(
                "INSERT OR IGNORE INTO album_item (album_id, media_id, added_at) "
                "VALUES (?, ?, ?)",
                [(album_id, mid, added_at) for mid in media_ids if mid in known],
            )
            con.commit()

    def remove_items(self, album_id: int, media_ids: list[int]) -> None:
        """Drop the named membership rows; never touches the ``media`` rows."""
        if not media_ids:
            return
        with self._lock:
            con = self._conn()
            con.execute(
                "DELETE FROM album_item WHERE album_id=? AND media_id IN (%s)"
                % ",".join("?" * len(media_ids)),
                [album_id, *media_ids],
            )
            con.commit()

    def items(self, album_id: int) -> list[dict]:
        """Album media rows newest-first by ``date_taken`` (then id desc)."""
        with self._lock:
            rows = self._conn().execute(
                "SELECT m.* FROM album_item ai "
                "JOIN media m ON m.id = ai.media_id "
                "WHERE ai.album_id=? "
                "ORDER BY m.date_taken DESC, m.id DESC",
                (album_id,),
            ).fetchall()
        return [dict(r) for r in rows]

    def count(self, album_id: int) -> int:
        """Number of live media members of the album."""
        with self._lock:
            return self._conn().execute(
                "SELECT COUNT(*) FROM album_item ai "
                "JOIN media m ON m.id = ai.media_id WHERE ai.album_id=?",
                (album_id,),
            ).fetchone()[0]

    def cover_media_id(self, album_id: int) -> int | None:
        """The first item newest-first by ``date_taken`` (None when empty)."""
        items = self.items(album_id)
        return items[0]["id"] if items else None

    def delete(self, album_id: int) -> None:
        """Delete the album; cascade drops its membership rows and token."""
        with self._lock:
            con = self._conn()
            con.execute("DELETE FROM album WHERE id=?", (album_id,))
            con.commit()

    def share(self, album_id: int) -> str:
        """Mint a fresh opaque share token (replacing any prior) and return it."""
        token = secrets.token_urlsafe(16)
        with self._lock:
            con = self._conn()
            con.execute(
                "UPDATE album SET share_token=? WHERE id=?", (token, album_id)
            )
            con.commit()
        return token

    def revoke(self, album_id: int) -> None:
        """Clear the album's share token (the link 404s; album stays)."""
        with self._lock:
            con = self._conn()
            con.execute(
                "UPDATE album SET share_token=NULL WHERE id=?", (album_id,)
            )
            con.commit()

    def by_token(self, token: str) -> dict | None:
        """The album row for a live share token, or None if unknown/revoked."""
        with self._lock:
            row = self._conn().execute(
                "SELECT id, name, created_by, created_at, share_token "
                "FROM album WHERE share_token=?",
                (token,),
            ).fetchone()
        return dict(row) if row is not None else None

    def is_member(self, album_id: int, media_id: int) -> bool:
        """Whether ``media_id`` is a live member of the album."""
        with self._lock:
            row = self._conn().execute(
                "SELECT 1 FROM album_item ai JOIN media m ON m.id = ai.media_id "
                "WHERE ai.album_id=? AND ai.media_id=?",
                (album_id, media_id),
            ).fetchone()
        return row is not None

    def _row(self, album_id: int) -> dict | None:
        with self._lock:
            row = self._conn().execute(
                "SELECT id, name, created_by, created_at, share_token "
                "FROM album WHERE id=?",
                (album_id,),
            ).fetchone()
        return dict(row) if row is not None else None
