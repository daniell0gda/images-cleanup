# Image Sorter — Android Phone Sync (architecture skeleton)

> **This is an architecture + functional skeleton, not a finished app.** The
> non-UI core (networking, persistence, MediaStore discovery, the resumable
> upload engine, pairing, cleanup) is implemented thoroughly and is meant to be
> real and compile-honest. **The Compose UI is deliberately a placeholder** —
> plain Material3 functional screens with no theming, motion, or visual polish.
> The final UI is a professional designer's deliverable; everything marked
> `// TODO(designer)` is intentionally left for that work. `// TODO(background)`
> marks the WorkManager seam that is architected for but out of scope for v1.

This module is the phone client for the existing, fully-tested Python sync server
that ships with the launcher (FastAPI, port 7000). Sync is strictly one-way:
phone (source) → NAS (destination). Nothing on the phone is ever auto-deleted.

## Requirements

- Android Studio (Koala or newer recommended)
- JDK 17
- Android SDK with platform 34; minSdk 33 (Android 13)

## Open / build

This `android/` directory is a self-contained Gradle project. It is **not** part
of the Python repo's build.

1. In Android Studio: **File → Open** and select `X:\projekty\image-sorter\android`.
2. Let Gradle sync (it uses a version catalog at `gradle/libs.versions.toml` and
   Kotlin DSL build scripts).
3. Set the server host: the base URL defaults to `http://nas.local:7000/` in
   `ServiceLocator.baseUrl()`. Make it user-configurable in Settings
   (`// TODO(designer)`), or edit the constant for local testing.
4. Run the `app` configuration on a device/emulator (API 33+).

> The Gradle wrapper is committed and pinned to Gradle 9.3.0. `:app:assembleDebug`
> has been verified to build green with AGP 8.5.2 and JDK 17 (SDK platform 34,
> build-tools 34.0.0).

## Package

`eu.caiq.imagesorter.sync`

## Module map

```
app/src/main/java/eu/caiq/imagesorter/sync/
  SyncApp.kt                     Application + manual ServiceLocator (no Hilt)
  data/
    api/
      SyncApi.kt                 Retrofit interface — exact server contract
      AuthInterceptor.kt         Bearer token on all calls except register/status
      UploadClient.kt            Raw-body streaming chunk upload (OkHttp/okio)
      dto/                       Wire models; snake_case via Moshi @Json
        DeviceDtos.kt            register + status
        ProfileDtos.kt           profiles
        IdentityDtos.kt          reconcile + verify (shared {name,created_on,size})
        SessionDtos.kt           sessions, offset, chunk, complete, outcomes
    db/
      AppDatabase.kt             Room cache (rebuildable — NOT source of truth)
      dao/                       SyncedCacheDao, PendingUploadDao, FailureDao
      entity/                    SyncedCacheEntity, PendingUploadEntity, FailureEntity
    media/
      MediaStoreScanner.kt       Enumerate + generation watermark + Identity mapping
    prefs/
      SecurePrefs.kt             EncryptedSharedPrefs (token, device_id) + plain prefs
  domain/model/
      Identity.kt                name + created_on + size (server-shared identity)
      MediaItem.kt               local MediaStore item + identity + uri
      SyncStatus.kt              PENDING / IN_PROGRESS / SYNCED / FAILED
      FailureReason.kt           server taxonomy incl. retryable flags
  pairing/
      PairingManager.kt          register → show code → poll status → store token
  sync/
      SyncEngine.kt              discover → reconcile → upload → report orchestration
      TusUploader.kt             resume via GET offset, then chunked POST
      UploadClient is in data/api (streaming body)
      SyncForegroundService.kt   foreground service + progress notification
      SyncTrigger.kt             pluggable trigger seam (interface)
      ManualSyncTrigger.kt       v1 impl — user-initiated, starts the service
      CleanupManager.kt          verify + MediaStore.createDeleteRequest flow
      SyncProgress.kt            progress snapshot model
  ui/
      MainActivity.kt            single-activity host; permissions + delete dialog
      MainViewModel.kt           screen routing + async flows
      screens/                   PairingScreen, ProfilePickerScreen,
                                 MainStatusScreen, CleanupScreen (all placeholder)
      theme/Theme.kt             placeholder Material3 theme (designer to replace)
res/values/                      strings.xml, themes.xml
AndroidManifest.xml              permissions + foregroundServiceType=dataSync
```

## Server API contract (matched exactly)

Base `http://<nas-host>:7000`. Bearer token on everything except register/status.

| Endpoint | Used by |
|---|---|
| `POST /api/sync/devices` | PairingManager.register |
| `GET /api/sync/devices/{id}/status` | PairingManager poll (no auth) |
| `GET /api/sync/profiles` | profile picker |
| `POST /api/sync/reconcile` | SyncEngine (always full) |
| `POST /api/sync/sessions` | SyncEngine.openSession |
| `GET /api/sync/sessions/{sid}/files/{fid}` | TusUploader resume offset |
| `POST /api/sync/sessions/{sid}/files` | UploadClient raw chunk |
| `POST /api/sync/sessions/{sid}/complete` | SyncEngine |
| `GET /api/sync/sessions/{sid}/outcomes` | SyncEngine report |
| `POST /api/sync/verify` | CleanupManager |

The phone never calls `approve` / `revoke` — those are launcher-side actions.

## Key design points honored

- **Identity = name + created_on + size**, phone-authoritative, sent with every
  upload (headers `File-Name` / `File-Created-On` / `File-Size` / `File-Mime-Type`).
  `created_on` is ISO-8601 local date-time to match `datetime.fromisoformat`.
- **Reconcile is always full**; discovery is incremental via the MediaStore
  generation watermark (full enumerate on first run / lost watermark).
- **Resumable uploads** (tus-style): GET offset first, then POST chunks from there.
  Bounded concurrency (default 6, configurable 4–10), newest-first, resumable
  across restarts via the persisted `pending_upload` queue.
- **Foreground service** for v1 execution; the **`SyncTrigger`** seam lets a
  WorkManager background trigger be added later without touching `SyncEngine`.
- **Token in EncryptedSharedPreferences**; a 401 clears it and routes back to
  pairing. Non-secret cursors (profile id, watermark, trusted SSID) in plain prefs.
- **Room is a rebuildable cache**, never the source of truth.
- **Cleanup** is the only destructive surface and is double-gated: a fresh server
  `/verify` plus the Android system delete dialog (`MediaStore.createDeleteRequest`).
- **Network-trust gate**: a stored trusted-SSID preference (in `SecurePrefs`) for
  gating *when* sync runs; separate from server auth.

## Known skeleton gaps (intentional)

- Final UI, theme, navigation, thumbnails, paging — `// TODO(designer)`.
- WorkManager background trigger — `// TODO(background)`, seam only.
- `MainViewModel.resolveLocalIds` returns empty: the verified-identity →
  current-MediaStore-id re-query is stubbed; wire it before enabling deletion.
- Settings screen (host/port, concurrency, folders, forget-network, re-pair) is
  not built; the underlying prefs exist in `SecurePrefs`.
- The trusted-network *check* itself (read current SSID, compare) is not wired
  into the service gate yet; the preference store is in place.
```
