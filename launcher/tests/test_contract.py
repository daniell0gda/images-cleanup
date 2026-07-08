"""Single-file contract test: the launcher's `/api/sync/*` HTTP surface and the
Android Kotlin client must stay a matched pair.

The server side is introspected live (route table + real JSON responses through a
``TestClient``, plus Pydantic model fields). The client side is parsed as text
from the Kotlin sources (no gradle/emulator). Each acceptance criterion is its
own ``test_*`` function so a drift in either stack fails exactly one assertion.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


# ---------------------------------------------------------------------------
# Locations
# ---------------------------------------------------------------------------

_REPO_ROOT = Path(__file__).resolve().parents[2]
_KOTLIN_BASE = _REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "eu" / "caiq" / "imagesorter" / "sync"

_KOTLIN_FILES = {
    "SyncApi": _KOTLIN_BASE / "data" / "api" / "SyncApi.kt",
    "DeviceDtos": _KOTLIN_BASE / "data" / "api" / "dto" / "DeviceDtos.kt",
    "IdentityDtos": _KOTLIN_BASE / "data" / "api" / "dto" / "IdentityDtos.kt",
    "ProfileDtos": _KOTLIN_BASE / "data" / "api" / "dto" / "ProfileDtos.kt",
    "SessionDtos": _KOTLIN_BASE / "data" / "api" / "dto" / "SessionDtos.kt",
    "AuthInterceptor": _KOTLIN_BASE / "data" / "api" / "AuthInterceptor.kt",
    "TusUploader": _KOTLIN_BASE / "sync" / "TusUploader.kt",
    "UploadClient": _KOTLIN_BASE / "data" / "api" / "UploadClient.kt",
    "FailureReason": _KOTLIN_BASE / "domain" / "model" / "FailureReason.kt",
}


# ---------------------------------------------------------------------------
# Server harness (live FastAPI app, no YOLO)
# ---------------------------------------------------------------------------

def _build_app(tmp_path: Path):
    """Build the launcher app with sync env vars under tmp_path and a stub tag
    detector so YOLO is never loaded."""
    configs = tmp_path / "configs"
    inbox = tmp_path / "inbox"
    configs.mkdir(exist_ok=True)
    inbox.mkdir(exist_ok=True)
    os.environ["CONFIGS_DIR"] = str(configs)
    os.environ["INBOX_BASE"] = str(inbox)
    os.environ["SYNC_DB"] = str(tmp_path / "sync.db")
    import launcher.server as srv
    return srv.create_app(srv._JobState(), sync_detect_tags=lambda p: set()), configs


def _trust(client: TestClient, device_id: str = "dev-1", name: str = "Pixel") -> str:
    code = client.post(
        "/api/sync/devices", json={"device_id": device_id, "name": name}
    ).json()["pairing_code"]
    client.post(f"/api/sync/devices/{device_id}/approve")
    return client.get(
        f"/api/sync/devices/{device_id}/status", headers={"X-Pairing-Code": code}
    ).json()["token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _normalise_path(path: str) -> str:
    """Canonicalise a route/path template: lead with a slash and replace any
    ``{param}`` placeholder with a uniform ``{}`` so camelCase (client) and
    snake_case (server) param names compare equal."""
    if not path.startswith("/"):
        path = "/" + path
    return re.sub(r"\{[^}]+\}", "{}", path)


def _server_sync_routes(app) -> set[tuple[str, str]]:
    """(METHOD, normalised-path) for every ``/api/sync/*`` route on the app."""
    routes: set[tuple[str, str]] = set()
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if path is None or methods is None:
            continue
        if not path.startswith("/api/sync"):
            continue
        for method in methods:
            if method in {"HEAD", "OPTIONS"}:
                continue
            routes.add((method, _normalise_path(path)))
    return routes


# ---------------------------------------------------------------------------
# Kotlin source parsing
# ---------------------------------------------------------------------------

def _read_kotlin(name: str) -> str:
    path = _KOTLIN_FILES[name]
    if not path.exists():
        pytest.fail(f"Expected Kotlin source missing: {name} at {path}")
    return path.read_text(encoding="utf-8")


def _client_api_routes(syncapi_src: str) -> set[tuple[str, str]]:
    """(METHOD, normalised-path) for every @GET/@POST in SyncApi.kt."""
    routes: set[tuple[str, str]] = set()
    for method, path in re.findall(r'@(GET|POST)\("([^"]+)"\)', syncapi_src):
        routes.add((method, _normalise_path(path)))
    return routes


def _data_class_body(src: str, class_name: str) -> str:
    """The text between the constructor parentheses of a Kotlin ``data class``,
    extracted by balancing parens (the body itself contains ``)`` from @Json)."""
    m = re.search(rf"data class {re.escape(class_name)}\s*\(", src)
    if m is None:
        pytest.fail(f"Kotlin data class not found: {class_name}")
    start = m.end()
    depth = 1
    i = start
    while i < len(src) and depth > 0:
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
        i += 1
    return src[start : i - 1]


def _json_fields_of_data_class(src: str, class_name: str) -> list[str]:
    """The @Json(name=...) field names declared on a Kotlin ``data class``."""
    return re.findall(r'@Json\(name\s*=\s*"([^"]+)"\)', _data_class_body(src, class_name))


def _nonnullable_json_fields(src: str, class_name: str) -> set[str]:
    """@Json field names whose Kotlin type is non-nullable (no trailing ``?`` and
    no default). Nullable/optional fields may be absent from a real response."""
    body = _data_class_body(src, class_name)
    fields: set[str] = set()
    for line in body.splitlines():
        jm = re.search(r'@Json\(name\s*=\s*"([^"]+)"\)\s*val\s+\w+\s*:\s*([^,]+)', line)
        if jm is None:
            continue
        wire, type_decl = jm.group(1), jm.group(2)
        if "?" in type_decl or "=" in type_decl:
            continue
        fields.add(wire)
    return fields


# ===========================================================================
# Criterion 1 — harness builds the app without YOLO
# ===========================================================================

def test_harness_builds_app_with_testclient_and_no_yolo(tmp_path):
    """create_app builds with CONFIGS_DIR/INBOX_BASE/SYNC_DB under tmp_path and a
    stub detector, and a TestClient can drive a live endpoint."""
    app, _ = _build_app(tmp_path)
    client = TestClient(app)
    # A real call works end to end without ever importing ultralytics.
    r = client.post("/api/sync/devices", json={"device_id": "d", "name": "n"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "pending"
    import sys
    assert "ultralytics" not in sys.modules


# ===========================================================================
# Criterion 2 — all expected Kotlin sources exist and read as UTF-8
# ===========================================================================

def test_all_expected_kotlin_sources_exist():
    """Every Kotlin source the contract introspects is present and readable; a
    missing file fails with a clear message naming it."""
    for name in _KOTLIN_FILES:
        src = _read_kotlin(name)
        assert isinstance(src, str) and src


# ===========================================================================
# Criterion 3 — every client route exists on the server
# ===========================================================================

def test_every_client_route_resolves_to_a_server_route(tmp_path):
    """Each @GET/@POST path+method in SyncApi.kt maps to a route on the live app
    (path params normalised so {deviceId} matches {device_id})."""
    app, _ = _build_app(tmp_path)
    server = _server_sync_routes(app)
    # Only /api/sync routes are in scope: the unauthenticated reachability probe
    # (GET /api/users) deliberately targets a non-sync launcher route.
    client_routes = {
        r for r in _client_api_routes(_read_kotlin("SyncApi")) if r[1].startswith("/api/sync")
    }

    missing = client_routes - server
    assert not missing, f"client routes absent from server: {sorted(missing)}"


# ===========================================================================
# Criterion 4 — every phone-facing server route is declared by the client
# ===========================================================================

# Launcher-only routes the phone never calls — excluded from client coverage.
# The management UI lists/approves/revokes devices; the phone never does.
# Profile deletion is admin/web-only too: the LAN web admin deletes profiles,
# the phone has no delete surface, so the phone client does not declare it.
_LAUNCHER_ONLY = {
    ("GET", "/api/sync/devices"),
    ("POST", "/api/sync/devices/{}/approve"),
    ("POST", "/api/sync/devices/{}/revoke"),
    ("DELETE", "/api/sync/profiles/{}"),
}


def test_every_phone_facing_server_route_is_declared_by_client(tmp_path):
    """Every /api/sync/* route except the launcher-only approve/revoke pair is
    declared by the Kotlin SyncApi client."""
    app, _ = _build_app(tmp_path)
    server = _server_sync_routes(app) - _LAUNCHER_ONLY
    client_routes = _client_api_routes(_read_kotlin("SyncApi"))

    undeclared = server - client_routes
    assert not undeclared, f"server routes not declared by client: {sorted(undeclared)}"


# ===========================================================================
# Criterion 5 — device-registration request fields match
# ===========================================================================

def test_register_device_request_fields_match():
    """server _DeviceRequest fields == client RegisterDeviceRequest @Json names."""
    import launcher.server as srv
    server_fields = set(srv._DeviceRequest.model_fields.keys())
    client_fields = set(_json_fields_of_data_class(_read_kotlin("DeviceDtos"), "RegisterDeviceRequest"))
    assert server_fields == client_fields == {"device_id", "name"}


# ===========================================================================
# Criterion 6 — wire-identity request fields match
# ===========================================================================

def test_identity_request_fields_match():
    """server _Identity fields == client IdentityDto @Json names (reconcile/verify)."""
    import launcher.server as srv
    server_fields = set(srv._Identity.model_fields.keys())
    client_fields = set(_json_fields_of_data_class(_read_kotlin("IdentityDtos"), "IdentityDto"))
    assert server_fields == client_fields == {"name", "created_on", "size"}


# ===========================================================================
# Criterion 7 — open-session request field matches
# ===========================================================================

def test_open_session_request_field_matches():
    """server _SessionRequest field == client OpenSessionRequest @Json name."""
    import launcher.server as srv
    server_fields = set(srv._SessionRequest.model_fields.keys())
    client_fields = set(_json_fields_of_data_class(_read_kotlin("SessionDtos"), "OpenSessionRequest"))
    assert server_fields == client_fields == {"profile_id", "force_place"}


# ===========================================================================
# Criterion 8 — response keys contain every non-nullable client DTO field
# ===========================================================================

def _write_sync_template(configs: Path) -> None:
    """Write server.yaml's ``sync:`` block so session-open reaches 200 (a config
    file no longer creates a DB profile; the shared placement template does)."""
    dest = configs.parent / "dest"
    (configs / "server.yaml").write_text(
        "sync:\n"
        "  tag_groups:\n"
        "    - name: people\n"
        "      tags: [person]\n"
        f"      destination: {dest / 'people'}\n"
        "  video:\n"
        f"    destination: {dest / 'videos'}\n"
        "  on_collision: rename\n"
    )


def test_response_keys_cover_nonnullable_client_dto_fields(tmp_path):
    """For each non-mutating sync endpoint, the real JSON keys contain every
    non-nullable @Json field the client response DTO declares."""
    app, configs = _build_app(tmp_path)
    _write_sync_template(configs)
    client = TestClient(app)
    token = _trust(client, "dev-1")
    h = _auth(token)

    sessions_src = _read_kotlin("SessionDtos")
    identity_src = _read_kotlin("IdentityDtos")
    profile_src = _read_kotlin("ProfileDtos")
    device_src = _read_kotlin("DeviceDtos")

    # A profile exists only when created in the DB (unauthenticated POST); a
    # config file no longer seeds one. The returned id opens the session below.
    profile_id = client.post("/api/sync/profiles", json={"name": "alice"}).json()["profile_id"]

    # profiles (list of ProfileDto)
    profiles = client.get("/api/sync/profiles", headers=h).json()
    assert set(profiles[0].keys()) >= _nonnullable_json_fields(profile_src, "ProfileDto")

    # device status (trusted -> token present)
    status = client.get("/api/sync/devices/dev-1/status").json()
    assert set(status.keys()) >= _nonnullable_json_fields(device_src, "DeviceStatusResponse")

    # reconcile (ReconcileResponse + ReconcileResultDto rows)
    ident = [{"name": "a.jpg", "created_on": "2024-01-01T00:00:00", "size": 100}]
    recon = client.post("/api/sync/reconcile", json=ident, headers=h).json()
    assert set(recon.keys()) >= _nonnullable_json_fields(identity_src, "ReconcileResponse")
    assert set(recon["results"][0].keys()) >= _nonnullable_json_fields(identity_src, "ReconcileResultDto")

    # verify (VerifyResponse + VerifyResultDto rows)
    verify = client.post("/api/sync/verify", json=ident, headers=h).json()
    assert set(verify.keys()) >= _nonnullable_json_fields(identity_src, "VerifyResponse")
    assert set(verify["results"][0].keys()) >= _nonnullable_json_fields(identity_src, "VerifyResultDto")

    # session open (OpenSessionResponse)
    open_resp = client.post("/api/sync/sessions", json={"profile_id": profile_id}, headers=h).json()
    sid = open_resp["session_id"]
    assert set(open_resp.keys()) >= _nonnullable_json_fields(sessions_src, "OpenSessionResponse")

    # file offset (FileOffsetResponse)
    offset = client.get(f"/api/sync/sessions/{sid}/files/f1", headers=h).json()
    assert set(offset.keys()) >= _nonnullable_json_fields(sessions_src, "FileOffsetResponse")

    # outcomes (OutcomesResponse) — empty list is fine for the top-level key check
    outcomes = client.get(f"/api/sync/sessions/{sid}/outcomes", headers=h).json()
    assert set(outcomes.keys()) >= _nonnullable_json_fields(sessions_src, "OutcomesResponse")


# ===========================================================================
# Criterion 9 — profile list keys equal ProfileDto fields exactly
# ===========================================================================

def test_profile_list_keys_equal_profile_dto_fields(tmp_path):
    """Keys from GET /api/sync/profiles == client ProfileDto @Json fields."""
    app, _ = _build_app(tmp_path)
    client = TestClient(app)
    token = _trust(client, "dev-1")
    # Seed a profile via the DB endpoint; a config file no longer lists one.
    client.post("/api/sync/profiles", json={"name": "alice"})

    profiles = client.get("/api/sync/profiles", headers=_auth(token)).json()
    server_keys = set(profiles[0].keys())
    client_fields = set(_json_fields_of_data_class(_read_kotlin("ProfileDtos"), "ProfileDto"))
    assert server_keys == client_fields == {"profile_id", "display_name"}


# ===========================================================================
# Criterion 10 — outcome row keys are a subset of OutcomeDto fields
# ===========================================================================

def test_outcome_row_keys_subset_of_outcome_dto(tmp_path):
    """The keys launcher.sync writes for a synced row and a failed row are each a
    subset of the client OutcomeDto @Json fields."""
    from launcher.sync import SyncStore
    store = SyncStore(tmp_path / "sync.db")
    store.record_outcome("s1", {"file_id": "f1", "name": "ok.jpg", "status": "synced"})
    store.record_outcome("s1", {
        "file_id": "f2", "name": "bad.jpg", "status": "failed",
        "reason": "size_mismatch", "retryable": True,
    })
    store.record_outcome("s1", {"file_id": "f3", "name": "np.jpg", "status": "unclassified"})
    rows = store.outcomes_for("s1")
    by_status = {r["status"]: set(r.keys()) for r in rows}

    client_fields = set(_json_fields_of_data_class(_read_kotlin("SessionDtos"), "OutcomeDto"))
    assert by_status["synced"] == {"file_id", "name", "status"}
    assert by_status["failed"] == {"file_id", "name", "status", "reason", "retryable"}
    assert by_status["unclassified"] == {"file_id", "name", "status"}
    assert by_status["synced"] <= client_fields
    assert by_status["failed"] <= client_fields
    assert by_status["unclassified"] <= client_fields


# ===========================================================================
# Criterion 11 — upload-chunk header names match exactly
# ===========================================================================

def _server_upload_header_aliases() -> set[str]:
    """The Header(alias="...") names on the sync_upload_chunk route, read from
    server.py source text (signature introspection is brittle)."""
    src = (_REPO_ROOT / "launcher" / "server.py").read_text(encoding="utf-8")
    m = re.search(r"async def sync_upload_chunk\((.*?)\):", src, re.DOTALL)
    assert m is not None, "sync_upload_chunk handler not found in server.py"
    return set(re.findall(r'Header\(alias="([^"]+)"', m.group(1)))


def _client_upload_header_names(syncapi_src: str) -> set[str]:
    """The @Header("...") names declared on SyncApi.uploadChunk."""
    m = re.search(r"fun uploadChunk\((.*?)\):", syncapi_src, re.DOTALL)
    assert m is not None, "uploadChunk function not found in SyncApi.kt"
    return set(re.findall(r'@Header\("([^"]+)"\)', m.group(1)))


def test_upload_chunk_header_names_match():
    """server Header(alias=...) names == client @Header(...) names on uploadChunk."""
    server_headers = _server_upload_header_aliases()
    client_headers = _client_upload_header_names(_read_kotlin("SyncApi"))
    expected = {"File-Id", "File-Name", "File-Created-On", "File-Size", "File-Mime-Type", "Upload-Offset"}
    assert server_headers == client_headers == expected


# ===========================================================================
# Criterion 12 — bearer-token attach/omit rule matches
# ===========================================================================

def _server_unauthenticated_sync_routes() -> set[tuple[str, str]]:
    """(METHOD, normalised-path) for each /api/sync/* route whose handler does
    NOT call _require_device, excluding the launcher-only approve/revoke pair.

    Parsed from server.py source by splitting on the @app route decorators and
    checking each handler body for a _require_device call."""
    src = (_REPO_ROOT / "launcher" / "server.py").read_text(encoding="utf-8")
    pattern = re.compile(
        r'@app\.(get|post|put|delete)\("(/api/sync[^"]*)"\)\s*\n\s*async def \w+\(',
    )
    decorators = list(pattern.finditer(src))
    open_routes: set[tuple[str, str]] = set()
    for idx, m in enumerate(decorators):
        method = m.group(1).upper()
        path = _normalise_path(m.group(2))
        body_end = decorators[idx + 1].start() if idx + 1 < len(decorators) else len(src)
        body = src[m.end():body_end]
        if "_require_device" not in body:
            open_routes.add((method, path))
    return open_routes - _LAUNCHER_ONLY


def _client_omit_routes(auth_src: str, syncapi_src: str) -> set[tuple[str, str]]:
    """The (METHOD, normalised-path) routes the AuthInterceptor omits the token
    for. The interceptor matches by path only; the method is recovered from the
    SyncApi route that owns that path."""
    # AuthInterceptor omits: path == /api/sync/devices, and
    # path startsWith /api/sync/devices/ and endsWith /status.
    assert 'path == "/api/sync/devices"' in auth_src
    assert 'startsWith("/api/sync/devices/")' in auth_src and 'endsWith("/status")' in auth_src
    omit_paths = {"/api/sync/devices", "/api/sync/devices/{}/status"}
    result: set[tuple[str, str]] = set()
    for method, path in _client_api_routes(syncapi_src):
        if path in omit_paths:
            result.add((method, path))
    return result


def test_bearer_token_attach_omit_rule_matches():
    """The client AuthInterceptor omits the token for exactly POST /api/sync/devices
    and GET /api/sync/devices/{id}/status, which are exactly the sync routes whose
    server handler does not call _require_device."""
    client_omit = _client_omit_routes(_read_kotlin("AuthInterceptor"), _read_kotlin("SyncApi"))
    server_open = _server_unauthenticated_sync_routes()
    expected = {("POST", "/api/sync/devices"), ("GET", "/api/sync/devices/{}/status")}
    # The token-omission contract covers only the pre-pairing routes: the client
    # must call these before it has a token, and they are genuinely
    # unauthenticated server-side. GET/POST /api/sync/profiles are also
    # unauthenticated but token-tolerant — the phone calls them post-pairing (so
    # it harmlessly sends its token) and the LAN web admin calls them with none.
    # They are therefore a superset of `server_open`, not part of the omit rule.
    assert client_omit == expected
    assert expected <= server_open


# ===========================================================================
# Criteria 13 & 14 — FailureReason vocabulary and retryable classification
# ===========================================================================

def _client_failure_reasons(src: str) -> dict[str, bool]:
    """Map each enum entry's wire string -> retryable flag from FailureReason.kt."""
    pairs = re.findall(
        r'\w+\("([^"]+)",\s*retryable\s*=\s*(true|false)\)',
        src,
    )
    return {wire: (flag == "true") for wire, flag in pairs}


def test_failure_reason_wire_vocabulary_matches():
    """server FailureReason .value set == client wire set, excluding the
    client-only `unknown` fallback."""
    from launcher.sync import FailureReason
    server_wires = {r.value for r in FailureReason}
    client = _client_failure_reasons(_read_kotlin("FailureReason"))
    client_wires = set(client.keys()) - {"unknown"}
    assert server_wires == client_wires == {
        "size_mismatch", "unreadable", "placement_error", "internal_error", "no_video_destination",
    }


def test_failure_reason_retryable_classification_matches():
    """Each reason's .retryable on the server equals the client's retryable flag
    for the same wire string (the `unknown` fallback is client-only)."""
    from launcher.sync import FailureReason
    client = _client_failure_reasons(_read_kotlin("FailureReason"))
    for reason in FailureReason:
        assert reason.value in client, f"client lacks wire {reason.value!r}"
        assert reason.retryable == client[reason.value], (
            f"retryable mismatch for {reason.value!r}: "
            f"server={reason.retryable} client={client[reason.value]}"
        )
