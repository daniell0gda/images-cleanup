# Specification — Shared Albums

Status: design agreed, ready to implement.
Audience: an implementing agent. This document is self-contained; read it fully
before writing code. Follow the repo's `CLAUDE.md` (Python 3.11 at
`C:\Python311\python.exe`, Conventional Commits, small single-purpose commits,
simplicity-first, surgical changes).

## 1. Goal

Add Google-Photos-style album sharing.

- The user (on the **Android app**) selects photos/videos and can **Create
  album**, **Add to album**, or **Create link** (create + share in one step).
- A new **Albums** view (Android) lists albums and lets the user copy the share
  link, stop sharing, rename, remove an album, and open an album.
- Inside an album the user can **add new items** and **remove items** (membership
  only — never deletes the underlying file).
- The **launcher server** serves each shared album at a **public, tokenless
  dynamic link** so anyone with the link can view it in a browser.

## 2. Architecture context (existing code)

- **Server**: FastAPI in `launcher/server.py`. It indexes a media library into
  SQLite (`launcher/media.py`, `MediaIndexer`, DB at `MEDIA_DB` / `./data/media.db`)
  and serves `/api/media/*` (timeline, `/thumb`, `/preview`, `/stream`), all gated
  by a **device bearer token** via `_require_device`. Routes are registered in
  `_register_sync_routes`.
- **Media serving helpers** to reuse (in `launcher/media.py`):
  `generate_thumbnail`, `generate_preview`, `_serve_with_range` (in `server.py`),
  `MediaIndexer.ensure_proxy` (transcode-on-demand for non-web-safe video),
  `MediaIndexer.get(media_id)`, `MediaIndexer.timeline(...)`.
- **Settings**: `launcher/settings.py` (`load_settings`/`save_settings`),
  surfaced by `GET/POST /api/settings`; web UI is `SettingsView` in
  `launcher/frontend/src/App.tsx`.
- **Web launcher UI** (`App.tsx`, React + Mantine) is the local-network
  management UI (profiles, devices, settings). It is **not** a photo browser and
  is **not** used to manage albums.
- **Android app** (`android/app/src/main/java/eu/caiq/imagesorter/sync/`):
  - Nav shell: `ui/MainActivity.kt` — bottom `NavigationBar` with `HomeTab`
    enum (`PHOTOS`, `SYNC`) rendered by `HomeShell`.
  - Photos gallery: `ui/screens/PhotosScreen.kt` (uses `PhotosGrid`, tap opens a
    preview overlay via local state).
  - Reusable Google-Photos multi-select: `ui/components/SelectableMedia.kt`
    (`SelectableMediaGrid`, `SelectionTopBar`, `MediaPreviewPager`).
  - Media data layer: `data/media/` (`MediaRepository`, `MediaUrls`,
    `MediaRemoteMediator`), `data/api/MediaApi.kt`, Room in `data/db/`.
  - Server base URL: `serverAddressToBaseUrl(...)`; token via
    `locator.securePrefs.getToken()`.

## 3. Key decisions (all agreed)

1. **Management is Android-only**; the server serves a public dynamic link.
2. **Albums are global** across all trusted devices: any trusted device can view,
   edit, and delete any album (same model as the shared library). No per-device
   ownership. The creating device's **name is stored** (`created_by`) and shown
   only in the Android Albums view — never on the public page.
3. **Album storage lives in `media.db`** (not a separate DB) so the share page can
   join membership against live media rows and so deletion cascades. Membership is
   keyed by `media_id` with **`FOREIGN KEY ... ON DELETE CASCADE`** to `media(id)`,
   and **`PRAGMA foreign_keys = ON`** on the indexer connection. Result: when the
   media build prunes a vanished file, its album rows are deleted in the same
   transaction — a removed photo silently leaves every album and can never rebind
   to a different file (important because `media.id` is a plain rowid that SQLite
   can recycle).
4. **One album entity.** "Create album" makes it unshared; "Create link" makes it
   and shares it immediately. "Shared or not" = whether `share_token` is non-null.
5. **Link lifecycle**: opaque token (`secrets.token_urlsafe(16)`); the public
   route is `GET /share/{token}` (not the numeric id). **Stop sharing** clears the
   token (link 404s, album stays). **Re-sharing mints a fresh token** (revoked
   links stay dead). **Removing the album** deletes everything (cascade drops
   membership; token dies).
6. **Naming/cover/order**: both create actions open the **same name dialog**,
   prefilled with a default like `Album <today's date>` (name required). Albums are
   renamable later. **Cover = first item by display order (auto)**, no manual cover
   picker. **Items sorted newest-first by `date_taken`** (same as the timeline),
   no manual reordering.
7. **Albums view = third bottom-nav tab**: `Photos | Albums | Sync`. Opening a
   single album is **local state inside the Albums tab** (a `selectedAlbumId` that
   swaps the tab content to an album-detail screen with its own back), not a new
   global `AppScreen`.
8. **In-album interactions**: "Add photos" opens the main timeline in
   picker/selection mode (reuse `SelectableMediaGrid`); confirm adds the selection
   (idempotent). Long-press → selection mode → **"Remove from album"** removes
   membership only (non-destructive; must NOT trigger the system delete dialog
   used by cleanup/not-people).
9. **Public page**: server-rendered, self-contained HTML (responsive grid,
   click-to-enlarge lightbox, inline `<video>`). Shows album name + media only —
   **no creator/device info**. Unknown/revoked/removed token → clean **404 page**.
   Shared album with zero items → valid page with an **empty state** (not 404).
   Video plays via the same transcode-on-demand + Range path as the app.
10. **Public byte serving is album-scoped and tokenless**: routes resolve
    token→album, verify the requested `media_id` is a member, then serve bytes
    reusing the existing helpers. The token + membership check IS the
    authorization (no device token). The public surface is limited to the shared
    album's items only.
11. **Public link host (option b)**: the **server** owns the public hostname via a
    new `public_base_url` setting (e.g. `https://photos.example.com`). The server
    returns a fully-built `share_url` in album responses; the app never constructs
    it. If unset, fall back to the incoming request's base URL (which is the LAN
    address when the app talks over LAN — degrades to LAN-only, does not error).
12. **Reverse-proxy safety**: the share page's **internal** media URLs (thumb /
    preview / stream) must be **relative** (`/share/{token}/media/{id}/thumb`), so
    once the page loads via any host (incl. Traefik + TLS) its bytes resolve
    through that same host automatically. `public_base_url` governs only the outer
    link handed to the user. Assume a dedicated subdomain (no path-prefix
    stripping); path-prefix routing would additionally require FastAPI `root_path`
    and is out of scope.

## 4. Data model (SQLite, in `media.db`)

Add to `MediaIndexer._init_schema` (`launcher/media.py`), and enable foreign keys
on the connection in `MediaIndexer._conn` (`con.execute("PRAGMA foreign_keys=ON")`).

```sql
CREATE TABLE IF NOT EXISTS album (
    id           INTEGER PRIMARY KEY,
    name         TEXT NOT NULL,
    created_by   TEXT,                 -- creating device name (display only)
    created_at   TEXT NOT NULL,        -- ISO 8601 UTC
    share_token  TEXT UNIQUE           -- NULL = not shared
);

CREATE TABLE IF NOT EXISTS album_item (
    album_id   INTEGER NOT NULL REFERENCES album(id) ON DELETE CASCADE,
    media_id   INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    added_at   TEXT NOT NULL,
    PRIMARY KEY (album_id, media_id)   -- makes add idempotent
);
CREATE INDEX IF NOT EXISTS idx_album_item_album ON album_item (album_id);
```

Implement an **`AlbumStore`** (new, e.g. `launcher/albums.py`) for album CRUD,
membership, and token operations. It may share the `media.db` path/connection
strategy with `MediaIndexer`; whatever connection performs membership reads/writes
and prune deletes must have `PRAGMA foreign_keys=ON` so the cascade fires. Render
the share page by joining `album_item` to `media` (or via `MediaIndexer.get`).

## 5. Server API

### 5.1 Android-facing (require device token via `_require_device`)

- `GET  /api/albums` → list. Each entry:
  `{ id, name, created_by, created_at, item_count, cover_media_id|null,
     shared: bool, share_url|null }`.
  `share_url` present only when shared; built per §3.11. `cover_media_id` = first
  item newest-first by `date_taken`, or null if empty.
- `POST /api/albums` → create. Body `{ name, media_ids: int[], created_by }`.
  Returns the created album entry. (Used by "Create album".)
- `PATCH /api/albums/{id}` → rename. Body `{ name }`.
- `DELETE /api/albums/{id}` → delete album (cascade removes items + token).
- `POST /api/albums/{id}/items` → add. Body `{ media_ids: int[] }`. Idempotent
  (ignore already-present and unknown media ids). Returns updated entry.
- `DELETE /api/albums/{id}/items` → remove membership. Body `{ media_ids: int[] }`.
  Non-destructive. Returns updated entry.
- `GET /api/albums/{id}/items` → the album's media as timeline-style items
  (newest-first by `date_taken`), shape compatible with `_media_item` in
  `server.py` (`{ id, kind, date_taken, width, height }`) so the Android gallery
  components can render them.
- `POST /api/albums/{id}/share` → mint a fresh token (replace any existing).
  Returns `{ share_token, share_url }`.
- `DELETE /api/albums/{id}/share` → revoke (set `share_token = NULL`).

Unknown `{id}` → 404. Reuse existing JSON/error conventions.

### 5.2 Public (no device token; token + membership IS the auth)

- `GET /share/{token}` → server-rendered HTML page (§6). Unknown/revoked token →
  404 page (clean message, not a stack trace). Empty album → valid empty page.
- `GET /share/{token}/media/{media_id}/thumb`
- `GET /share/{token}/media/{media_id}/preview`
- `GET /share/{token}/media/{media_id}/stream`
  Each: resolve token→album (404 if none), verify `media_id ∈ album` (404 if not),
  then serve bytes reusing `generate_thumbnail` / `generate_preview` /
  `_serve_with_range` / `MediaIndexer.ensure_proxy` exactly as the authed
  `/api/media/*` handlers do. `/stream` honours `Range` and transcodes non-web-safe
  video on demand.

### 5.3 Settings

Add `public_base_url` (string, optional) to `settings.py`
(`load_settings`/`save_settings`, with a default of `""`), to `_SettingsRequest`
and `_settings_payload` in `server.py`, and a field in `SettingsView`
(`App.tsx`). Normalize a trailing slash; validate it is a full `scheme://host`
when non-empty (reject otherwise with 400, matching existing settings validation).

## 6. Public share page (HTML)

Server-rendered and self-contained (inline CSS; minimal inline JS for the
lightbox). Requirements:

- Responsive grid of square thumbnails (`/share/{token}/media/{id}/thumb`,
  **relative** URLs).
- Click a thumbnail → lightbox showing the preview (`/preview`) for images, an
  inline `<video controls>` sourced from `/stream` for videos (video cells marked
  with a play badge in the grid).
- Header: album **name** only. No creator/device/profile info.
- Empty album → friendly empty state.
- 404 page for unknown/revoked tokens.
- All asset URLs relative (§3.12).
- No download button in v1.

## 7. Android UI

### 7.1 Photos tab — selection mode (new)

`PhotosScreen.kt` currently uses `PhotosGrid` (tap-to-open). Add Google-Photos
multi-select:

- **Long-press** a cell enters selection mode; tap toggles selection; a selection
  bar shows the count and actions: **Create album**, **Add to album**,
  **Create link**. (Reuse `SelectableMediaGrid` / `SelectionTopBar` patterns from
  `SelectableMedia.kt`; selection state hoisted.)
- **Create album** / **Create link** open the **same name dialog** (default
  `Album <date>`). Create album → `POST /api/albums`. Create link →
  `POST /api/albums` then `POST /api/albums/{id}/share`, then copy the returned
  `share_url` to the clipboard and confirm via a snackbar/toast.
- **Add to album** → pick an existing album (list from `GET /api/albums`), then
  `POST /api/albums/{id}/items`.

### 7.2 Albums tab (new)

Extend `HomeTab` with `ALBUMS`; add a third `NavigationBarItem` in `HomeShell`
(`Photos | Albums | Sync`).

- **List state**: each album tile shows cover thumbnail (`cover_media_id`), name,
  item count, and `created_by`. Per-album actions: **copy link** (shared only),
  **stop sharing** (shared only → `DELETE /api/albums/{id}/share`), **share**
  (unshared → `POST /api/albums/{id}/share` then copy), **rename**
  (`PATCH`), **remove album** (`DELETE`, with confirm).
- **Detail state** (local `selectedAlbumId`, own back affordance): newest-first
  grid from `GET /api/albums/{id}/items` (reuse the gallery cell/preview
  components and `MediaUrls`/bearer-token image loading). Actions:
  - **Add photos** → open the main timeline in picker/selection mode; confirm →
    `POST /api/albums/{id}/items`.
  - Long-press → selection → **Remove from album** →
    `DELETE /api/albums/{id}/items` (membership only; do NOT invoke the system
    delete dialog).

### 7.3 Data layer

Add album endpoints to `MediaApi.kt` and an album repository alongside
`MediaRepository`. Copy-link uses the server-provided `share_url` verbatim (the app
does not construct share URLs).

## 8. Edge cases / invariants

- Pruned/deleted media silently leaves albums (cascade); item counts and covers
  update on next read.
- Add is idempotent; unknown media ids on add are ignored.
- Remove-from-album and stop-sharing are non-destructive to files and to the
  album, respectively.
- Concurrent edits from multiple devices: last-write-wins on rename; add/remove
  are set operations (no conflict). Acceptable for a household server.
- Empty album can be created/left empty; the share link stays valid and shows an
  empty page.
- Public byte routes must 404 (not 403) for non-member ids to avoid leaking which
  ids exist.
- Video public playback depends on ffmpeg/ffprobe being present in the deployment
  (`problems.txt` #4 tracks this gap; not introduced by this feature).

## 9. Testing (TDD; mirror existing test layout)

- **Server** (`launcher/tests/`): album store CRUD + idempotent add + membership;
  cascade-on-prune (build prunes a file → its album rows are gone); token
  mint/revoke/re-mint; `share_url` building with and without `public_base_url`;
  public routes — valid token serves, unknown/revoked → 404, non-member id → 404,
  `/stream` Range; settings round-trip for `public_base_url`. Follow the patterns
  in `test_media_*.py`, `test_server.py`, `test_settings.py`.
- **Android** (`android/app/src/test/.../`): selection-mode actions on the Photos
  grid; album list/detail state; "Remove from album" calls the membership endpoint
  and not the delete flow; copy-link uses the server `share_url`. Follow the
  existing Compose/unit test patterns.

## 10. Suggested implementation order

1. Server data layer: schema + `PRAGMA foreign_keys=ON` + `AlbumStore` (TDD).
2. Android-facing album API in `_register_sync_routes` (TDD).
3. `public_base_url` setting (server + web Settings UI).
4. Public share routes + HTML page (TDD; relative URLs).
5. Android data layer (`MediaApi` + album repository).
6. Android Photos-tab selection mode + create/add/link actions.
7. Android Albums tab (list + detail + add/remove).

Keep each step a small, single-purpose commit per `CLAUDE.md`.
