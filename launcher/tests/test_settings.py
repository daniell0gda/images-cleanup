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
# settings.py — public_base_url round-trip + validation
# ---------------------------------------------------------------------------

def test_public_base_url_defaults_empty(tmp_path):
    """With no server.yaml, public_base_url defaults to an empty string."""
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    assert s.load_settings(configs)["public_base_url"] == ""


def test_save_settings_roundtrips_public_base_url_trailing_slash(tmp_path):
    """save_settings stores public_base_url with any trailing slash stripped so a
    subsequent load_settings returns the normalized value."""
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    saved = s.save_settings(
        configs,
        enabled=True,
        schedule="0 1 * * *",
        public_base_url="https://photos.example.com/",
    )
    assert saved["public_base_url"] == "https://photos.example.com"
    assert s.load_settings(configs)["public_base_url"] == "https://photos.example.com"


def test_save_settings_rejects_invalid_public_base_url(tmp_path):
    """A non-empty public_base_url without a scheme://host is rejected and nothing
    is written."""
    import pytest
    from launcher import settings as s
    configs = tmp_path / "configs"
    configs.mkdir()

    with pytest.raises(ValueError):
        s.save_settings(
            configs,
            enabled=True,
            schedule="0 1 * * *",
            public_base_url="photos.example.com",
        )
    assert not (configs / "server.yaml").is_file()


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


def test_post_settings_media_only_persists_without_db_refresh(tmp_path):
    """POST /api/settings with only a media_library block (no db_refresh key —
    the exact shape the React saveMedia handler sends) returns 200 and persists
    the media config."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/settings", json={
        "media_library": {"enabled": True, "schedule": "0 7 * * *", "folders": ["/srv/pics"]},
    })
    assert ok.status_code == 200, ok.text
    ml = ok.json()["media_library"]
    assert ml["folders"] == ["/srv/pics"]
    assert ml["schedule"] == "0 7 * * *"
    assert client.get("/api/settings").json()["media_library"]["schedule"] == "0 7 * * *"


def test_post_settings_media_only_leaves_db_refresh_unchanged(tmp_path):
    """A media-only save must not clobber or reschedule the previously stored
    db_refresh settings."""
    def scheduler(interval, fn):
        pass

    app = make_app(tmp_path, sync_scheduler=scheduler)
    client = TestClient(app)

    # Establish a non-default db_refresh first (full save).
    client.post("/api/settings", json={
        "db_refresh": {"enabled": False, "schedule": "30 3 * * *"},
        "media_library": {"enabled": True, "folders": [], "schedule": "0 2 * * *"},
    })
    db_cron = app.state.db_cron
    db_cron.reschedule("30 3 * * *", False)

    # Now a media-only save: db_refresh must survive untouched.
    ok = client.post("/api/settings", json={
        "media_library": {"enabled": True, "schedule": "0 7 * * *", "folders": ["/srv/x"]},
    })
    assert ok.status_code == 200, ok.text
    stored = client.get("/api/settings").json()["db_refresh"]
    assert stored["enabled"] is False
    assert stored["schedule"] == "30 3 * * *"
    assert db_cron.schedule == "30 3 * * *"
    assert db_cron.enabled is False


def test_post_settings_both_blocks_persists_both(tmp_path):
    """Regression guard: posting both db_refresh and media_library still returns
    200 and persists both blocks."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/settings", json={
        "db_refresh": {"enabled": False, "schedule": "30 3 * * *"},
        "media_library": {"enabled": True, "folders": ["/srv/both"], "schedule": "0 5 * * *"},
    })
    assert ok.status_code == 200, ok.text
    full = client.get("/api/settings").json()
    assert full["db_refresh"]["enabled"] is False
    assert full["db_refresh"]["schedule"] == "30 3 * * *"
    assert full["media_library"]["folders"] == ["/srv/both"]
    assert full["media_library"]["schedule"] == "0 5 * * *"


def test_get_settings_includes_public_base_url(tmp_path):
    """GET /api/settings exposes public_base_url, defaulting to empty string."""
    app = make_app(tmp_path)
    client = TestClient(app)

    assert client.get("/api/settings").json()["public_base_url"] == ""


def test_post_settings_roundtrips_public_base_url(tmp_path):
    """POST /api/settings persists public_base_url (trailing slash normalized) and
    GET returns it back."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/settings", json={"public_base_url": "https://photos.example.com/"})
    assert ok.status_code == 200, ok.text
    assert ok.json()["public_base_url"] == "https://photos.example.com"
    assert client.get("/api/settings").json()["public_base_url"] == "https://photos.example.com"


def test_post_settings_rejects_invalid_public_base_url(tmp_path):
    """An invalid public_base_url is rejected with 400 and not persisted."""
    app = make_app(tmp_path)
    client = TestClient(app)

    bad = client.post("/api/settings", json={"public_base_url": "not-a-url"})
    assert bad.status_code == 400
    assert client.get("/api/settings").json()["public_base_url"] == ""


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
