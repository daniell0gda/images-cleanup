"""Pydantic request bodies for the launcher + phone-sync HTTP API.

Collected here so ``server.py`` carries route logic, not schema declarations.
The route handlers import these (aliased to their historical ``_Name`` spellings)
and reference them as FastAPI body parameters.
"""
from pydantic import BaseModel


class JobRequest(BaseModel):
    user: str
    mode: str


class DeviceRequest(BaseModel):
    device_id: str
    name: str


class Identity(BaseModel):
    name: str
    created_on: str
    size: int


class SessionRequest(BaseModel):
    profile_id: str
    force_place: bool = False


class ProfileRequest(BaseModel):
    name: str


class DbRefreshSettings(BaseModel):
    enabled: bool
    schedule: str


class MediaLibrarySettings(BaseModel):
    enabled: bool
    folders: list[str]
    schedule: str


class SettingsRequest(BaseModel):
    db_refresh: DbRefreshSettings | None = None
    media_library: MediaLibrarySettings | None = None
    public_base_url: str | None = None


class AlbumCreateRequest(BaseModel):
    name: str
    media_ids: list[int] = []
    created_by: str | None = None


class AlbumRenameRequest(BaseModel):
    name: str


class AlbumItemsRequest(BaseModel):
    media_ids: list[int] = []
