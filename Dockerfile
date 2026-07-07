# syntax=docker/dockerfile:1

# ---- Stage 1: build both React frontends ----
FROM node:20-slim AS frontend-build
WORKDIR /build

# Sorter frontend (has a lockfile -> reproducible install)
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci
COPY frontend/ ./frontend/
RUN cd frontend && npm run build          # -> /build/frontend/dist

# Launcher frontend (no lockfile)
COPY launcher/frontend/package.json ./launcher/frontend/
RUN cd launcher/frontend && npm install
COPY launcher/frontend/ ./launcher/frontend/
RUN cd launcher/frontend && npm run build  # -> /build/launcher/dist


# ---- Stage 2: Python runtime ----
FROM python:3.11-slim AS runtime
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# System libraries required by opencv / ultralytics, plus ffmpeg/ffprobe for
# the video probe + transcode path (launcher/media.py shells out to both).
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install CPU-only torch first so ultralytics does not pull multi-GB CUDA wheels.
# (Pre-installing satisfies ultralytics' torch requirement so `pip install .`
# below won't drag in the default multi-GB CUDA wheels.)
RUN pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu

# Application source. Copy every top-level launcher module (not the tests/
# frontend subdirs) so new modules ship automatically instead of silently
# breaking the runtime when this list falls behind the imports in server.py.
COPY imagesorter/ ./imagesorter/
COPY launcher/*.py ./launcher/

# Install the project and its web extras straight from pyproject.toml so the
# runtime dependencies can never drift from the declared ones.
COPY pyproject.toml ./
RUN pip install ".[web]"

# Built frontends from stage 1
COPY --from=frontend-build /build/frontend/dist   ./frontend/dist
COPY --from=frontend-build /build/launcher/dist   ./launcher/dist

# Bake in the YOLO weights so the first run works fully offline
COPY yolo11s.pt ./

# Container defaults: bind the sorter on all interfaces at a fixed internal
# port, never try to open a desktop browser, read configs from a mounted volume.
# The *_PUBLIC_PORT values are the host ports the UIs are reached on; override
# them in compose if you remap the published ports.
ENV SORTER_HOST=0.0.0.0 \
    SORTER_PORT=8080 \
    IMAGESORTER_NO_BROWSER=1 \
    CONFIGS_DIR=/configs \
    INBOX_BASE=/data/inbox \
    SYNC_DB=/data/sync.db \
    MEDIA_DB=/data/media.db \
    MEDIA_THUMBS_DIR=/data/media_thumbs \
    MEDIA_PROXIES_DIR=/data/media_proxies \
    MEDIA_PREVIEWS_DIR=/data/media_previews \
    LAUNCHER_PUBLIC_PORT=7000 \
    SORTER_PUBLIC_PORT=8080

# Phone-sync state (trusted devices + synced index + upload sessions) and the
# media index (including albums, which share media.db) must survive container
# recreation, so keep them on a volume rather than the container's writable
# layer. For fast same-volume placement, mount INBOX_BASE on the same
# filesystem as your sorted destinations.
VOLUME /data

EXPOSE 7000 8080

CMD ["python", "-m", "launcher"]
