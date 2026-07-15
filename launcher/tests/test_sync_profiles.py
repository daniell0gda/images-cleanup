"""Sync profile endpoints and the DB-backed profile store."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    _make_config,
    _part,
    _meta,
    _jpeg_bytes,
    _read_profile_tag,
)


def test_profiles_lists_db_rows_without_token(tmp_path):
    """GET /api/sync/profiles returns DB profile rows as {profile_id,
    display_name}, needs no device bearer token, and never auto-migrates legacy
    config_<user>_groupby.yaml files."""
    app = make_app(tmp_path)
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.create_profile("alice")
    store.create_profile("bob")
    # A legacy file-based config must NOT surface as a profile.
    (tmp_path / "configs" / "config_carol_groupby.yaml").write_text("mode: GroupByTags\n")

    client = TestClient(app)
    r = client.get("/api/sync/profiles")  # no Authorization header
    assert r.status_code == 200, r.text
    profiles = r.json()
    ids = {p["profile_id"] for p in profiles}
    assert ids == {"Alice", "Bob"}
    assert all("display_name" in p for p in profiles)


def test_create_profile_endpoint_returns_201_and_maps_errors(tmp_path):
    """POST /api/sync/profiles is unauthenticated: 201 on create, 409 on a
    case-insensitive duplicate, 400 on a blank/invalid/over-long name."""
    app = make_app(tmp_path)
    client = TestClient(app)

    ok = client.post("/api/sync/profiles", json={"name": "daniel local"})
    assert ok.status_code == 201, ok.text
    assert ok.json() == {"profile_id": "Daniel Local", "display_name": "Daniel Local"}

    dup = client.post("/api/sync/profiles", json={"name": "DANIEL LOCAL"})
    assert dup.status_code == 409, dup.text

    assert client.post("/api/sync/profiles", json={"name": "   "}).status_code == 400
    assert client.post("/api/sync/profiles", json={"name": "bad/name"}).status_code == 400
    assert client.post("/api/sync/profiles", json={"name": "x" * 65}).status_code == 400


def test_delete_profile_endpoint_removes_only_the_profiles_row(tmp_path):
    """DELETE /api/sync/profiles/{id} is unauthenticated and leaves synced_files
    rows (and thus media-index/EXIF of already-synced photos) untouched."""
    import sqlite3
    app = make_app(tmp_path)
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.create_profile("alice")
    store.record_synced(
        "a.jpg", "2024-01-01T00:00:00", 10, "image/jpeg",
        str(tmp_path / "stored" / "a.jpg"), "dev-1", "Alice",
    )

    client = TestClient(app)
    r = client.delete("/api/sync/profiles/Alice")  # no Authorization header
    assert r.status_code == 200, r.text
    assert client.get("/api/sync/profiles").json() == []

    con = sqlite3.connect(str(tmp_path / "sync.db"))
    remaining = con.execute("SELECT COUNT(*) FROM synced_files WHERE name='a.jpg'").fetchone()[0]
    con.close()
    assert remaining == 1


def test_syncstore_creates_profiles_table_with_expected_schema(tmp_path):
    """SyncStore initializes a profiles table keyed by profile_id with a
    NOT NULL created_at column."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.list_profiles()  # forces the connection + schema init

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    cols = {row[1]: (row[2], row[3], row[5]) for row in con.execute("PRAGMA table_info(profiles)")}
    con.close()
    assert cols["profile_id"] == ("TEXT", 0, 1)  # type, notnull, pk
    assert cols["created_at"][0] == "TEXT" and cols["created_at"][1] == 1


def test_syncstore_create_list_delete_profile_rows(tmp_path):
    """create_profile inserts a row, list_profiles reads them back sorted as
    {profile_id, display_name}, and delete_profile removes a single row."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.create_profile("bravo")
    store.create_profile("alpha")

    assert store.list_profiles() == [
        {"profile_id": "Alpha", "display_name": "Alpha"},
        {"profile_id": "Bravo", "display_name": "Bravo"},
    ]

    store.delete_profile("Alpha")
    assert [p["profile_id"] for p in store.list_profiles()] == ["Bravo"]


def test_module_level_list_profiles_is_deleted(tmp_path):
    """The old file-scanning module-level list_profiles(configs_dir) is gone;
    profile listing is now a SyncStore method reading the DB table."""
    import launcher.sync as sync_mod
    assert not hasattr(sync_mod, "list_profiles")


def test_create_profile_normalizes_to_title_case_no_suffix(tmp_path):
    """A submitted name is stored Title-cased with spaces preserved, and the
    stored name is both profile_id and display_name with no _groupby suffix."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")

    result = store.create_profile("daniel local")
    assert result == {"profile_id": "Daniel Local", "display_name": "Daniel Local"}
    assert not result["profile_id"].endswith("_groupby")


def test_profile_name_validation_trims_and_rejects_invalid(tmp_path):
    """Validation trims surrounding whitespace, accepts a 64-char name, and
    rejects blank, invalid-charset, and over-64-character names."""
    from launcher.sync import normalize_profile_name

    assert normalize_profile_name("  daniel  local  ") == "Daniel Local"
    assert normalize_profile_name("a" * 64) == "A" + "a" * 63  # 64 chars allowed

    for bad in ("", "   ", "bad-name", "no_underscore", "a" * 65, "emoji\U0001F600"):
        with pytest.raises(ValueError):
            normalize_profile_name(bad)


def test_create_profile_rejects_case_insensitive_duplicate(tmp_path):
    """Creating 'daniel' fails when 'Daniel' already exists (case-insensitive),
    raising DuplicateProfileError distinctly from a plain validation error."""
    from launcher.sync import SyncStore, DuplicateProfileError
    store = SyncStore(tmp_path / "sync.db")
    store.create_profile("Daniel")

    with pytest.raises(DuplicateProfileError):
        store.create_profile("daniel")
    # A duplicate is a ValueError subclass so the server can still catch broadly.
    assert issubclass(DuplicateProfileError, ValueError)


def test_profile_display_name_strips_legacy_suffix_but_passes_db_names_through(tmp_path):
    """Legacy file-based ids keep their stripped display name while a DB profile
    name (no _groupby suffix) renders verbatim; no migration is implied."""
    from launcher.sync import profile_display_name
    assert profile_display_name("daniel_groupby") == "daniel"  # legacy row unchanged
    assert profile_display_name("Daniel Local") == "Daniel Local"


def test_profile_carries_no_placement_data_only_tag_and_index_effects(tmp_path):
    """A profile contributes no placement config: placement is fully determined
    by the shared Config, and a profile's only per-file effects are the verbatim
    EXIF profile:<name> stamp and the synced_files.profile_id that backs the
    media-index profile column for gallery filtering."""
    from launcher.sync import SyncStore, place_file, embed_profile_tag
    store = SyncStore(tmp_path / "sync.db")
    name = store.create_profile("daniel local")["profile_id"]
    assert name == "Daniel Local"

    # Placement is config-driven only: place_file never receives the profile.
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", _jpeg_bytes())
    meta = _meta("img.jpg", part.stat().st_size)
    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"person"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "people" / "2021"

    # Effect 1: the EXIF stamp uses the DB profile name verbatim.
    embed_profile_tag(stored, name)
    assert _read_profile_tag(stored) == "profile:Daniel Local"

    # Effect 2: synced_files records the profile, backing the gallery filter.
    store.record_synced(meta.name, meta.created_on, meta.size, meta.mime_type,
                        str(stored), "dev-1", name)
    assert store.profile_for_path(str(stored)) == "Daniel Local"
