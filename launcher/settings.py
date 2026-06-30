"""Global server settings for the launcher (server-wide, not per-user).

Backed by a single YAML file at ``CONFIGS_DIR/server.yaml``, sitting next to the
per-user sync-profile configs. Only the schedule for the periodic synced-file
index refresh lives here today; the loader merges defaults so a missing or
partial file always yields a complete, valid settings dict.
"""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

import yaml

# Default: refresh the synced-file index every day at 01:00 (standard 5-field
# cron). Enabled out of the box so a fresh install self-heals stale rows.
DEFAULTS = {
    "db_refresh": {
        "enabled": True,
        "schedule": "0 1 * * *",
    },
    # Media gallery index: scan configured folders into a pre-built collection on
    # a schedule (default 02:00 daily). Empty folder list out of the box.
    "media_library": {
        "enabled": True,
        "folders": [],
        "schedule": "0 2 * * *",
    },
    # Outer hostname for shared-album links (e.g. https://photos.example.com).
    # Empty = fall back to the incoming request base URL.
    "public_base_url": "",
}

_SETTINGS_FILE = "server.yaml"


def settings_path(configs_dir: Path) -> Path:
    return configs_dir / _SETTINGS_FILE


def normalize_public_base_url(value: str) -> str:
    """Strip a trailing slash and validate a non-empty value is a full
    ``scheme://host`` URL. Returns the normalized string; raises ValueError on a
    non-empty value lacking a scheme or host."""
    from urllib.parse import urlparse

    url = (value or "").strip().rstrip("/")
    if not url:
        return ""
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError(f"Invalid public_base_url: {value!r}")
    return url


def is_valid_schedule(schedule: str) -> bool:
    from croniter import croniter

    return isinstance(schedule, str) and croniter.is_valid(schedule)


def next_run(schedule: str, after: datetime | None = None) -> str | None:
    """ISO timestamp of the next fire after ``after`` (now), or None if the
    expression is invalid."""
    if not is_valid_schedule(schedule):
        return None
    from croniter import croniter

    base = after if after is not None else datetime.now()
    return croniter(schedule, base).get_next(datetime).isoformat()


def load_settings(configs_dir: Path) -> dict:
    """Read server.yaml merged over DEFAULTS. A malformed or absent file falls
    back to defaults rather than raising — the server must always start."""
    path = settings_path(configs_dir)
    raw: dict = {}
    if path.is_file():
        try:
            loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                raw = loaded
        except (OSError, yaml.YAMLError):
            raw = {}
    db = {**DEFAULTS["db_refresh"], **(raw.get("db_refresh") or {})}
    ml = {**DEFAULTS["media_library"], **(raw.get("media_library") or {})}
    pub = raw.get("public_base_url")
    return {
        "db_refresh": {"enabled": bool(db["enabled"]), "schedule": db["schedule"]},
        "media_library": {
            "enabled": bool(ml["enabled"]),
            "folders": list(ml["folders"] or []),
            "schedule": ml["schedule"],
        },
        "public_base_url": str(pub) if isinstance(pub, str) else "",
    }


def save_settings(
    configs_dir: Path,
    enabled: bool,
    schedule: str,
    media_library: dict | None = None,
    public_base_url: str | None = None,
) -> dict:
    """Validate and persist server settings, returning the stored dict.

    Persists ``db_refresh`` plus, when ``media_library`` is given, the media
    gallery config (folders + schedule + enabled). Raises ValueError for an
    invalid cron expression (db_refresh or media) or a folder that is not a
    non-empty string, so the caller can map it to a 400 rather than writing an
    unschedulable value.
    """
    if not is_valid_schedule(schedule):
        raise ValueError(f"Invalid cron schedule: {schedule!r}")
    pub = (
        load_settings(configs_dir)["public_base_url"]
        if public_base_url is None
        else normalize_public_base_url(public_base_url)
    )
    data: dict = {
        "db_refresh": {"enabled": bool(enabled), "schedule": schedule},
        "public_base_url": pub,
    }
    if media_library is not None:
        ml = {**DEFAULTS["media_library"], **media_library}
        if not is_valid_schedule(ml["schedule"]):
            raise ValueError(f"Invalid cron schedule: {ml['schedule']!r}")
        folders = list(ml["folders"] or [])
        for folder in folders:
            if not isinstance(folder, str) or not folder.strip():
                raise ValueError(f"Invalid media folder: {folder!r}")
        data["media_library"] = {
            "enabled": bool(ml["enabled"]),
            "folders": folders,
            "schedule": ml["schedule"],
        }
    configs_dir.mkdir(parents=True, exist_ok=True)
    settings_path(configs_dir).write_text(
        yaml.safe_dump(data, sort_keys=False), encoding="utf-8"
    )
    return data
