"""Server media-library index & incremental build.

Backs the ``/api/media/*`` endpoints with a SQLite index of images and videos
discovered under one or more configured root folders (scanned recursively). The
index is built incrementally: unchanged files are skipped, new/changed files are
(re)indexed, and rows for vanished files are pruned along with their cached
thumbnail/proxy. A single in-process lock prevents two builds running at once.

Thumbnail bytes themselves are largely Cluster 3's concern; this module keeps a
clean seam (a ``thumbnail`` hook) so the index/incremental/prune/lock/order
logic is fully real and tested without requiring real thumbnail generation,
ffmpeg, or ffprobe.
"""
from __future__ import annotations

import logging
import os
import shutil
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Storage locations (env-overridable, matching the SYNC_DB/INBOX_BASE style)
# ---------------------------------------------------------------------------

def media_db_path() -> Path:
    return Path(os.environ.get("MEDIA_DB", "./data/media.db"))


def media_thumbs_dir() -> Path:
    return Path(os.environ.get("MEDIA_THUMBS_DIR", "./data/media_thumbs"))


def media_proxies_dir() -> Path:
    return Path(os.environ.get("MEDIA_PROXIES_DIR", "./data/media_proxies"))


def media_previews_dir() -> Path:
    return Path(os.environ.get("MEDIA_PREVIEWS_DIR", "./data/media_previews"))


# ---------------------------------------------------------------------------
# Allowed formats (§4.7)
# ---------------------------------------------------------------------------

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
_VIDEO_EXTS = {".mp4", ".mov", ".m4v"}


def _kind_for(path: Path) -> str | None:
    """Return ``"image"``/``"video"`` for an allowed extension, else ``None``."""
    ext = path.suffix.lower()
    if ext in _IMAGE_EXTS:
        return "image"
    if ext in _VIDEO_EXTS:
        return "video"
    return None


def _mtime_iso(mtime: float) -> str:
    return datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat()


def _image_metadata(path: Path) -> tuple[int | None, int | None, str | None]:
    """Return ``(width, height, date_taken)`` for an image via Pillow.

    ``date_taken`` is the EXIF ``DateTimeOriginal`` when present (returned as an
    ISO string), else ``None`` so the caller falls back to the file mtime.
    """
    from PIL import Image, ImageOps

    with Image.open(path) as img:
        date_taken = _exif_datetime(img)
        # Report the display dimensions (EXIF orientation applied), so a portrait
        # photo stored as landscape pixels + a rotate tag is recorded portrait.
        width, height = ImageOps.exif_transpose(img).size
    return width, height, date_taken


def _exif_datetime(img) -> str | None:
    """Extract EXIF ``DateTimeOriginal`` as an ISO string, or ``None``."""
    try:
        exif = img.getexif()
    except Exception:
        return None
    if not exif:
        return None
    # 0x9003 = DateTimeOriginal; 0x0132 = DateTime (fallback).
    raw = None
    for tag in (0x9003, 0x0132):
        value = exif.get(tag)
        if value:
            raw = value
            break
    # DateTimeOriginal often lives in the Exif IFD, not the base IFD.
    if raw is None:
        try:
            sub = exif.get_ifd(0x8769)
        except Exception:
            sub = None
        if sub:
            raw = sub.get(0x9003)
    if not raw:
        return None
    try:
        return datetime.strptime(str(raw), "%Y:%m:%d %H:%M:%S").isoformat()
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# Thumbnails & previews (§4.5, asserted preview tier)
# ---------------------------------------------------------------------------

THUMB_SIZE = 320
PREVIEW_LONG_EDGE = 1600


def _center_crop_square(img):
    """Center-crop a Pillow image to a square (the shorter edge)."""
    width, height = img.size
    side = min(width, height)
    left = (width - side) // 2
    top = (height - side) // 2
    return img.crop((left, top, left + side, top + side))


def _save_jpeg(img, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    rgb = img.convert("RGB") if img.mode != "RGB" else img
    rgb.save(dest, format="JPEG", quality=85)


def _image_square_thumb(src: Path, dest: Path) -> None:
    from PIL import Image, ImageOps

    with Image.open(src) as img:
        img.load()
        img = ImageOps.exif_transpose(img)
        square = _center_crop_square(img)
        square = square.resize((THUMB_SIZE, THUMB_SIZE), Image.LANCZOS)
        _save_jpeg(square, dest)


def _video_square_thumb(src: Path, dest: Path) -> None:
    """Sample a frame a few seconds in (fallback to frame 0) and crop to a square."""
    import cv2
    from PIL import Image

    cap = cv2.VideoCapture(str(src))
    try:
        if not cap.isOpened():
            raise RuntimeError(f"cv2 cannot open video {src}")
        fps = cap.get(cv2.CAP_PROP_FPS) or 0
        target = int(fps * 3) if fps and fps > 0 else 0
        if target > 0:
            cap.set(cv2.CAP_PROP_POS_FRAMES, target)
        ok, frame = cap.read()
        if not ok:
            cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
            ok, frame = cap.read()
        if not ok or frame is None:
            raise RuntimeError(f"cv2 cannot decode a frame from {src}")
    finally:
        cap.release()
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    img = Image.fromarray(rgb)
    square = _center_crop_square(img)
    square = square.resize((THUMB_SIZE, THUMB_SIZE), Image.LANCZOS)
    _save_jpeg(square, dest)


def generate_thumbnail(src: Path, kind: str, dest: Path) -> bool:
    """Write a ~320px square center-cropped JPEG thumbnail of ``src`` to ``dest``.

    Images decode with Pillow; videos sample a frame with OpenCV. Returns True on
    success. Raises on failure so callers can keep per-file errors non-fatal.
    """
    src = Path(src)
    dest = Path(dest)
    if kind == "video":
        _video_square_thumb(src, dest)
    else:
        _image_square_thumb(src, dest)
    return True


def generate_preview(src: Path, dest: Path) -> bool:
    """Write a ~1600px long-edge JPEG preview (images only) to ``dest``.

    The aspect ratio is preserved; an image already within the long-edge limit is
    re-encoded at its native size. Returns True on success.
    """
    from PIL import Image, ImageOps

    src = Path(src)
    dest = Path(dest)
    with Image.open(src) as img:
        img.load()
        img = ImageOps.exif_transpose(img)
        long_edge = max(img.size)
        if long_edge > PREVIEW_LONG_EDGE:
            scale = PREVIEW_LONG_EDGE / long_edge
            new_size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
            img = img.resize(new_size, Image.LANCZOS)
        _save_jpeg(img, dest)
    return True


def make_thumbnail_hook(thumbs_dir: Path):
    """Return a ``(row_id, path, kind) -> bool`` hook for ``MediaIndexer``.

    The hook writes ``{thumbs_dir}/{row_id}.jpg`` and returns True so the build
    marks ``thumb_ready=1``. Errors propagate to the indexer, which logs them and
    keeps the build going.
    """
    thumbs_dir = Path(thumbs_dir)

    def hook(row_id: int, path: Path, kind: str) -> bool:
        return generate_thumbnail(path, kind, thumbs_dir / f"{row_id}.jpg")

    return hook


# ---------------------------------------------------------------------------
# Video delivery: ffmpeg/ffprobe availability, probe, range, transcode (§4.8)
# ---------------------------------------------------------------------------

def ffprobe_available() -> bool:
    """True when the ``ffprobe`` binary is on PATH (probe needs it)."""
    return shutil.which("ffprobe") is not None


def ffmpeg_available() -> bool:
    """True when the ``ffmpeg`` binary is on PATH (transcode needs it)."""
    return shutil.which("ffmpeg") is not None


# Browsers can only play H.264 progressively; Android's ExoPlayer also decodes
# HEVC (H.265) in hardware, so the app plays those originals with no transcode.
_WEBSAFE_VIDEO_CODECS = {"h264"}
_APPSAFE_VIDEO_CODECS = {"h264", "hevc"}
_WEBSAFE_AUDIO_CODECS = {"aac", "mp3"}


def probe_video_codecs(path: Path) -> tuple[str | None, str | None]:
    """Return ``(video_codec, audio_codec)`` for ``path`` via one ffprobe call.

    Each is the ffprobe ``codec_name`` (e.g. ``"h264"``, ``"hevc"``, ``"aac"``)
    of the first stream of that type, or ``None`` when absent. Requires
    ``ffprobe`` on PATH; callers must gate with :func:`ffprobe_available`.
    """
    import json
    import subprocess

    out = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_streams",
            "-of", "json", str(path),
        ],
        capture_output=True, text=True, check=True,
    )
    streams = json.loads(out.stdout).get("streams", [])
    video_codec = audio_codec = None
    for s in streams:
        if s.get("codec_type") == "video" and video_codec is None:
            video_codec = s.get("codec_name")
        elif s.get("codec_type") == "audio" and audio_codec is None:
            audio_codec = s.get("codec_name")
    return video_codec, audio_codec


def _audio_websafe(audio_codec: str | None) -> bool:
    """A missing audio track is fine; otherwise it must be AAC/MP3."""
    return audio_codec is None or audio_codec in _WEBSAFE_AUDIO_CODECS


def video_websafe(video_codec: str | None, audio_codec: str | None) -> bool:
    """Browser-playable: H.264 video + AAC/MP3 (or no) audio."""
    return video_codec in _WEBSAFE_VIDEO_CODECS and _audio_websafe(audio_codec)


def video_appsafe(video_codec: str | None, audio_codec: str | None) -> bool:
    """App-playable: H.264 or HEVC video + AAC/MP3 (or no) audio.

    ExoPlayer decodes both in hardware, so the app streams these originals
    directly; only the browser share surface needs the H.264 proxy.
    """
    return video_codec in _APPSAFE_VIDEO_CODECS and _audio_websafe(audio_codec)


def probe_video_websafe(path: Path) -> bool:
    """True iff ``path`` is browser web-safe (single-call convenience wrapper)."""
    return video_websafe(*probe_video_codecs(path))


def transcode_to_mp4(src: Path, dest: Path) -> None:
    """Transcode ``src`` into a faststart H.264/AAC MP4 at ``dest`` via ffmpeg.

    Writes to a temp file first and renames into place so a crashed/partial
    encode never leaves a corrupt cache. Requires ``ffmpeg`` on PATH; callers
    must gate with :func:`ffmpeg_available`.
    """
    import subprocess

    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(src),
            "-c:v", "libx264", "-c:a", "aac",
            "-movflags", "+faststart",
            # The temp file ends in ``.tmp``, so ffmpeg cannot infer the muxer
            # from the extension — name the MP4 format explicitly.
            "-f", "mp4",
            str(tmp),
        ],
        capture_output=True, check=True,
    )
    os.replace(tmp, dest)


def parse_byte_range(header: str | None, file_size: int) -> tuple[int, int] | None:
    """Parse an HTTP ``Range`` header into an inclusive ``(start, end)``.

    Supports a single ``bytes=a-b``, open-ended ``bytes=a-``, and suffix
    ``bytes=-n`` form. Returns ``None`` for an absent, malformed, or
    unsatisfiable range (the caller then serves the full body with 200).
    """
    if not header:
        return None
    header = header.strip()
    prefix = "bytes="
    if not header.startswith(prefix):
        return None
    spec = header[len(prefix):]
    if "," in spec:
        spec = spec.split(",", 1)[0]
    if "-" not in spec:
        return None
    start_s, _, end_s = spec.partition("-")
    try:
        if start_s == "":
            # Suffix form: last N bytes.
            n = int(end_s)
            if n <= 0:
                return None
            start = max(0, file_size - n)
            end = file_size - 1
        else:
            start = int(start_s)
            end = int(end_s) if end_s != "" else file_size - 1
    except ValueError:
        return None
    if start < 0 or start >= file_size or end < start:
        return None
    end = min(end, file_size - 1)
    return start, end


def read_file_slice(path: Path, start: int, end: int) -> bytes:
    """Read the inclusive byte range ``[start, end]`` from ``path``."""
    with open(path, "rb") as fh:
        fh.seek(start)
        return fh.read(end - start + 1)


# ---------------------------------------------------------------------------
# Build status
# ---------------------------------------------------------------------------

@dataclass
class BuildStatus:
    """Snapshot of an in-progress or finished build (§4.6)."""

    state: str = "idle"  # "idle" | "building"
    processed: int = 0
    total: int | None = None
    added: int = 0
    removed: int = 0
    skipped_roots: list[str] = field(default_factory=list)
    last_built: str | None = None
    last_count: int | None = None

    def snapshot(self) -> dict:
        return {
            "state": self.state,
            "processed": self.processed,
            "total": self.total,
            "added": self.added,
            "removed": self.removed,
            "skipped_roots": list(self.skipped_roots),
            "last_built": self.last_built,
            "last_count": self.last_count,
        }


# ---------------------------------------------------------------------------
# Indexer
# ---------------------------------------------------------------------------

class MediaIndexer:
    """SQLite-backed media index with an incremental, single-locked build.

    All DB access is serialized through a lock so the indexer is safe to share
    across request handlers and the background build thread.
    """

    def __init__(
        self,
        db_path: Path,
        thumbs_dir: Path,
        proxies_dir: Path,
        folders: list[str],
        thumbnail=None,
        profile_for=None,
    ):
        self._db_path = Path(db_path)
        self._thumbs_dir = Path(thumbs_dir)
        self._proxies_dir = Path(proxies_dir)
        self._folders = list(folders)
        # Thumbnail hook (Cluster 3). Default no-ops gracefully so the index
        # logic is testable without real thumbnail bytes.
        self._thumbnail = thumbnail or (lambda row_id, path, kind: False)
        # Profile lookup: ``(path) -> str | None`` returning which sync profile
        # placed the file (None for files not backed up via phone sync). Default
        # no-ops so the index logic is testable without a sync store.
        self._profile_for = profile_for or (lambda path: None)
        self._lock = threading.RLock()
        self._con: sqlite3.Connection | None = None
        self._build_lock = threading.Lock()
        self._status = BuildStatus()
        # Video transcode seam (§4.8). Injectable so the stream/lock logic is
        # testable without real ffmpeg; defaults to the ffmpeg-backed transcoder.
        self._transcoder = transcode_to_mp4
        self._proxy_locks: dict[int, threading.Lock] = {}
        self._proxy_locks_guard = threading.Lock()
        # Background pre-transcode worker (§4.8): a non-web-safe video found during
        # indexing is proxied ahead of time so its first play is instant instead of
        # blocking on a full encode. Serialized to one worker so the encode backlog
        # never saturates the CPU. Created lazily (only when ffmpeg is present).
        self._pretranscode_pool: ThreadPoolExecutor | None = None

    # -- connection / schema --------------------------------------------

    def _conn(self) -> sqlite3.Connection:
        with self._lock:
            con = self._con
            if con is None:
                self._db_path.parent.mkdir(parents=True, exist_ok=True)
                con = sqlite3.connect(str(self._db_path), check_same_thread=False)
                con.row_factory = sqlite3.Row
                con.execute("PRAGMA foreign_keys=ON")
                self._con = con
                self._init_schema(con)
            return con

    def _init_schema(self, con: sqlite3.Connection) -> None:
        with self._lock:
            con.executescript(
                """
                CREATE TABLE IF NOT EXISTS media (
                    id             INTEGER PRIMARY KEY,
                    path           TEXT UNIQUE NOT NULL,
                    root           TEXT NOT NULL,
                    kind           TEXT NOT NULL,
                    size           INTEGER NOT NULL,
                    mtime          REAL NOT NULL,
                    date_taken     TEXT NOT NULL,
                    width          INTEGER,
                    height         INTEGER,
                    video_websafe  INTEGER,
                    video_appsafe  INTEGER,
                    thumb_ready    INTEGER NOT NULL DEFAULT 0,
                    profile        TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_media_timeline
                    ON media (date_taken DESC, id DESC);

                CREATE TABLE IF NOT EXISTS album (
                    id           INTEGER PRIMARY KEY,
                    name         TEXT NOT NULL,
                    created_by   TEXT,
                    created_at   TEXT NOT NULL,
                    share_token  TEXT UNIQUE
                );
                CREATE TABLE IF NOT EXISTS album_item (
                    album_id   INTEGER NOT NULL REFERENCES album(id) ON DELETE CASCADE,
                    media_id   INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
                    added_at   TEXT NOT NULL,
                    PRIMARY KEY (album_id, media_id)
                );
                CREATE INDEX IF NOT EXISTS idx_album_item_album
                    ON album_item (album_id);
                """
            )
            # Migrate DBs created before the profile column existed, then index
            # it (deferred until here so the column is guaranteed to exist).
            cols = {r["name"] for r in con.execute("PRAGMA table_info(media)")}
            if "profile" not in cols:
                con.execute("ALTER TABLE media ADD COLUMN profile TEXT")
            # Added when the app began playing HEVC originals directly: existing
            # videos stay NULL until re-probed, which safely serves the original
            # to the app (the common H.264/HEVC case) rather than transcoding.
            if "video_appsafe" not in cols:
                con.execute("ALTER TABLE media ADD COLUMN video_appsafe INTEGER")
            con.execute("CREATE INDEX IF NOT EXISTS idx_media_profile ON media (profile)")
            con.commit()

    # -- queries ---------------------------------------------------------

    def list_all(self) -> list[dict]:
        """Every indexed row (test/debug helper), ordered by id."""
        with self._lock:
            rows = self._conn().execute("SELECT * FROM media ORDER BY id").fetchall()
        return [dict(r) for r in rows]

    def get(self, media_id: int) -> dict | None:
        """Return the row for ``media_id`` (or ``None`` if unknown)."""
        with self._lock:
            row = self._conn().execute(
                "SELECT * FROM media WHERE id=?", (media_id,)
            ).fetchone()
        return dict(row) if row is not None else None

    def mark_thumb_ready(self, media_id: int) -> None:
        """Set ``thumb_ready=1`` for ``media_id`` (used by the lazy thumb route)."""
        self._mark_thumb_ready(media_id)

    def delete(self, media_id: int) -> None:
        """Drop a single item's index row and its cached thumbnail/proxy.

        The single-item counterpart to prune, exposed for the delete endpoint:
        the caller removes the source file (guarded against escaping its root);
        this forgets the row so the item leaves the timeline. A no-op when the
        row is already gone.
        """
        self._delete_row(media_id)

    # -- video proxy (transcode-if-needed cache) -------------------------

    def set_transcoder(self, transcoder) -> None:
        """Override the ``(src, dest) -> None`` transcoder (test injection seam)."""
        self._transcoder = transcoder

    def ensure_proxy(self, media_id: int, src: Path) -> Path:
        """Return the cached H.264/AAC proxy for ``media_id``, transcoding once.

        Guarded by a per-id lock: concurrent callers for the same id block on the
        first, which produces ``{proxies_dir}/{id}.mp4`` exactly once; the waiters
        then find the cache present and reuse it (no second encode). Returns the
        proxy path ready to be served with Range.
        """
        dest = self._proxies_dir / f"{media_id}.mp4"
        if dest.exists():
            return dest
        lock = self._proxy_lock_for(media_id)
        with lock:
            if not dest.exists():
                self._transcoder(src, dest)
        return dest

    def _proxy_lock_for(self, media_id: int) -> threading.Lock:
        with self._proxy_locks_guard:
            lock = self._proxy_locks.get(media_id)
            if lock is None:
                lock = threading.Lock()
                self._proxy_locks[media_id] = lock
            return lock

    def _submit_pretranscode(self, media_id: int, src: Path) -> None:
        """Queue a background proxy build for a non-web-safe ``media_id``.

        No-op when ffmpeg is unavailable or the proxy already exists, so a repeat
        build never re-queues cached work. The encode shares ``ensure_proxy``'s
        per-id lock, so a concurrent on-demand stream reuses this result rather
        than encoding twice.
        """
        if not ffmpeg_available():
            return
        if (self._proxies_dir / f"{media_id}.mp4").exists():
            return
        if self._pretranscode_pool is None:
            self._pretranscode_pool = ThreadPoolExecutor(
                max_workers=1, thread_name_prefix="pretranscode"
            )
        self._pretranscode_pool.submit(self._pretranscode_one, media_id, src)

    def _pretranscode_one(self, media_id: int, src: Path) -> None:
        try:
            self.ensure_proxy(media_id, src)
        except Exception:
            logger.warning(
                "pre-transcode failed for media %s (%s)", media_id, src, exc_info=True
            )

    def timeline(self, limit: int = 100, after=None, on_or_before: str | None = None,
                 before=None, profile: str | None = None) -> list[dict]:
        """Timeline page ordered by ``(date_taken, id)``.

        ``after`` is the keyset cursor ``(date_taken, id)`` of the last item of
        the previous page; when given, only strictly-older items are returned so
        the cursor is total and stable across concurrent inserts. The ordering
        and tiebreak are downstream pagination's (Cluster 5) foundation.

        ``on_or_before`` is a ``YYYY-MM-DD`` date that, when given, restricts the
        page to rows whose ``date_taken`` calendar day is on or before it — the
        seek used by ``GET /api/media?from_date=``.

        ``before`` is the keyset cursor of an anchor; when given, only strictly
        *newer* items are returned, **ascending** (``date_taken ASC, id ASC``) so
        the closest-newer photo comes first — the upward (prepend) page used by
        ``GET /api/media?before=`` for bidirectional seek. Otherwise the page is
        newest-first (``date_taken DESC, id DESC``).
        """
        clauses: list[str] = []
        params: list = []
        if after is not None:
            date_taken, last_id = after
            clauses.append("((date_taken < ?) OR (date_taken = ? AND id < ?))")
            params += [date_taken, date_taken, last_id]
        if before is not None:
            date_taken, last_id = before
            clauses.append("((date_taken > ?) OR (date_taken = ? AND id > ?))")
            params += [date_taken, date_taken, last_id]
        if on_or_before is not None:
            clauses.append("substr(date_taken, 1, 10) <= ?")
            params.append(on_or_before)
        if profile is not None:
            clauses.append("profile = ?")
            params.append(profile)
        sql = "SELECT * FROM media"
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        order = "ASC" if before is not None else "DESC"
        sql += f" ORDER BY date_taken {order}, id {order} LIMIT ?"
        params.append(limit)
        with self._lock:
            rows = self._conn().execute(sql, params).fetchall()
        return [dict(r) for r in rows]

    def available_dates(self) -> dict[str, dict[str, list[int]]]:
        """Year -> month -> sorted unique days tree of all indexed media.

        Derived from the live ``date_taken`` column (ISO strings). Only
        year/month/day branches that have at least one indexed row appear, days
        are sorted ascending and de-duplicated. Backs ``GET /api/media/dates``.
        """
        with self._lock:
            rows = self._conn().execute("SELECT date_taken FROM media").fetchall()
        tree: dict[str, dict[str, set[int]]] = {}
        for r in rows:
            iso = r["date_taken"]
            year, month, day = iso[0:4], iso[5:7], int(iso[8:10])
            tree.setdefault(year, {}).setdefault(month, set()).add(day)
        return {
            year: {month: sorted(days) for month, days in sorted(months.items())}
            for year, months in sorted(tree.items())
        }

    # -- build -----------------------------------------------------------

    def set_folders(self, folders: list[str]) -> None:
        """Replace the configured scan roots (e.g. after a settings save) so the
        next build sees folders added at runtime without a restart."""
        with self._lock:
            self._folders = list(folders)

    def status(self) -> dict:
        """Return the current build status snapshot (no build is triggered)."""
        return self._status.snapshot()

    def is_building(self) -> bool:
        """True while a build holds the single in-process build lock."""
        if self._build_lock.acquire(blocking=False):
            self._build_lock.release()
            return False
        return True

    def build(self, force: bool = False) -> dict:
        """Incrementally (re)build the index over the configured folders.

        Acquires a single in-process build lock; a second concurrent trigger is
        a no-op that returns the currently-running status snapshot.
        """
        if not self._build_lock.acquire(blocking=False):
            return self._status.snapshot()
        try:
            return self._run_build()
        finally:
            self._build_lock.release()

    def index_path(self, path, fallback_date_taken: str | None = None) -> int | None:
        """Index a single already-placed file into the index, upserting one row.

        No folder walk is performed: the containing configured root is resolved
        and the same ``_index_file`` path used by ``build()`` runs for just this
        file, so its row (kind, root, metadata, thumbnail) is identical to a full
        build. Returns the row's media id, or ``None`` when the path lies outside
        every configured root or is not an allowed media type.

        ``fallback_date_taken`` (an ISO string) is used as ``date_taken`` when the
        file carries no EXIF capture date, in place of the file mtime — the sync
        lane passes the phone-supplied ``created_on`` so a synced photo lands at
        its true chronological position rather than at its sync time.
        """
        path = Path(path)
        root = self._root_for(path)
        if root is None:
            return None
        self._index_file(root, path, BuildStatus(), set(), fallback_date_taken)
        with self._lock:
            row = self._conn().execute(
                "SELECT id FROM media WHERE path=?", (str(path),)
            ).fetchone()
        return row["id"] if row is not None else None

    def _root_for(self, path: Path) -> Path | None:
        """Return the configured folders entry that contains ``path``, or None."""
        for folder in self._folders:
            root = Path(folder)
            try:
                path.relative_to(root)
            except ValueError:
                continue
            return root
        return None

    def _run_build(self) -> dict:
        # Carry the last completed build's summary forward so it stays visible
        # while a new build is in flight.
        prev = self._status
        status = BuildStatus(
            state="building",
            last_built=prev.last_built,
            last_count=prev.last_count,
        )
        self._status = status
        seen_paths: set[str] = set()
        for folder in self._folders:
            root = Path(folder)
            if not root.is_dir():
                status.skipped_roots.append(folder)
                continue
            for path in self._walk(root):
                self._index_file(root, path, status, seen_paths)

        self._prune(seen_paths, status)

        status.state = "idle"
        status.last_built = datetime.now(timezone.utc).isoformat()
        status.last_count = self._count()
        return status.snapshot()

    def _walk(self, root: Path):
        for dirpath, dirnames, filenames in os.walk(root):
            # Destinations are grouped <year>/<month>/..., so descending order
            # visits the newest year/month subfolders first, indexing (and
            # thumbnailing) the most relevant photos before the older backlog.
            dirnames.sort(reverse=True)
            for name in filenames:
                path = Path(dirpath) / name
                if _kind_for(path) is not None:
                    yield path

    def _index_file(self, root, path, status, seen_paths, fallback_date_taken=None) -> None:
        kind = _kind_for(path)
        if kind is None:
            return
        try:
            st = path.stat()
        except OSError:
            logger.warning("media build: cannot stat %s", path, exc_info=True)
            return
        path_str = str(path)
        seen_paths.add(path_str)
        status.processed += 1

        # Incremental skip: path present with unchanged mtime+size -> no work.
        existing = self._existing(path_str)
        if existing is not None and existing["mtime"] == st.st_mtime and existing["size"] == st.st_size:
            return

        width = height = None
        date_taken: str | None = None
        try:
            if kind == "image":
                width, height, date_taken = _image_metadata(path)
        except Exception:
            logger.warning("media build: cannot read metadata for %s", path, exc_info=True)
        if not date_taken:
            date_taken = fallback_date_taken or _mtime_iso(st.st_mtime)

        profile = self._profile_for(path_str)
        row_id = self._upsert(path_str, str(root), kind, st.st_size, st.st_mtime,
                              date_taken, width, height, profile)
        status.added += 1
        if kind == "video" and ffprobe_available():
            try:
                vcodec, acodec = probe_video_codecs(path)
                appsafe = video_appsafe(vcodec, acodec)
                self._set_video_safety(row_id, video_websafe(vcodec, acodec), appsafe)
                if not appsafe:
                    # The app can't play this codec natively, so build the H.264
                    # proxy in the background — its first play is then instant
                    # instead of blocking on a full encode. (HEVC is app-safe and
                    # streams as the original; its browser proxy builds on demand.)
                    self._submit_pretranscode(row_id, path)
            except Exception:
                logger.warning("media build: ffprobe failed for %s", path, exc_info=True)
        try:
            if self._thumbnail(row_id, path, kind):
                self._mark_thumb_ready(row_id)
        except Exception:
            logger.warning("media build: thumbnail failed for %s", path, exc_info=True)

    def _existing(self, path: str):
        with self._lock:
            return self._conn().execute(
                "SELECT id, mtime, size FROM media WHERE path=?", (path,)
            ).fetchone()

    def _upsert(self, path, root, kind, size, mtime, date_taken, width, height, profile=None) -> int:
        # Videos re-probe on every (re)index, so reset both safety flags to NULL;
        # images carry the columns forward unchanged (they never apply).
        safe_clause = "NULL" if kind == "video" else None
        websafe_clause = safe_clause or "video_websafe"
        appsafe_clause = safe_clause or "video_appsafe"
        with self._lock:
            self._conn().execute(
                "INSERT INTO media "
                "(path, root, kind, size, mtime, date_taken, width, height, "
                " video_websafe, video_appsafe, thumb_ready, profile) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?) "
                "ON CONFLICT(path) DO UPDATE SET "
                "  root=excluded.root, kind=excluded.kind, size=excluded.size, "
                "  mtime=excluded.mtime, date_taken=excluded.date_taken, "
                "  width=excluded.width, height=excluded.height, "
                f"  video_websafe={websafe_clause}, video_appsafe={appsafe_clause}, "
                "  thumb_ready=0, profile=excluded.profile",
                (path, root, kind, size, mtime, date_taken, width, height, profile),
            )
            self._conn().commit()
            row = self._conn().execute(
                "SELECT id FROM media WHERE path=?", (path,)
            ).fetchone()
        return row["id"]

    def _set_video_safety(self, row_id: int, websafe: bool, appsafe: bool) -> None:
        with self._lock:
            self._conn().execute(
                "UPDATE media SET video_websafe=?, video_appsafe=? WHERE id=?",
                (1 if websafe else 0, 1 if appsafe else 0, row_id),
            )
            self._conn().commit()

    def _mark_thumb_ready(self, row_id: int) -> None:
        with self._lock:
            self._conn().execute(
                "UPDATE media SET thumb_ready=1 WHERE id=?", (row_id,)
            )
            self._conn().commit()

    def _prune(self, seen_paths, status) -> None:
        """Drop rows whose file vanished from disk; delete their cached artifacts.

        Only roots that were actually scanned contribute to ``seen_paths``; a
        skipped (unmounted) root never prunes its rows, so an offline volume does
        not wipe its index.
        """
        scanned_roots = [
            f for f in self._folders if Path(f).is_dir()
        ]
        with self._lock:
            rows = self._conn().execute("SELECT id, path, root FROM media").fetchall()
        for r in rows:
            if r["path"] in seen_paths:
                continue
            # Only prune rows belonging to a root we actually scanned this run.
            if r["root"] not in scanned_roots:
                continue
            self._delete_row(r["id"])
            status.removed += 1

    def _delete_row(self, row_id: int) -> None:
        with self._lock:
            self._conn().execute("DELETE FROM media WHERE id=?", (row_id,))
            self._conn().commit()
        for cached in (self._thumbs_dir / f"{row_id}.jpg",
                       self._proxies_dir / f"{row_id}.mp4"):
            try:
                if cached.exists():
                    cached.unlink()
            except OSError:
                logger.warning("media build: cannot delete cache %s", cached, exc_info=True)

    def _count(self) -> int:
        with self._lock:
            return self._conn().execute("SELECT COUNT(*) FROM media").fetchone()[0]
