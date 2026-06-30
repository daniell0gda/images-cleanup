"""Pure HTTP helpers for the media routes: keyset cursors, date parsing, and the
per-row path guard. No FastAPI app state — just request-shaping logic that
``server.py`` imports, so the route layer stays focused on wiring.
"""
import base64
import binascii
from datetime import datetime
from pathlib import Path


def encode_cursor(date_taken: str, media_id: int) -> str:
    """Opaque keyset cursor for ``(date_taken, id)``: base64 of ``date_taken|id``."""
    raw = f"{date_taken}|{media_id}".encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii")


def decode_cursor(cursor: str) -> tuple[str, int]:
    """Decode a cursor produced by :func:`encode_cursor` back to ``(date_taken, id)``."""
    try:
        raw = base64.urlsafe_b64decode(cursor.encode("ascii")).decode("utf-8")
        date_taken, last_id = raw.rsplit("|", 1)
        return date_taken, int(last_id)
    except (binascii.Error, UnicodeDecodeError, ValueError):
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail="Invalid cursor")


def parse_from_date(value: str) -> str:
    """Validate a ``from_date`` query value as a ``YYYY-MM-DD`` calendar date.

    Returns the normalised ``YYYY-MM-DD`` string; raises HTTP 422 for any
    malformed or out-of-range date so the seek parameter never reaches SQL.
    """
    from fastapi import HTTPException
    try:
        return datetime.strptime(value, "%Y-%m-%d").strftime("%Y-%m-%d")
    except ValueError:
        raise HTTPException(status_code=422, detail="Invalid from_date; expected YYYY-MM-DD")


def assert_within_root(path: Path, root: str) -> None:
    """Raise 403 unless ``path`` resolves inside its indexed ``root``.

    Each media row records the configured folder it was discovered under at index
    time (the ``root`` column); ``path`` is under ``root`` by construction. We
    validate against the row's OWN root rather than the live ``media_library``
    folder list so that already-indexed media stay servable even after the folder
    list is edited — while still rejecting any path that does not sit under the
    root it was indexed from (anti-traversal defence-in-depth). Requesting only
    works for ids that exist in the index (and, for share, are album members)."""
    from fastapi import HTTPException
    resolved = path.resolve()
    root_resolved = Path(root).resolve()
    if resolved == root_resolved or root_resolved in resolved.parents:
        return
    raise HTTPException(status_code=403, detail="Path outside configured roots")
