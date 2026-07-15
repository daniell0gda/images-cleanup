"""File routing/placement, failure taxonomy & retry, and temp-file lifecycle."""
from __future__ import annotations

from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    trust,
    auth,
    _open_session,
    _upload_chunk,
    _make_config,
    _part,
    _meta,
    _write_e2e_config,
    _full_upload,
    _build_lane,
)


def test_image_matching_tag_group_goes_to_group_destination(tmp_path):
    """An image whose detected tags match a group lands in that group's
    destination, honoring group_by_year."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"person"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "people" / "2021"
    assert stored.exists()


def test_image_matching_nothing_goes_to_unclassified(tmp_path):
    """By default (non-sync path), an image matching no group lands in the
    unclassified destination — the CLI sorter behavior is unchanged."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"cat"})
    assert ok and reason is None
    assert stored.parent == tmp_path / "dest" / "others" / "2021"


def test_discard_unclassified_does_not_place_and_is_distinguishable(tmp_path):
    """With discard_unclassified=True, an image matching no group is NOT moved
    into the unclassified/others destination, and the result is distinguishable
    from both a synced result (ok+path) and a failed result (reason set)."""
    from launcher.sync import place_file, DISCARDED
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "img.part", b"hello")
    meta = _meta("img.jpg", 5)

    ok, stored, reason = place_file(
        part, meta, cfg, lambda p: {"cat"}, discard_unclassified=True
    )

    assert ok is False  # not synced
    assert reason is None  # not failed
    assert stored is DISCARDED  # distinguishable sentinel
    assert not (tmp_path / "dest" / "others").exists()


def test_video_skips_classification_and_goes_to_video_destination(tmp_path):
    """A video is routed to video.destination without ever calling detect_tags."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "clip.part", b"movie")
    meta = _meta("clip.mp4", 5, mime="video/mp4")

    called = []
    ok, stored, reason = place_file(part, meta, cfg, lambda p: called.append(p) or set())
    assert ok and reason is None
    assert called == []
    assert stored.parent == tmp_path / "dest" / "videos" / "2021"


def test_video_without_video_section_fails_with_terminal_reason(tmp_path):
    """A video routed under a profile that has no video section yields an
    explicit, terminal failure (not retried, never decoded as an image)."""
    from launcher.sync import place_file, FailureReason
    cfg = _make_config(tmp_path, video=False)
    part = _part(tmp_path, "clip.part", b"movie")
    meta = _meta("clip.mp4", 5, mime="video/mp4")

    called = []
    ok, stored, reason = place_file(part, meta, cfg, lambda p: called.append(p) or set())

    assert ok is False
    assert stored is None
    assert reason is FailureReason.NO_VIDEO_DESTINATION
    assert reason.retryable is False
    assert called == []


def test_placement_always_renames_on_clash_both_survive(tmp_path):
    """Two distinct files with the same name both survive — the clash is
    renamed, never skipped/overwritten."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)

    p1 = _part(tmp_path, "a.part", b"first")
    ok1, s1, _ = place_file(p1, _meta("dup.jpg", 5), cfg, lambda p: {"cat"})
    p2 = _part(tmp_path, "b.part", b"secondchunk")
    ok2, s2, _ = place_file(p2, _meta("dup.jpg", 11), cfg, lambda p: {"cat"})

    assert ok1 and ok2
    assert s1 != s2
    assert s1.exists() and s2.exists()
    assert s1.read_bytes() == b"first"
    assert s2.read_bytes() == b"secondchunk"


def test_placement_honors_on_collision_skip_treating_clash_as_already_backed_up(tmp_path):
    """With on_collision='skip', a name clash is not renamed: the existing file is
    left untouched and the upload is reported as already backed up (ok, no _1
    duplicate), pointing the synced index at the existing destination file."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path, on_collision="skip")

    p1 = _part(tmp_path, "a.part", b"first")
    ok1, s1, _ = place_file(p1, _meta("dup.jpg", 5), cfg, lambda p: {"cat"})
    p2 = _part(tmp_path, "b.part", b"secondchunk")
    ok2, s2, reason2 = place_file(p2, _meta("dup.jpg", 11), cfg, lambda p: {"cat"})

    assert ok1 and ok2 and reason2 is None
    # No rename: the second resolves to the same existing destination path.
    assert s1 == s2
    # The existing file is preserved (not overwritten by the skipped upload).
    assert s1.read_bytes() == b"first"
    # No _1 duplicate was created.
    assert not (s1.parent / "dup_1.jpg").exists()


def test_unparseable_created_on_falls_back_to_epoch_year_bucket(tmp_path):
    """A file whose created_on cannot be parsed is placed deterministically into
    the 1970 (epoch) year bucket rather than failing the upload. Pins the
    documented fallback: _parse_created_on returns datetime.fromtimestamp(0)."""
    from launcher.sync import place_file
    cfg = _make_config(tmp_path)
    part = _part(tmp_path, "bad-date.part", b"hello")
    meta = _meta("bad-date.jpg", 5, created="not-a-date")

    ok, stored, reason = place_file(part, meta, cfg, lambda p: {"cat"})

    assert ok and reason is None
    assert stored.exists()
    assert stored.parent == tmp_path / "dest" / "others" / "1970"


def test_failed_file_is_removed_from_temp_and_reported(tmp_path):
    """A file that fails processing is deleted from its temp session and the
    failure is reported to the phone."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid = _open_session(client, token)
    meta = {"name": "bad.jpg", "created_on": "2021-01-01T00:00:00", "size": 99, "mime_type": "image/jpeg"}
    _upload_chunk(client, token, sid, "f1", meta, 0, b"short")
    client.post(f"/api/sync/sessions/{sid}/complete", headers=auth(token))

    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert outcomes[0]["status"] == "failed"
    # The temp part was removed (session dir is cleaned once no parts remain).
    sdir = tmp_path / "inbox" / "dev-1" / sid
    assert not sdir.exists() or not list(sdir.glob("*.part"))


def test_empty_session_folder_is_removed_after_all_files_moved(tmp_path):
    """Once every uploaded file has moved out, the session folder is rmdir'd."""
    app = make_app(tmp_path, detect_tags=lambda p: {"person"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid, _ = _full_upload(client, token, {"person"}, name="ok.jpg", data=b"abcd")
    assert not (tmp_path / "inbox" / "dev-1" / sid).exists()


def test_non_matching_image_is_unclassified_not_synced_and_cleaned_up(tmp_path):
    """A sync-lane image matching no tag_group: no synced_files row, its .part
    (and any renamed copy) is gone from the session folder, and the per-file
    outcome status is 'unclassified' (persisted + returned by the outcomes API)."""
    app = make_app(tmp_path, detect_tags=lambda p: {"cat"})
    _write_e2e_config(tmp_path)
    client = TestClient(app)
    token = trust(client, "dev-1")

    sid, outcomes = _full_upload(client, token, {"cat"}, name="np.jpg", data=b"abcd")

    assert outcomes[0]["status"] == "unclassified"
    # No synced_files row recorded for the discarded image.
    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    assert con.execute("SELECT COUNT(*) FROM synced_files").fetchone()[0] == 0
    con.close()
    # The temp part (and any renamed copy) is gone from the session folder.
    sdir = tmp_path / "inbox" / "dev-1" / sid
    assert not sdir.exists() or (
        not list(sdir.glob("*.part")) and not (sdir / "np.jpg").exists()
    )
    # The unclassified outcome was persisted and round-trips via the API.
    again = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=auth(token)).json()["outcomes"]
    assert again[0]["status"] == "unclassified"


def test_unclassified_outcome_row_carries_only_identity_keys(tmp_path):
    """An 'unclassified' outcome row carries only file_id, name, status — no
    reason/retryable — while failed rows still get reason/retryable."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_outcome("s1", {"file_id": "f1", "name": "np.jpg", "status": "unclassified"})
    store.record_outcome("s1", {
        "file_id": "f2", "name": "bad.jpg", "status": "failed",
        "reason": "size_mismatch", "retryable": True,
    })
    by_status = {r["status"]: r for r in store.outcomes_for("s1")}

    assert set(by_status["unclassified"].keys()) == {"file_id", "name", "status"}
    assert set(by_status["failed"].keys()) == {"file_id", "name", "status", "reason", "retryable"}


def test_failure_reasons_are_classified_retryable_or_terminal():
    """Every failure reason is explicitly classified; the taxonomy splits into
    auto-retryable and terminal."""
    from launcher.sync import FailureReason
    assert FailureReason.UNREADABLE.retryable is False
    assert FailureReason.SIZE_MISMATCH.retryable is True
    assert FailureReason.PLACEMENT_ERROR.retryable is True
    assert FailureReason.INTERNAL_ERROR.retryable is True


def test_retryable_reason_reattempted_terminal_not(tmp_path, monkeypatch):
    """A retryable failure is re-attempted (and can then succeed); a terminal
    failure is attempted exactly once."""
    from datetime import timedelta
    import launcher.sync as sync_mod
    make_app(tmp_path)
    _write_e2e_config(tmp_path)
    store, sessions, lane = _build_lane(tmp_path, detect_tags=lambda p: set())

    from launcher.sync import FileMeta
    fmeta = FileMeta("x.jpg", "2021-01-01T00:00:00", 4, "image/jpeg")
    config = sync_mod.SyncStore  # placeholder; not used by stubbed place_file

    # Retryable: fail once, then succeed.
    calls = {"n": 0}

    def flaky_place(part, meta, cfg, detect, **kwargs):
        calls["n"] += 1
        if calls["n"] == 1:
            return False, None, sync_mod.FailureReason.PLACEMENT_ERROR
        return True, tmp_path / "stored.jpg", None

    monkeypatch.setattr(sync_mod, "place_file", flaky_place)
    ok, _, _ = lane._attempt_with_retry(tmp_path / "p.part", fmeta, config)
    assert ok is True
    assert calls["n"] == 2  # re-attempted

    # Terminal: never retried.
    term_calls = {"n": 0}

    def terminal_place(part, meta, cfg, detect, **kwargs):
        term_calls["n"] += 1
        return False, None, sync_mod.FailureReason.UNREADABLE

    monkeypatch.setattr(sync_mod, "place_file", terminal_place)
    ok2, _, reason2 = lane._attempt_with_retry(tmp_path / "p.part", fmeta, config)
    assert ok2 is False
    assert reason2 is sync_mod.FailureReason.UNREADABLE
    assert term_calls["n"] == 1
