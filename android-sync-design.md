# Android Phone Sync — Design

## Overview

A native Android app that backs up photos and videos from the phone to the NAS,
reusing the existing **GroupByTags** classification mechanism to place each file at
its correct destination. Sync is strictly **one-way: phone (source) → NAS
(destination)**. Nothing on the phone is ever deleted automatically; the only
deletion is an explicit, user-initiated cleanup that is gated by a fresh
server-side existence check **and** the Android system delete dialog.

The app talks to the existing **launcher** server (FastAPI, port 7000), which gains
the new endpoints, a persistent datastore, and a background sync-processor.

---

## Design principles

- **Data safety above all.** A phone copy is only ever deleted after the server
  confirms the file still physically exists at its destination. The identity key
  and the always-full reconcile exist to make "this is safely backed up" provable.
- **Server is authoritative.** Sync history lives on the NAS, so a phone reinstall
  never loses state and never causes a false "already synced."
- **Performance over a huge library.** Whole DCIM/Camera (50k–100k+ items, tens to
  hundreds of GB) is the expected case. Discovery is incremental; checking is cheap;
  only missing bytes are expensive.
- **Reuse, don't reimplement.** All classification runs server-side on the existing
  YOLO/GroupByTags code. The phone never classifies and never sees destination paths.

---

## End-to-end flow

1. **First run** — app generates a device id + name, registers, and **waits** to be
   trusted. User approves it on the launcher's Trusted Devices page after matching a
   6-digit code. App receives and stores a bearer token. User picks a **profile**
   once (stored, never asked again).
2. **Discover** — app enumerates the configured folder(s) via MediaStore (whole
   DCIM/Camera by default), using the incremental generation watermark on later runs.
3. **Reconcile** — app sends candidate identities to the server in batches; server
   answers which are already synced. **Always full**, every run — already-present
   files (prior install, another device, manual placement) are skipped automatically.
4. **Upload** — missing files upload resumably (tus), 4–10 concurrent (default ~6),
   newest-first, into `{inbox}/{device}/{session}/`. Each upload carries metadata
   `{ name, created_on, size, mime_type }` and the chosen `profile_id`.
5. **Process (server)** — as each file finishes, the warm sync-processor classifies
   it (images batched ~16 for YOLO; videos skip YOLO), moves it to its destination,
   commits an index row, and reports the per-file outcome.
6. **Report** — phone polls/streams outcomes: `synced` or `failed{reason}`. Failed
   files are deleted from temp (the phone still has the original) and shown in the
   list with a tappable reason; the app retries them when it can.
7. **Cleanup (explicit)** — in the Cleanup view: **Start cleanup** → server verifies
   each synced file still physically exists → **Remove synced files** → Android
   system delete dialog → deletion. Only files confirmed present on the NAS are
   eligible.

---

## Routing (per file)

| Input | Path |
|---|---|
| Image, matches a tag group | that tag group's `destination` |
| Image, matches nothing | `unclassified.destination` |
| Video (never classified) | dedicated `video.destination` |

All three honor `group_by_year` / `group_by_month`. `unclassified.enabled` must be
true for a sync profile (there must always be a destination for unmatched images).

---

## Identity & dedup

- **Identity = `name + created_on + size`.** Cheap on both sides (phone MediaStore
  `DISPLAY_NAME` / `DATE_TAKEN` / `SIZE`); the phone is **authoritative** for these
  values and sends them with each upload. Size is included specifically to prevent a
  same-name/same-second collision from causing a false "already synced" that the
  delete feature could then act on.
- **Server-authoritative persistent index** (SQLite on the NAS). Records each synced
  file by its **original** identity plus the actual stored path (which may differ due
  to always-rename).
- **`on_collision` is honored in the sync path.** Re-syncs are caught by the index
  before transfer. For a genuine filename clash at a destination, the profile's
  `on_collision` decides: `rename` (default) keeps both files; `skip` leaves the
  existing file in place and treats the upload as already backed up (recorded synced
  against the existing destination file). `skip` suits content-derived filenames,
  where a clash means the same file; note it weakens the always-rename safety net
  if two genuinely distinct files ever share a destination name.

---

## Server: configuration

A sync profile is an existing **GroupByTags** config plus an optional `video`
section. `source_folder` is unused in the sync path; `on_collision` is honored
(see Identity & dedup above).

```yaml
# ... existing GroupByTags config (tag_groups, unclassified, etc.) ...

video:
  destination: ./sorted/videos
  group_by_year: true
  group_by_month: false
```

Server-level settings (env vars, like the existing `CONFIGS_DIR`):

- `INBOX_BASE` — base dir for temp upload sessions. **Must be on the same
  filesystem/volume as the destinations** so final placement is an instant rename.
- `SYNC_DB` — path to the SQLite file (index + trusted devices).

Only **GroupByTags** profiles are offered to the phone; SimilaritySearch is filtered
out (it has no per-file destination model).

---

## Server: temp folder lifecycle

- Layout: `{INBOX_BASE}/{device_id}/{session_id}/`.
- **Per-session completion marker** persisted on disk so a restart can tell complete
  sessions from in-progress ones.
- **Processing is per-file (batched), not session-gated** — placement starts as soon
  as a file finishes uploading.
- **Happy path:** move (not copy) empties the session folder; rmdir when done.
- **Failed files:** deleted from temp (safe — phone keeps the original), reported to
  the phone as `failed{reason}`, retried by the app.
- **Janitor:** on startup and on a timer, deletes failed leftovers and abandoned
  incomplete sessions past a TTL (e.g. 24h). Never touches in-progress uploads.

---

## Server: processing model

- A **long-running, in-process sync-processor** in the launcher keeps **one warm YOLO
  model** and drains a queue of completed uploads (images batched ~16; videos placed
  directly).
- Runs in a **separate lane** from the existing one-at-a-time manual sort jobs — a
  manual sort must not 409 a phone backup and vice-versa.
- **Per-file index commit** immediately after each successful move.
- **Startup reconcile:** re-enqueue complete-but-unprocessed sessions; keep
  in-progress sessions for resume; drop truly-abandoned ones.
- **Crash window:** if the server dies after a move but before the index commit, the
  file is on the NAS but not indexed → the phone re-uploads → always-rename → a
  **harmless duplicate**. This can never cause a false "synced," so it never risks a
  deletion. Accepted.

---

## Server: persistence (SQLite on the NAS)

```sql
-- Paired devices
CREATE TABLE devices (
    device_id     TEXT PRIMARY KEY,   -- UUID generated by the app
    name          TEXT NOT NULL,      -- friendly name, e.g. "Daniel's Pixel"
    token         TEXT,               -- bearer token issued on approval (NULL while pending)
    status        TEXT NOT NULL,      -- 'pending' | 'trusted' | 'revoked'
    pairing_code  TEXT,               -- 6-digit code shown during pairing
    created_at    TEXT NOT NULL,
    approved_at   TEXT
);

-- Synced-file index (authoritative sync history)
CREATE TABLE synced_files (
    name          TEXT NOT NULL,      -- original uploaded filename
    created_on    TEXT NOT NULL,      -- phone-authoritative DATE_TAKEN
    size          INTEGER NOT NULL,   -- bytes
    mime_type     TEXT NOT NULL,
    stored_path   TEXT NOT NULL,      -- actual destination path (post-rename)
    device_id     TEXT NOT NULL,
    profile_id    TEXT NOT NULL,
    synced_at     TEXT NOT NULL,
    PRIMARY KEY (name, created_on, size)
);
```

The identity is global by `(name, created_on, size)`: a file already uploaded by one
device is "already synced" for another. That is intentional and safe — the file is on
the NAS, so any device deleting its local copy is protected.

---

## Server: new HTTP endpoints

All sync endpoints require the bearer token except registration/pairing-status.

| Method & path | Purpose |
|---|---|
| `POST /api/sync/devices` | Register a device `{device_id, name}` → `pending`, returns the 6-digit pairing code |
| `GET /api/sync/devices/{id}/status` | Phone polls: `pending` / `trusted` (returns token) / `revoked` |
| `GET /api/sync/profiles` | List sync-capable (GroupByTags) profiles → `[{profile_id, display_name}]` |
| `POST /api/sync/reconcile` | Batch identity check → which of `[{name, created_on, size}]` are already synced |
| `POST /api/sync/sessions` | Open an upload session for a `profile_id` → `session_id` |
| `POST /api/sync/sessions/{sid}/files` (tus) | Resumable chunked upload of a file + metadata |
| `POST /api/sync/sessions/{sid}/complete` | Mark session complete (writes the completion marker) |
| `GET  /api/sync/sessions/{sid}/outcomes` | Poll/stream per-file outcomes (`synced` / `failed{reason}`) |
| `POST /api/sync/verify` | Cleanup: confirm `[identities]` still physically exist on disk |

Launcher UI gains a **Trusted Devices** page: list pending/trusted devices, show the
pairing code to match, approve, and revoke.

---

## Phone app (native Kotlin)

- **Discovery:** MediaStore, whole DCIM/Camera by default (user-configurable
  folders). Incremental via `MediaStore.getGeneration()` watermark; full enumerate
  only on first run / missing watermark. **Reconcile is always full.**
- **Local cache:** lightweight SQLite (Room) — a *cache* of server truth + local
  discovery/queue state, rebuildable from the server. Not the source of truth.
- **Execution:** **foreground service** (v1), user-initiated, with a progress
  notification so failures are always visible. The trigger is **pluggable** so a
  WorkManager background sync (gated on the trusted-network constraint) can be added
  later without a rewrite. Background is explicitly **out of scope for v1** because of
  OEM battery killers, Doze, and silent process death.
- **Upload:** tus resumable, 4–10 concurrent (configurable, default ~6),
  newest-first, resumable across app restarts via the watermark + pending queue.
- **Credential:** bearer token in Keystore-backed `EncryptedSharedPreferences`.
- **Network-trust gate:** a phone-side convenience — "use this network for sync?",
  remembered, forgettable in settings. Stops sync over cellular/untrusted wifi. This
  is separate from server auth.

---

## Phone app: UI

> **The UI must be modern and top-notch from a UX perspective, and should be produced
> by a professional designer.** Treat the screens below as functional requirements,
> not visual specs — the look, motion, and interaction polish are a deliberate design
> deliverable, not an afterthought.

- **Main view (read-only status):**
  - Filter chips: **working set** (in-progress + failed + recently synced; default) /
    **synced today** / **all**.
  - Each row shows a status icon (synced / failed / in-progress / pending). Tapping a
    row shows a preview; failed rows show the reason.
  - Virtualized + paged, newest-first — must stay fast over a 100k-item library.
  - Initiating delete from any filter routes into the **same** safe Cleanup pipeline,
    pre-scoped to that filter (verification still runs, fast for small sets).
- **Cleanup view (the only destructive surface):**
  - Single **Start cleanup** button → progress bar while the server verifies each
    synced file still exists → swaps to **Remove synced files** → Android system
    delete dialog → deletion. Button enabled iff ≥1 confirmed-synced item is still on
    the phone.
- **Pairing / first-run:** device registers and shows a 6-digit code to match on the
  launcher; profile picker shown once.
- **Settings:** forget trusted network, re-pair, choose profile, set upload
  concurrency, choose synced folders.

---

## Explicitly out of scope (v1)

- Automatic background sync (WorkManager) — architected for, not built.
- Internet-reachable server / TLS / remote sync (LAN-only).
- iOS (native Android only).
- SimilaritySearch over synced photos (sync uses GroupByTags only).
- Two-way / NAS→phone sync (strictly one-way).
```
