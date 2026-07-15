"""Device pairing, auth-token lifecycle, and re-register safety."""
from __future__ import annotations

import re
from fastapi.testclient import TestClient
from launcher.tests._sync_helpers import (
    make_app,
    register,
    trust,
    auth,
)


def test_register_device_creates_pending_with_pairing_code(tmp_path):
    """POST /api/sync/devices returns a 6-digit code and the device persists
    as pending with a NULL token."""
    app = make_app(tmp_path)
    client = TestClient(app)

    r = register(client, "dev-1", "Pixel")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "pending"
    assert re.match(r"^\d{6}$", body["pairing_code"])

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    row = con.execute(
        "SELECT status, token FROM devices WHERE device_id=?", ("dev-1",)
    ).fetchone()
    con.close()
    assert row == ("pending", None)


def test_list_devices_returns_status_and_code_without_token(tmp_path):
    """GET /api/sync/devices lists every device with its pairing fields for the
    management UI, but never leaks the bearer token."""
    app = make_app(tmp_path)
    client = TestClient(app)

    register(client, "dev-1", "Pixel")
    trust(client, "dev-2", "Galaxy")

    r = client.get("/api/sync/devices")
    assert r.status_code == 200, r.text
    by_id = {d["device_id"]: d for d in r.json()}

    assert by_id["dev-1"]["status"] == "pending"
    assert re.match(r"^\d{6}$", by_id["dev-1"]["pairing_code"])
    assert by_id["dev-2"]["status"] == "trusted"
    assert by_id["dev-2"]["approved_at"]
    for d in by_id.values():
        assert "token" not in d


def test_device_status_lifecycle_pending_trusted_revoked(tmp_path):
    """Status is pending (no token) before approval, trusted (with token)
    after approval, and revoked after revocation."""
    app = make_app(tmp_path)
    client = TestClient(app)
    code = register(client, "dev-1").json()["pairing_code"]

    before = client.get("/api/sync/devices/dev-1/status").json()
    assert before["status"] == "pending"
    assert "token" not in before

    client.post("/api/sync/devices/dev-1/approve")
    after = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
    assert after["status"] == "trusted"
    assert after["token"]

    client.post("/api/sync/devices/dev-1/revoke")
    revoked = client.get("/api/sync/devices/dev-1/status").json()
    assert revoked["status"] == "revoked"


def test_pending_status_does_not_echo_pairing_code(tmp_path):
    """The pairing code now gates token retrieval, so it is a secret and must
    not be echoed back in the status response body — the phone learns its code
    from the register response, not from status polling."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")

    status = client.get("/api/sync/devices/dev-1/status").json()
    assert status["status"] == "pending"
    assert "pairing_code" not in status


def test_status_returns_token_only_with_correct_pairing_code(tmp_path):
    """A trusted device's token is returned by status only when the caller
    presents the matching pairing code via the X-Pairing-Code header."""
    app = make_app(tmp_path)
    client = TestClient(app)
    code = register(client, "dev-1").json()["pairing_code"]
    approve = client.post("/api/sync/devices/dev-1/approve").json()
    issued = approve["token"]

    ok = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
    assert ok["status"] == "trusted"
    assert ok["token"] == issued


def test_status_omits_token_without_or_with_wrong_pairing_code(tmp_path):
    """A trusted device with a missing or wrong pairing code still reports
    trusted, but the token is withheld and the code is not echoed back."""
    app = make_app(tmp_path)
    client = TestClient(app)
    register(client, "dev-1")
    client.post("/api/sync/devices/dev-1/approve")

    missing = client.get("/api/sync/devices/dev-1/status").json()
    assert missing["status"] == "trusted"
    assert "token" not in missing
    assert "pairing_code" not in missing

    wrong = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": "000000"}
    ).json()
    assert wrong["status"] == "trusted"
    assert "token" not in wrong
    assert "pairing_code" not in wrong


def test_status_unknown_device_404_regardless_of_code(tmp_path):
    """An unknown device_id returns 404 even when a pairing code is supplied."""
    app = make_app(tmp_path)
    client = TestClient(app)
    assert client.get("/api/sync/devices/nope/status").status_code == 404
    assert (
        client.get(
            "/api/sync/devices/nope/status", headers={"X-Pairing-Code": "123456"}
        ).status_code
        == 404
    )


def test_approve_sets_approved_at_and_status_token_matches(tmp_path):
    """Approval issues a token and sets approved_at; the status endpoint
    returns the same token that approval issued."""
    app = make_app(tmp_path)
    client = TestClient(app)
    code = register(client, "dev-1").json()["pairing_code"]

    approve = client.post("/api/sync/devices/dev-1/approve").json()
    issued = approve["token"]

    status = client.get(
        "/api/sync/devices/dev-1/status", headers={"X-Pairing-Code": code}
    ).json()
    assert status["token"] == issued

    import sqlite3
    con = sqlite3.connect(str(tmp_path / "sync.db"))
    row = con.execute(
        "SELECT status, approved_at FROM devices WHERE device_id=?", ("dev-1",)
    ).fetchone()
    con.close()
    assert row[0] == "trusted"
    assert row[1] is not None


def test_protected_endpoint_rejects_missing_and_wrong_token(tmp_path):
    """A protected sync endpoint rejects missing/wrong tokens and accepts a
    valid trusted-device token."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)

    assert client.post("/api/sync/reconcile", json=[]).status_code == 401
    assert client.post("/api/sync/reconcile", json=[], headers=auth("garbage")).status_code == 401
    assert client.post("/api/sync/reconcile", json=[], headers=auth(token)).status_code == 200


def test_revoked_device_token_is_rejected(tmp_path):
    """A token that was valid while trusted stops working after revocation."""
    app = make_app(tmp_path)
    client = TestClient(app)
    token = trust(client)
    assert client.post("/api/sync/reconcile", json=[], headers=auth(token)).status_code == 200

    client.post("/api/sync/devices/dev-1/revoke")
    assert client.post("/api/sync/reconcile", json=[], headers=auth(token)).status_code == 401


def test_reregister_trusted_device_preserves_token_and_status(tmp_path):
    """Re-registering an already-trusted device_id must NOT null its token or
    downgrade it to pending: the store keeps the token and 'trusted' status."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    token = store.approve_device("dev-1")
    assert token

    store.register_device("dev-1", "Pixel")

    row = store.get_device("dev-1")
    assert row["status"] == "trusted"
    assert row["token"] == token


def test_reregister_trusted_device_is_noop_returning_status_without_code(tmp_path):
    """Re-registering a trusted device is a safe no-op: the store reports the
    existing trusted status and issues no fresh pairing code, and the token is
    never minted anew."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    token = store.approve_device("dev-1")

    result = store.register_device("dev-1", "Pixel")
    assert result["status"] == "trusted"
    assert result.get("pairing_code") is None
    # Token untouched.
    assert store.get_device("dev-1")["token"] == token


def test_register_absent_device_creates_pending_with_fresh_code(tmp_path):
    """Registering an unknown device_id creates it as pending with a fresh
    6-digit pairing code and a NULL token (unchanged behavior)."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")

    result = store.register_device("dev-new", "Pixel")
    assert result["status"] == "pending"
    assert re.match(r"^\d{6}$", result["pairing_code"])

    row = store.get_device("dev-new")
    assert row["status"] == "pending"
    assert row["token"] is None


def test_reregister_pending_device_still_refreshes_without_touching_trusted(tmp_path):
    """Re-registering an existing pending device still works (returns pending with
    a code) and does not affect a different, already-trusted device."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-trusted", "Galaxy")
    trusted_token = store.approve_device("dev-trusted")

    store.register_device("dev-pending", "Pixel")
    result = store.register_device("dev-pending", "Pixel")
    assert result["status"] == "pending"
    assert re.match(r"^\d{6}$", result["pairing_code"])

    # The unrelated trusted device is untouched.
    other = store.get_device("dev-trusted")
    assert other["status"] == "trusted"
    assert other["token"] == trusted_token


def test_register_device_does_not_insert_or_replace(tmp_path):
    """The trusted-preservation guard lives at the store layer: register_device
    must not blindly INSERT OR REPLACE (which would wipe a trusted token). A
    re-register of a trusted device keeps its created_at/approved_at row intact."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.register_device("dev-1", "Pixel")
    store.approve_device("dev-1")
    before = store.get_device("dev-1")

    store.register_device("dev-1", "Pixel")
    after = store.get_device("dev-1")

    assert after["created_at"] == before["created_at"]
    assert after["approved_at"] == before["approved_at"]
    assert after["token"] == before["token"]
