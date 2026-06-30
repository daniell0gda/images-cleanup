"""Tests for the album data layer (server-data-layer cluster).

Covers the media-indexer foreign-key pragma and the ``AlbumStore`` CRUD,
membership, cascade-on-prune, listing/cover, and share-token operations.
"""
from __future__ import annotations

import os
from pathlib import Path

import yaml
from fastapi.testclient import TestClient
from PIL import Image

from launcher.albums import AlbumStore
from launcher.media import MediaIndexer


def _indexer(tmp_path: Path) -> MediaIndexer:
    return MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[],
    )


def _seed_media(idx: MediaIndexer, date_taken: str) -> int:
    """Insert one media row via the indexer's upsert and return its id."""
    path = f"{date_taken}.jpg"
    return idx._upsert(path, "root", "image", 1, 0.0, date_taken, 10, 10)


def _store(tmp_path: Path) -> tuple[MediaIndexer, AlbumStore]:
    idx = _indexer(tmp_path)
    idx._conn()  # ensure schema exists
    return idx, AlbumStore(tmp_path / "media.db")


def test_indexer_connection_enables_foreign_keys(tmp_path):
    idx = _indexer(tmp_path)
    con = idx._conn()
    assert con.execute("PRAGMA foreign_keys").fetchone()[0] == 1


def test_create_album_returns_row_with_id_and_iso_created_at(tmp_path):
    _, store = _store(tmp_path)
    album = store.create("Holiday", created_by="phone")
    assert isinstance(album["id"], int)
    assert album["name"] == "Holiday"
    assert album["created_by"] == "phone"
    assert album["share_token"] is None
    # ISO-8601 UTC, e.g. 2026-06-30T12:00:00+00:00
    from datetime import datetime
    parsed = datetime.fromisoformat(album["created_at"])
    assert parsed.utcoffset().total_seconds() == 0


def test_add_items_idempotent_and_ignores_unknown_ids(tmp_path):
    idx, store = _store(tmp_path)
    m1 = _seed_media(idx, "2026-01-01T00:00:00+00:00")
    m2 = _seed_media(idx, "2026-01-02T00:00:00+00:00")
    album = store.create("A")

    store.add_items(album["id"], [m1, m2])
    store.add_items(album["id"], [m1, 99999])  # re-add + unknown id

    items = store.items(album["id"])
    member_ids = {it["id"] for it in items}
    assert member_ids == {m1, m2}
    assert len(items) == 2


def test_remove_items_drops_membership_only_keeps_media(tmp_path):
    idx, store = _store(tmp_path)
    m1 = _seed_media(idx, "2026-01-01T00:00:00+00:00")
    m2 = _seed_media(idx, "2026-01-02T00:00:00+00:00")
    album = store.create("A")
    store.add_items(album["id"], [m1, m2])

    store.remove_items(album["id"], [m1])

    assert {it["id"] for it in store.items(album["id"])} == {m2}
    # underlying media rows survive
    assert idx.get(m1) is not None
    assert idx.get(m2) is not None


def test_prune_cascades_album_membership(tmp_path):
    library = tmp_path / "lib"
    library.mkdir()
    a = library / "a.jpg"
    b = library / "b.jpg"
    Image.new("RGB", (10, 10)).save(a)
    Image.new("RGB", (10, 10)).save(b)

    idx = MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(library)],
    )
    idx.build(force=True)
    rows = {r["path"]: r["id"] for r in idx.list_all()}
    store = AlbumStore(tmp_path / "media.db")
    album = store.create("A")
    store.add_items(album["id"], list(rows.values()))
    assert store.count(album["id"]) == 2

    a.unlink()
    idx.build(force=True)

    assert store.count(album["id"]) == 1
    assert store.cover_media_id(album["id"]) == rows[str(b)]


def test_items_newest_first_and_cover(tmp_path):
    idx, store = _store(tmp_path)
    old = _seed_media(idx, "2026-01-01T00:00:00+00:00")
    new = _seed_media(idx, "2026-03-01T00:00:00+00:00")
    mid = _seed_media(idx, "2026-02-01T00:00:00+00:00")
    album = store.create("A")

    assert store.cover_media_id(album["id"]) is None

    store.add_items(album["id"], [old, new, mid])
    ordered = [it["id"] for it in store.items(album["id"])]
    assert ordered == [new, mid, old]
    assert store.cover_media_id(album["id"]) == new


def test_share_mint_revoke_remint(tmp_path):
    _, store = _store(tmp_path)
    album = store.create("A")

    token1 = store.share(album["id"])
    assert token1
    assert store._row(album["id"])["share_token"] == token1

    store.revoke(album["id"])
    assert store._row(album["id"])["share_token"] is None

    token2 = store.share(album["id"])
    assert token2 and token2 != token1
    assert store._row(album["id"])["share_token"] == token2


def test_delete_album_cascades_items_and_token(tmp_path):
    idx, store = _store(tmp_path)
    m1 = _seed_media(idx, "2026-01-01T00:00:00+00:00")
    album = store.create("A")
    store.add_items(album["id"], [m1])
    token = store.share(album["id"])

    store.delete(album["id"])

    assert store._row(album["id"]) is None
    con = store._conn()
    assert con.execute(
        "SELECT COUNT(*) FROM album_item WHERE album_id=?", (album["id"],)
    ).fetchone()[0] == 0
    assert con.execute(
        "SELECT COUNT(*) FROM album WHERE share_token=?", (token,)
    ).fetchone()[0] == 0
    # underlying media survives
    assert idx.get(m1) is not None


# -- album HTTP API (android-facing-api cluster) ------------------------------


def _make_image(path: Path, size=(40, 40)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, (10, 200, 50)).save(path)


def _build_app(tmp_path: Path, folders, public_base_url=""):
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    os.environ["MEDIA_DB"] = str(tmp_path / "media.db")
    os.environ["MEDIA_THUMBS_DIR"] = str(tmp_path / "thumbs")
    os.environ["MEDIA_PROXIES_DIR"] = str(tmp_path / "proxies")
    os.environ["MEDIA_PREVIEWS_DIR"] = str(tmp_path / "previews")
    (configs / "server.yaml").write_text(
        yaml.safe_dump({
            "media_library": {"enabled": True,
                              "folders": [str(f) for f in folders],
                              "schedule": "0 2 * * *"},
            "public_base_url": public_base_url,
        }),
        encoding="utf-8",
    )
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=lambda p: set())


def _auth(client: TestClient, device_id="dev-1") -> dict:
    client.post("/api/sync/devices", json={"device_id": device_id, "name": "Pixel"})
    client.post(f"/api/sync/devices/{device_id}/approve")
    token = client.get(f"/api/sync/devices/{device_id}/status").json()["token"]
    return {"Authorization": f"Bearer {token}"}


def _api_app(tmp_path, public_base_url=""):
    root = tmp_path / "lib"
    _make_image(root / "a.jpg")
    _make_image(root / "b.jpg")
    app = _build_app(tmp_path, [root], public_base_url=public_base_url)
    app.state.media_indexer.build()
    return app


def _media_ids(app):
    return [r["id"] for r in app.state.media_indexer.list_all()]


def test_get_albums_lists_entries_with_cover_and_share_fields(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)
    mids = _media_ids(app)

    created = client.post(
        "/api/albums",
        json={"name": "Trip", "media_ids": mids, "created_by": "phone"},
        headers=headers,
    ).json()

    resp = client.get("/api/albums", headers=headers)
    assert resp.status_code == 200
    entry = next(a for a in resp.json() if a["id"] == created["id"])
    assert entry["name"] == "Trip"
    assert entry["created_by"] == "phone"
    assert isinstance(entry["created_at"], str)
    assert entry["item_count"] == len(mids)
    assert entry["cover_media_id"] in mids
    assert entry["shared"] is False
    assert entry["share_url"] is None


def test_get_albums_empty_album_has_null_cover(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)

    created = client.post(
        "/api/albums", json={"name": "Empty", "media_ids": []}, headers=headers
    ).json()

    entry = next(
        a for a in client.get("/api/albums", headers=headers).json()
        if a["id"] == created["id"]
    )
    assert entry["item_count"] == 0
    assert entry["cover_media_id"] is None
    assert entry["share_url"] is None


def test_create_rename_delete_album(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)
    mids = _media_ids(app)

    created = client.post(
        "/api/albums",
        json={"name": "First", "media_ids": mids[:1], "created_by": "phone"},
        headers=headers,
    )
    assert created.status_code == 200
    album = created.json()
    assert album["name"] == "First"
    assert album["item_count"] == 1

    renamed = client.patch(
        f"/api/albums/{album['id']}", json={"name": "Renamed"}, headers=headers
    )
    assert renamed.status_code == 200
    assert renamed.json()["name"] == "Renamed"

    deleted = client.delete(f"/api/albums/{album['id']}", headers=headers)
    assert deleted.status_code == 200
    remaining = [a["id"] for a in client.get("/api/albums", headers=headers).json()]
    assert album["id"] not in remaining


def test_add_and_remove_items_idempotent_return_updated_entry(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)
    mids = _media_ids(app)

    album = client.post(
        "/api/albums", json={"name": "M", "media_ids": []}, headers=headers
    ).json()
    aid = album["id"]

    r1 = client.post(
        f"/api/albums/{aid}/items", json={"media_ids": mids}, headers=headers
    )
    assert r1.status_code == 200
    assert r1.json()["item_count"] == len(mids)

    # re-adding is idempotent
    r2 = client.post(
        f"/api/albums/{aid}/items", json={"media_ids": mids}, headers=headers
    )
    assert r2.json()["item_count"] == len(mids)

    r3 = client.request(
        "DELETE", f"/api/albums/{aid}/items",
        json={"media_ids": mids[:1]}, headers=headers,
    )
    assert r3.status_code == 200
    assert r3.json()["item_count"] == len(mids) - 1

    # removing the same again is idempotent
    r4 = client.request(
        "DELETE", f"/api/albums/{aid}/items",
        json={"media_ids": mids[:1]}, headers=headers,
    )
    assert r4.json()["item_count"] == len(mids) - 1


def test_get_album_items_timeline_shape_newest_first(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)
    mids = _media_ids(app)

    album = client.post(
        "/api/albums", json={"name": "T", "media_ids": mids}, headers=headers
    ).json()

    resp = client.get(f"/api/albums/{album['id']}/items", headers=headers)
    assert resp.status_code == 200
    items = resp.json()
    assert len(items) == len(mids)
    assert set(items[0].keys()) == {"id", "kind", "date_taken", "width", "height"}
    dates = [it["date_taken"] for it in items]
    assert dates == sorted(dates, reverse=True)


def test_share_mints_token_and_revoke_clears_it(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)

    album = client.post(
        "/api/albums", json={"name": "S", "media_ids": []}, headers=headers
    ).json()
    aid = album["id"]

    shared = client.post(f"/api/albums/{aid}/share", headers=headers)
    assert shared.status_code == 200
    body = shared.json()
    assert body["share_token"]
    assert body["share_url"].endswith(f"/share/{body['share_token']}")

    entry = next(
        a for a in client.get("/api/albums", headers=headers).json() if a["id"] == aid
    )
    assert entry["shared"] is True
    assert entry["share_url"] == body["share_url"]

    revoked = client.delete(f"/api/albums/{aid}/share", headers=headers)
    assert revoked.status_code == 200
    entry = next(
        a for a in client.get("/api/albums", headers=headers).json() if a["id"] == aid
    )
    assert entry["shared"] is False
    assert entry["share_url"] is None


def test_unknown_album_id_returns_404(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)
    headers = _auth(client)

    assert client.get("/api/albums/9999/items", headers=headers).status_code == 404
    assert client.patch(
        "/api/albums/9999", json={"name": "x"}, headers=headers
    ).status_code == 404
    assert client.delete("/api/albums/9999", headers=headers).status_code == 404
    assert client.post(
        "/api/albums/9999/items", json={"media_ids": []}, headers=headers
    ).status_code == 404
    assert client.request(
        "DELETE", "/api/albums/9999/items", json={"media_ids": []}, headers=headers
    ).status_code == 404
    assert client.post("/api/albums/9999/share", headers=headers).status_code == 404
    assert client.delete("/api/albums/9999/share", headers=headers).status_code == 404


def test_album_routes_require_device_token(tmp_path):
    app = _api_app(tmp_path)
    client = TestClient(app)

    assert client.get("/api/albums").status_code == 401
    assert client.post(
        "/api/albums", json={"name": "x", "media_ids": []}
    ).status_code == 401
    bad = {"Authorization": "Bearer nope"}
    assert client.get("/api/albums", headers=bad).status_code == 401


def test_share_url_uses_public_base_url_when_set(tmp_path):
    app = _api_app(tmp_path, public_base_url="https://photos.example.com")
    client = TestClient(app)
    headers = _auth(client)

    album = client.post(
        "/api/albums", json={"name": "P", "media_ids": []}, headers=headers
    ).json()
    body = client.post(f"/api/albums/{album['id']}/share", headers=headers).json()
    assert body["share_url"] == (
        f"https://photos.example.com/share/{body['share_token']}"
    )


def test_share_url_falls_back_to_request_base_url(tmp_path):
    app = _api_app(tmp_path, public_base_url="")
    client = TestClient(app)
    headers = _auth(client)

    album = client.post(
        "/api/albums", json={"name": "P", "media_ids": []}, headers=headers
    ).json()
    body = client.post(f"/api/albums/{album['id']}/share", headers=headers).json()
    assert body["share_url"].startswith("http://testserver/share/")
    assert body["share_url"].endswith(body["share_token"])
