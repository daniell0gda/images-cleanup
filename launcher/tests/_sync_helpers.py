"""Shared helpers for the split test_sync_*.py suites.

Only os/Path/TestClient are needed at module scope; every other dependency
is imported locally inside the helper that uses it (matching the original).
"""
from __future__ import annotations

import os
from pathlib import Path

from fastapi.testclient import TestClient


def make_app(tmp_path: Path, detect_tags=None):
    """Build a fresh launcher app with sync env vars pointed at tmp_path."""
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=detect_tags)


def register(client: TestClient, device_id="dev-1", name="Pixel"):
    return client.post("/api/sync/devices", json={"device_id": device_id, "name": name})


def trust(client: TestClient, device_id="dev-1", name="Pixel") -> str:
    """Register, approve, and return the bearer token.

    The token is gated behind the pairing code, so we replay the code the
    register response handed us via the X-Pairing-Code header.
    """
    code = register(client, device_id, name).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    status = client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()
    return status["token"]


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _seed_synced(tmp_path, name, created_on, size, device_id="other-dev"):
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_synced(
        name, created_on, size, "image/jpeg",
        str(tmp_path / "stored" / name), device_id, "alice_groupby",
    )


def _configure_sync(tmp_path):
    """Write a server.yaml sync: template and register the default DB profile.

    Makes session-open succeed (sync configured + profile present) and gives
    _load_sync_config a shared placement config keyed off the sync: section."""
    dest = tmp_path / "dest"
    (tmp_path / "configs").mkdir(exist_ok=True)
    (tmp_path / "configs" / "server.yaml").write_text(
        "sync:\n"
        "  tag_groups:\n"
        "    - name: people\n"
        "      tags: [person]\n"
        f"      destination: {dest / 'people'}\n"
        "  video:\n"
        f"    destination: {dest / 'videos'}\n"
        "  on_collision: rename\n"
    )
    from launcher.sync import SyncStore
    SyncStore(tmp_path / "sync.db").create_profile("alice")
    return dest


def _write_groupby_config(tmp_path, user="alice"):
    return _configure_sync(tmp_path)


def _open_session(client, token, profile_id="Alice"):
    return client.post(
        "/api/sync/sessions", json={"profile_id": profile_id}, headers=auth(token)
    ).json()["session_id"]


def _upload_chunk(client, token, sid, file_id, meta, offset, data):
    headers = {
        **auth(token),
        "File-Id": file_id,
        "File-Name": meta["name"],
        "File-Created-On": meta["created_on"],
        "File-Size": str(meta["size"]),
        "File-Mime-Type": meta["mime_type"],
        "Upload-Offset": str(offset),
    }
    return client.post(f"/api/sync/sessions/{sid}/files", content=data, headers=headers)


def _make_config(tmp_path, *, video=True, on_collision="rename"):
    from imagesorter.config import Config, TagGroup, Unclassified, Video
    return Config(
        mode="GroupByTags",
        source_folder=str(tmp_path / "src"),
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=[
            TagGroup("people", ["person"], str(tmp_path / "dest" / "people"),
                     group_by_year=True, group_by_month=False),
        ],
        unclassified=Unclassified(
            enabled=True, folder_name="others", destination=str(tmp_path / "dest"),
            group_by_year=True, group_by_month=False,
        ),
        similarity_threshold=0.96,
        on_collision=on_collision,
        video=Video(str(tmp_path / "dest" / "videos"), group_by_year=True, group_by_month=False)
        if video else None,
    )


def _part(tmp_path, name, data=b"x"):
    p = tmp_path / name
    p.write_bytes(data)
    return p


def _meta(name, size, mime="image/jpeg", created="2021-07-15T00:00:00"):
    from launcher.sync import FileMeta
    return FileMeta(name, created, size, mime)


def _write_e2e_config(tmp_path, user="alice"):
    return _configure_sync(tmp_path)


def _full_upload(client, token, tags, name="x.jpg", data=b"data", mime="image/jpeg"):
    """Open a session, upload one file, complete it; return (sid, outcomes)."""
    sid = _open_session(client, token)
    meta = {"name": name, "created_on": "2021-01-01T00:00:00", "size": len(data), "mime_type": mime}
    _upload_chunk(client, token, sid, "f1", meta, 0, data)
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    return sid, outcomes


def _jpeg_bytes(color=(10, 20, 30)):
    """A minimal real JPEG so piexif has valid metadata to work with."""
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (16, 16), color).save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def _read_profile_tag(path):
    """Read back the EXIF XPKeywords string, or None if absent/unreadable."""
    import piexif
    try:
        raw = piexif.load(str(path))["0th"].get(piexif.ImageIFD.XPKeywords)
    except Exception:
        return None
    return bytes(raw).decode("utf-16le").rstrip("\x00") if raw is not None else None


def _build_lane(tmp_path, detect_tags):
    from launcher.sync import SyncStore, SessionManager, SyncLane
    import launcher.server as srv
    store = SyncStore(tmp_path / "sync.db")
    sessions = SessionManager(tmp_path / "inbox")
    lane = SyncLane(store, sessions, srv._load_sync_config, detect_tags)
    return store, sessions, lane


def _age_session(sdir, seconds):
    import os, time
    old = time.time() - seconds
    for p in [sdir, *sdir.iterdir()]:
        os.utime(p, (old, old))


def _prepare_env(tmp_path):
    """Point the sync env vars at tmp_path and create the dirs, without building
    the app yet (so the inbox can be pre-seeded before startup)."""
    import os
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")


def _dated_jpeg_bytes(exif_date: str | None = None, size=(8, 8), color=(10, 20, 30)) -> bytes:
    """A real JPEG payload, optionally carrying a DateTimeOriginal in the Exif
    IFD (where a real camera writes it, so it survives the sync lane's
    profile-tag rewrite)."""
    import io
    from PIL import Image

    img = Image.new("RGB", size, color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    if exif_date is None:
        return buf.getvalue()

    import piexif

    exif = {"Exif": {piexif.ExifIFD.DateTimeOriginal: exif_date.encode("ascii")}}
    out = io.BytesIO()
    piexif.insert(piexif.dump(exif), buf.getvalue(), out)
    return out.getvalue()


def _media_indexer(tmp_path, folders):
    from launcher.media import MediaIndexer
    return MediaIndexer(
        db_path=tmp_path / "media.db",
        thumbs_dir=tmp_path / "thumbs",
        proxies_dir=tmp_path / "proxies",
        folders=[str(f) for f in folders],
    )


def _run_synced_session(tmp_path, data, created_on):
    """Place one uploaded file via the sync lane with a media index attached;
    return (indexer, dest)."""
    make_app(tmp_path)
    dest = _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: {"person"})
    indexer = _media_indexer(tmp_path, [dest])
    lane.media_indexer = indexer

    from launcher.sync import FileMeta
    sid = sessions.create_session("dev-1", "alice_groupby")
    sessions.write_chunk(
        sid, "f1", FileMeta("shot.jpg", created_on, len(data), "image/jpeg"), 0, data
    )
    sessions.complete(sid)
    lane.process_session(sid)
    return indexer, dest
