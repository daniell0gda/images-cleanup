"""Tests for launcher server settings — media_library config + endpoints."""
from __future__ import annotations

import os
from pathlib import Path

from fastapi.testclient import TestClient


def make_app(tmp_path: Path, sync_scheduler=None):
    """Build a fresh launcher app with env vars pointed at tmp_path."""
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_scheduler=sync_scheduler)


# ---------------------------------------------------------------------------
# settings.py — defaults merge
# ---------------------------------------------------------------------------

def test_media_library_defaults_merged_over_missing_file(tmp_path):
    """With no server.yaml, load_settings yields full media_library defaults
    while leaving db_refresh intact."""
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    loaded = s.load_settings(configs)
    assert loaded["media_library"] == {
        "enabled": True,
        "folders": [],
        "schedule": "0 2 * * *",
    }
    # db_refresh remains backward-compatible.
    assert loaded["db_refresh"] == {"enabled": True, "schedule": "0 1 * * *"}


# ---------------------------------------------------------------------------
# settings.py — save round-trip + cron validation
# ---------------------------------------------------------------------------

def test_save_settings_persists_media_library_roundtrip(tmp_path):
    """save_settings stores media_library (folders + schedule + enabled) so a
    subsequent load_settings returns the same values, and db_refresh is kept."""
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    saved = s.save_settings(
        configs,
        enabled=False,
        schedule="30 3 * * *",
        media_library={
            "enabled": True,
            "folders": ["/srv/a", "/srv/b"],
            "schedule": "0 4 * * *",
        },
    )
    assert saved["media_library"] == {
        "enabled": True,
        "folders": ["/srv/a", "/srv/b"],
        "schedule": "0 4 * * *",
    }

    reloaded = s.load_settings(configs)
    assert reloaded["media_library"] == {
        "enabled": True,
        "folders": ["/srv/a", "/srv/b"],
        "schedule": "0 4 * * *",
    }
    assert reloaded["db_refresh"] == {"enabled": False, "schedule": "30 3 * * *"}


def test_save_settings_rejects_invalid_media_cron(tmp_path):
    """An invalid media_library cron raises ValueError and writes nothing."""
    import pytest
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    with pytest.raises(ValueError):
        s.save_settings(
            configs,
            enabled=True,
            schedule="0 1 * * *",
            media_library={"enabled": True, "folders": [], "schedule": "nope"},
        )
    assert not (configs / "server.yaml").is_file()


def test_save_settings_rejects_non_string_folder(tmp_path):
    """Each folder must be a non-empty string; an empty folder is rejected."""
    import pytest
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    with pytest.raises(ValueError):
        s.save_settings(
            configs,
            enabled=True,
            schedule="0 1 * * *",
            media_library={"enabled": True, "folders": [""], "schedule": "0 2 * * *"},
        )


# ---------------------------------------------------------------------------
# endpoints — GET/POST /api/settings media_library block
# ---------------------------------------------------------------------------

def test_get_settings_includes_media_library_with_next_run(tmp_path):
    """GET /api/settings exposes the media_library block with a computed
    next_run and a (null) last-build summary."""
    app = make_app(tmp_path)
    client = TestClient(app)

    body = client.get("/api/settings").json()
    ml = body["media_library"]
    assert ml["enabled"] is True
    assert ml["folders"] == []
    assert ml["schedule"] == "0 2 * * *"
    assert ml["next_run"] is not None
    # last-build summary is null until the build subsystem exists.
    assert body["media_build"] is None


def test_get_settings_next_run_null_when_media_disabled(tmp_path):
    """When media_library is disabled, next_run is null."""
    app = make_app(tmp_path)
    client = TestClient(app)

    client.post("/api/settings", json={
        "db_refresh": {"enabled": True, "schedule": "0 1 * * *"},
        "media_library": {"enabled": False, "folders": [], "schedule": "0 2 * * *"},
    })
    ml = client.get("/api/settings").json()["media_library"]
    assert ml["enabled"] is False
    assert ml["next_run"] is None


def test_post_settings_persists_media_and_rejects_bad_cron(tmp_path):
    """POST /api/settings saves media_library and round-trips; an invalid media
    cron is rejected with 400 without overwriting the stored value."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/settings", json={
        "db_refresh": {"enabled": True, "schedule": "0 1 * * *"},
        "media_library": {"enabled": True, "folders": ["/srv/pics"], "schedule": "0 5 * * *"},
    })
    assert ok.status_code == 200, ok.text
    assert ok.json()["media_library"]["folders"] == ["/srv/pics"]
    assert ok.json()["media_library"]["schedule"] == "0 5 * * *"
    assert client.get("/api/settings").json()["media_library"]["schedule"] == "0 5 * * *"

    bad = client.post("/api/settings", json={
        "db_refresh": {"enabled": True, "schedule": "0 1 * * *"},
        "media_library": {"enabled": True, "folders": [], "schedule": "not a cron"},
    })
    assert bad.status_code == 400
    assert client.get("/api/settings").json()["media_library"]["schedule"] == "0 5 * * *"


def test_post_settings_reschedules_media_cron(tmp_path):
    """POST /api/settings reconfigures the media cron without a restart: the
    media _CronScheduler is rescheduled to the saved schedule/enabled."""
    def scheduler(interval, fn):
        pass

    app = make_app(tmp_path, sync_scheduler=scheduler)
    client = TestClient(app)

    media_cron = app.state.media_cron
    assert media_cron is not None
    # Initialized from defaults on startup.
    assert media_cron.schedule == "0 2 * * *"
    assert media_cron.enabled is True

    client.post("/api/settings", json={
        "db_refresh": {"enabled": True, "schedule": "0 1 * * *"},
        "media_library": {"enabled": False, "folders": [], "schedule": "15 6 * * *"},
    })
    assert media_cron.schedule == "15 6 * * *"
    assert media_cron.enabled is False


def test_media_library_partial_file_merges_defaults(tmp_path):
    """A server.yaml that only sets media_library.folders fills the rest from
    defaults and keeps db_refresh untouched."""
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()
    (configs / "server.yaml").write_text(
        "media_library:\n  folders:\n    - /srv/pics\n", encoding="utf-8"
    )

    loaded = s.load_settings(configs)
    assert loaded["media_library"] == {
        "enabled": True,
        "folders": ["/srv/pics"],
        "schedule": "0 2 * * *",
    }
    assert loaded["db_refresh"] == {"enabled": True, "schedule": "0 1 * * *"}
