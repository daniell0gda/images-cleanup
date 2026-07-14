import { useState, useEffect, useCallback } from "react";
import {
  MantineProvider,
  Avatar,
  Text,
  Button,
  Stack,
  Group,
  Box,
  Title,
  Modal,
  ActionIcon,
  Badge,
  Switch,
  TextInput,
  Code,
} from "@mantine/core";
import classes from "./App.module.css";

interface UserEntry {
  user: string;
  modes: string[];
}

interface IdleStatus {
  status: "idle";
}

interface RunningStatus {
  status: "running";
  user: string;
  mode: string;
}

type JobStatus = IdleStatus | RunningStatus;

const AVATAR_COLORS = [
  "blue", "violet", "grape", "pink", "teal", "cyan", "indigo", "orange",
];

function avatarColor(name: string): string {
  return AVATAR_COLORS[name.charCodeAt(0) % AVATAR_COLORS.length];
}

function initials(name: string): string {
  return name.slice(0, 2).toUpperCase();
}

interface ModeInfo {
  icon: string;
  title: string;
  desc: string;
}

const MODE_INFO: Record<string, ModeInfo> = {
  groupby: {
    icon: "🏷️",
    title: "Sortuj według zawartości",
    desc: "Rozpoznaje osoby i obiekty na zdjęciach i układa je w foldery",
  },
  similarity: {
    icon: "🔍",
    title: "Znajdź podobne",
    desc: "Grupuje duplikaty i bardzo podobne ujęcia",
  },
};

function modeTitle(mode: string): string {
  return MODE_INFO[mode]?.title ?? mode;
}

type Page = "launcher" | "devices" | "settings" | "errors";

type DeviceStatus = "pending" | "trusted" | "revoked";

interface DeviceEntry {
  device_id: string;
  name: string;
  status: DeviceStatus;
  pairing_code: string | null;
  created_at: string;
  approved_at: string | null;
}

const DEVICE_STATUS_INFO: Record<DeviceStatus, { label: string; color: string }> = {
  pending: { label: "Oczekuje", color: "yellow" },
  trusted: { label: "Zaufane", color: "green" },
  revoked: { label: "Odwołane", color: "gray" },
};

function formatWhen(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString();
}

const STATUS_ORDER: DeviceStatus[] = ["pending", "trusted", "revoked"];

function DevicesView() {
  const [devices, setDevices] = useState<DeviceEntry[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const fetchDevices = useCallback(() => {
    fetch("/api/sync/devices")
      .then((r) => r.json())
      .then(setDevices)
      .catch(() => {});
  }, []);

  useEffect(() => {
    fetchDevices();
    const interval = setInterval(fetchDevices, 2000);
    return () => clearInterval(interval);
  }, [fetchDevices]);

  const act = useCallback(
    async (deviceId: string, action: "approve" | "revoke") => {
      setBusy(deviceId);
      try {
        await fetch(`/api/sync/devices/${deviceId}/${action}`, { method: "POST" });
        fetchDevices();
      } catch {
        /* the poll will reconcile */
      } finally {
        setBusy(null);
      }
    },
    [fetchDevices],
  );

  const sorted = [...devices].sort(
    (a, b) => STATUS_ORDER.indexOf(a.status) - STATUS_ORDER.indexOf(b.status),
  );

  return (
    <Box className={classes.view} key="devices">
      <Box className={classes.hero}>
        <Text component="span" className={classes.eyebrow}>
          Synchronizacja telefonu
        </Text>
        <Title order={1} className={classes.title}>
          Zaufane urządzenia
        </Title>
        <Text className={classes.subtitle}>
          Zatwierdź telefon, dopasowując kod do tego na ekranie aplikacji
        </Text>
      </Box>

      {sorted.length === 0 ? (
        <Text className={classes.emptyState}>
          Żadne urządzenie jeszcze się nie zgłosiło.
          <br />
          Otwórz aplikację na telefonie i wpisz adres tego serwera.
        </Text>
      ) : (
        <Stack gap="md">
          {sorted.map((d) => {
            const info = DEVICE_STATUS_INFO[d.status];
            const isBusy = busy === d.device_id;
            return (
              <Box key={d.device_id} className={classes.deviceCard}>
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <Box>
                    <Group gap="sm" align="center">
                      <Text className={classes.deviceName}>{d.name}</Text>
                      <Badge color={info.color} variant="light" radius="sm">
                        {info.label}
                      </Badge>
                    </Group>
                    {d.status === "pending" && d.pairing_code && (
                      <Group gap="xs" align="baseline" mt="sm">
                        <Text className={classes.pairingLabel}>Kod parowania</Text>
                        <Text className={classes.pairingCode}>{d.pairing_code}</Text>
                      </Group>
                    )}
                    <Text className={classes.deviceMeta}>
                      {d.status === "trusted" && d.approved_at
                        ? `Zatwierdzono ${formatWhen(d.approved_at)}`
                        : `Zgłoszono ${formatWhen(d.created_at)}`}
                    </Text>
                  </Box>
                  <Group gap="xs" wrap="nowrap">
                    {d.status !== "trusted" && (
                      <Button
                        size="xs"
                        color="green"
                        loading={isBusy}
                        onClick={() => act(d.device_id, "approve")}
                      >
                        Zatwierdź
                      </Button>
                    )}
                    {d.status !== "revoked" && (
                      <Button
                        size="xs"
                        variant="subtle"
                        color="red"
                        loading={isBusy}
                        onClick={() => act(d.device_id, "revoke")}
                      >
                        {d.status === "trusted" ? "Odwołaj" : "Odrzuć"}
                      </Button>
                    )}
                  </Group>
                </Group>
              </Box>
            );
          })}
        </Stack>
      )}
    </Box>
  );
}

interface DbRefreshSettings {
  enabled: boolean;
  schedule: string;
  next_run: string | null;
}

interface RefreshResult {
  checked: number;
  removed: number;
  at: string;
}

interface MediaLibrarySettings {
  enabled: boolean;
  folders: string[];
  schedule: string;
  next_run: string | null;
}

interface MediaBuildStatus {
  state: "idle" | "building";
  processed: number;
  total: number | null;
  added: number;
  removed: number;
  skipped_roots: string[];
  last_built: string | null;
  last_count: number | null;
}

interface SettingsPayload {
  db_refresh: DbRefreshSettings;
  last_refresh: RefreshResult | null;
  media_library: MediaLibrarySettings;
  media_build: MediaBuildStatus | null;
  public_base_url: string;
}

interface SyncProfile {
  profile_id: string;
  display_name: string;
}

function SettingsView() {
  const [enabled, setEnabled] = useState(true);
  const [schedule, setSchedule] = useState("0 1 * * *");
  const [nextRun, setNextRun] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<RefreshResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [mediaEnabled, setMediaEnabled] = useState(true);
  const [mediaFolders, setMediaFolders] = useState<string[]>([]);
  const [mediaSchedule, setMediaSchedule] = useState("0 2 * * *");
  const [mediaNextRun, setMediaNextRun] = useState<string | null>(null);
  const [mediaBuild, setMediaBuild] = useState<MediaBuildStatus | null>(null);
  const [mediaSaving, setMediaSaving] = useState(false);
  const [mediaError, setMediaError] = useState<string | null>(null);
  const [building, setBuilding] = useState(false);

  const [publicBaseUrl, setPublicBaseUrl] = useState("");
  const [publicSaving, setPublicSaving] = useState(false);
  const [publicError, setPublicError] = useState<string | null>(null);

  const [profiles, setProfiles] = useState<SyncProfile[]>([]);
  const [profileName, setProfileName] = useState("");
  const [profileCreating, setProfileCreating] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);

  const fetchProfiles = useCallback(() => {
    fetch("/api/sync/profiles")
      .then((r) => r.json())
      .then((p: SyncProfile[]) => setProfiles(Array.isArray(p) ? p : []))
      .catch(() => {});
  }, []);

  const applyPayload = useCallback((p: SettingsPayload) => {
    setEnabled(p.db_refresh.enabled);
    setSchedule(p.db_refresh.schedule);
    setNextRun(p.db_refresh.next_run);
    setLastRefresh(p.last_refresh);
    setMediaEnabled(p.media_library.enabled);
    setMediaFolders(p.media_library.folders);
    setMediaSchedule(p.media_library.schedule);
    setMediaNextRun(p.media_library.next_run);
    setMediaBuild(p.media_build);
    setPublicBaseUrl(p.public_base_url);
  }, []);

  useEffect(() => {
    fetch("/api/settings")
      .then((r) => r.json())
      .then(applyPayload)
      .catch(() => {});
    fetchProfiles();
  }, [applyPayload, fetchProfiles]);

  const createProfile = useCallback(async () => {
    setProfileCreating(true);
    setProfileError(null);
    try {
      const r = await fetch("/api/sync/profiles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: profileName.trim() }),
      });
      if (r.ok) {
        setProfileName("");
        fetchProfiles();
      } else {
        const body = await r.json().catch(() => null);
        const fallback =
          r.status === 409
            ? "Profil o tej nazwie już istnieje."
            : "Nieprawidłowa nazwa profilu.";
        setProfileError(body?.detail ?? fallback);
      }
    } catch {
      setProfileError("Serwer jest nieosiągalny.");
    } finally {
      setProfileCreating(false);
    }
  }, [profileName, fetchProfiles]);

  const deleteProfile = useCallback(
    async (profileId: string) => {
      try {
        await fetch(`/api/sync/profiles/${encodeURIComponent(profileId)}`, {
          method: "DELETE",
        });
        fetchProfiles();
      } catch {
        /* leave the list as-is; the admin can retry */
      }
    },
    [fetchProfiles],
  );

  const save = useCallback(async () => {
    setSaving(true);
    setError(null);
    try {
      const r = await fetch("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ db_refresh: { enabled, schedule } }),
      });
      if (r.ok) {
        applyPayload(await r.json());
      } else {
        const body = await r.json().catch(() => null);
        setError(body?.detail ?? "Nie udało się zapisać ustawień.");
      }
    } catch {
      setError("Serwer jest nieosiągalny.");
    } finally {
      setSaving(false);
    }
  }, [enabled, schedule, applyPayload]);

  const refreshNow = useCallback(async () => {
    setRefreshing(true);
    try {
      const r = await fetch("/api/settings/refresh", { method: "POST" });
      if (r.ok) setLastRefresh(await r.json());
    } catch {
      /* ignore; the user can retry */
    } finally {
      setRefreshing(false);
    }
  }, []);

  const addFolder = useCallback(() => {
    setMediaFolders((f) => [...f, ""]);
  }, []);

  const removeFolder = useCallback((index: number) => {
    setMediaFolders((f) => f.filter((_, i) => i !== index));
  }, []);

  const changeFolder = useCallback((index: number, value: string) => {
    setMediaFolders((f) => f.map((v, i) => (i === index ? value : v)));
  }, []);

  const saveMedia = useCallback(async () => {
    setMediaSaving(true);
    setMediaError(null);
    try {
      const folders = mediaFolders.map((f) => f.trim()).filter((f) => f !== "");
      const r = await fetch("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          media_library: {
            enabled: mediaEnabled,
            schedule: mediaSchedule,
            folders,
          },
        }),
      });
      if (r.ok) {
        applyPayload(await r.json());
      } else {
        const body = await r.json().catch(() => null);
        setMediaError(body?.detail ?? "Nie udało się zapisać ustawień.");
      }
    } catch {
      setMediaError("Serwer jest nieosiągalny.");
    } finally {
      setMediaSaving(false);
    }
  }, [mediaEnabled, mediaSchedule, mediaFolders, applyPayload]);

  const savePublicBaseUrl = useCallback(async () => {
    setPublicSaving(true);
    setPublicError(null);
    try {
      const r = await fetch("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ public_base_url: publicBaseUrl.trim() }),
      });
      if (r.ok) {
        applyPayload(await r.json());
      } else {
        const body = await r.json().catch(() => null);
        setPublicError(body?.detail ?? "Nie udało się zapisać ustawień.");
      }
    } catch {
      setPublicError("Serwer jest nieosiągalny.");
    } finally {
      setPublicSaving(false);
    }
  }, [publicBaseUrl, applyPayload]);

  const buildNow = useCallback(async () => {
    setBuilding(true);
    try {
      const r = await fetch("/api/media/build", { method: "POST" });
      if (r.ok) setMediaBuild(await r.json());
    } catch {
      setBuilding(false);
      return;
    }
    // Poll the build status until it returns to idle.
    const poll = async () => {
      try {
        const r = await fetch("/api/media/build/status");
        if (r.ok) {
          const s: MediaBuildStatus = await r.json();
          setMediaBuild(s);
          if (s.state === "building") {
            setTimeout(poll, 1000);
            return;
          }
        }
      } catch {
        /* stop polling on error; the user can retry */
      }
      setBuilding(false);
    };
    setTimeout(poll, 1000);
  }, []);

  return (
    <Box className={classes.view} key="settings">
      <Box className={classes.hero}>
        <Text component="span" className={classes.eyebrow}>
          Synchronizacja telefonu
        </Text>
        <Title order={1} className={classes.title}>
          Ustawienia
        </Title>
        <Text className={classes.subtitle}>
          Odświeżanie bazy zsynchronizowanych plików
        </Text>
      </Box>

      <Stack gap="md">
        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Profile synchronizacji</Text>
          <Text className={classes.deviceMeta} mb="md">
            Profile, na które telefony synchronizują zdjęcia. Nazwa profilu trafia
            do metadanych zdjęć i pozwala filtrować je w galerii.
          </Text>

          <Stack gap="md">
            {profiles.length > 0 ? (
              <Stack gap="xs">
                {profiles.map((p) => (
                  <Group key={p.profile_id} justify="space-between" wrap="nowrap">
                    <Text className={classes.deviceName}>{p.display_name}</Text>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label="Usuń profil"
                      onClick={() => deleteProfile(p.profile_id)}
                    >
                      ✕
                    </ActionIcon>
                  </Group>
                ))}
              </Stack>
            ) : (
              <Text className={classes.deviceMeta}>
                Nie utworzono jeszcze żadnego profilu.
              </Text>
            )}

            <Group gap="xs" wrap="nowrap" align="flex-start">
              <TextInput
                style={{ flex: 1 }}
                placeholder="Nazwa profilu"
                value={profileName}
                onChange={(e) => setProfileName(e.currentTarget.value)}
                error={profileError}
              />
              <Button onClick={createProfile} loading={profileCreating}>
                Utwórz
              </Button>
            </Group>
          </Stack>
        </Box>

        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Automatyczne odświeżanie</Text>
          <Text className={classes.deviceMeta} mb="md">
            Sprawdza, czy zsynchronizowane pliki nadal istnieją w miejscu
            docelowym. Usunięte ręcznie pliki zostaną oznaczone do ponownej
            synchronizacji przy następnym połączeniu telefonu.
          </Text>

          <Stack gap="md">
            <Switch
              checked={enabled}
              onChange={(e) => setEnabled(e.currentTarget.checked)}
              label="Włączone"
            />
            <TextInput
              label="Harmonogram (cron)"
              description="np. 0 1 * * * — codziennie o 1:00"
              value={schedule}
              onChange={(e) => setSchedule(e.currentTarget.value)}
              error={error}
              disabled={!enabled}
            />
            {enabled && nextRun && (
              <Text className={classes.deviceMeta}>
                Następne odświeżanie: {formatWhen(nextRun)}
              </Text>
            )}
            <Group justify="flex-end">
              <Button onClick={save} loading={saving}>
                Zapisz
              </Button>
            </Group>
          </Stack>
        </Box>

        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Odśwież teraz</Text>
          <Text className={classes.deviceMeta} mb="md">
            Uruchom odświeżanie ręcznie, np. zaraz po usunięciu plików z miejsca
            docelowego.
          </Text>
          <Group justify="space-between" align="center">
            <Text className={classes.deviceMeta}>
              {lastRefresh ? (
                <>
                  Ostatnio: sprawdzono {lastRefresh.checked}, usunięto{" "}
                  <Code>{lastRefresh.removed}</Code> ({formatWhen(lastRefresh.at)})
                </>
              ) : (
                "Jeszcze nie uruchomiono."
              )}
            </Text>
            <Button variant="light" onClick={refreshNow} loading={refreshing}>
              Odśwież teraz
            </Button>
          </Group>
        </Box>

        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Biblioteka zdjęć</Text>
          <Text className={classes.deviceMeta} mb="md">
            Indeksuje zdjęcia i filmy z wybranych folderów na serwerze, aby
            przeglądać je w aplikacji na telefonie. Foldery są wspólne dla
            wszystkich profili.
          </Text>

          <Stack gap="md">
            <Switch
              checked={mediaEnabled}
              onChange={(e) => setMediaEnabled(e.currentTarget.checked)}
              label="Włączone"
            />

            <Stack gap="xs">
              <Text className={classes.deviceMeta}>Foldery</Text>
              {mediaFolders.map((folder, i) => (
                <Group key={i} gap="xs" wrap="nowrap">
                  <TextInput
                    style={{ flex: 1 }}
                    placeholder="Ścieżka do folderu"
                    value={folder}
                    onChange={(e) => changeFolder(i, e.currentTarget.value)}
                  />
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label="Usuń folder"
                    onClick={() => removeFolder(i)}
                  >
                    ✕
                  </ActionIcon>
                </Group>
              ))}
              <Group justify="flex-start">
                <Button variant="subtle" size="xs" onClick={addFolder}>
                  Dodaj folder
                </Button>
              </Group>
            </Stack>

            <TextInput
              label="Harmonogram (cron)"
              description="np. 0 2 * * * — codziennie o 2:00"
              value={mediaSchedule}
              onChange={(e) => setMediaSchedule(e.currentTarget.value)}
              error={mediaError}
              disabled={!mediaEnabled}
            />
            {mediaEnabled && mediaNextRun && (
              <Text className={classes.deviceMeta}>
                Następne indeksowanie: {formatWhen(mediaNextRun)}
              </Text>
            )}

            {mediaBuild && (
              <Box>
                <Text className={classes.deviceMeta}>
                  {mediaBuild.last_built ? (
                    <>
                      Ostatnie indeksowanie: <Code>{mediaBuild.last_count}</Code>{" "}
                      plików ({formatWhen(mediaBuild.last_built)}); dodano{" "}
                      {mediaBuild.added}, usunięto {mediaBuild.removed}
                    </>
                  ) : (
                    "Jeszcze nie zindeksowano."
                  )}
                </Text>
                {mediaBuild.skipped_roots.length > 0 && (
                  <Text c="yellow.5" size="sm" mt="xs">
                    Pominięto niedostępne foldery:{" "}
                    {mediaBuild.skipped_roots.join(", ")}
                  </Text>
                )}
              </Box>
            )}

            <Group justify="flex-end">
              <Button onClick={saveMedia} loading={mediaSaving}>
                Zapisz
              </Button>
            </Group>
          </Stack>
        </Box>

        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Indeksuj teraz</Text>
          <Text className={classes.deviceMeta} mb="md">
            Uruchom indeksowanie biblioteki zdjęć ręcznie, np. po dodaniu nowych
            plików do folderów.
          </Text>
          <Group justify="space-between" align="center">
            <Text className={classes.deviceMeta}>
              {building ? (
                <>
                  Indeksowanie… przetworzono {mediaBuild?.processed ?? 0}
                  {mediaBuild?.total != null ? ` / ${mediaBuild.total}` : ""}{" "}
                  plików
                </>
              ) : (
                "Gotowe."
              )}
            </Text>
            <Button
              variant="light"
              onClick={buildNow}
              loading={building}
              disabled={building}
            >
              Odśwież teraz
            </Button>
          </Group>
        </Box>

        <Box className={classes.deviceCard}>
          <Text className={classes.deviceName}>Adres publicznych linków</Text>
          <Text className={classes.deviceMeta} mb="md">
            Bazowy adres (schemat i host) używany do budowania linków do
            udostępnionych albumów, np. https://photos.example.com. Pozostaw
            puste, aby użyć adresu, z którego łączy się aplikacja.
          </Text>

          <Stack gap="md">
            <TextInput
              label="Publiczny adres bazowy"
              placeholder="https://photos.example.com"
              value={publicBaseUrl}
              onChange={(e) => setPublicBaseUrl(e.currentTarget.value)}
              error={publicError}
            />
            <Group justify="flex-end">
              <Button onClick={savePublicBaseUrl} loading={publicSaving}>
                Zapisz
              </Button>
            </Group>
          </Stack>
        </Box>
      </Stack>
    </Box>
  );
}

interface ErrorEntry {
  id: number;
  ts: string;
  source: string;
  level: string;
  logger: string | null;
  message: string;
  traceback: string | null;
  device_id: string | null;
}

const ERROR_SOURCE_OPTIONS = [
  { value: "", label: "Wszystkie źródła" },
  { value: "launcher", label: "Launcher" },
  { value: "sorter", label: "Sortownik" },
  { value: "android", label: "Telefon" },
];

const ERROR_LEVEL_OPTIONS = [
  { value: "", label: "Wszystkie poziomy" },
  { value: "WARNING", label: "WARNING" },
  { value: "ERROR", label: "ERROR" },
  { value: "CRITICAL", label: "CRITICAL" },
];

function ErrorsView() {
  const [errors, setErrors] = useState<ErrorEntry[]>([]);
  const [source, setSource] = useState("");
  const [level, setLevel] = useState("");
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const fetchErrors = useCallback(() => {
    const params = new URLSearchParams();
    if (source) params.set("source", source);
    if (level) params.set("level", level);
    const qs = params.toString();
    fetch(`/api/errors${qs ? `?${qs}` : ""}`)
      .then((r) => r.json())
      .then((rows: ErrorEntry[]) => setErrors(Array.isArray(rows) ? rows : []))
      .catch(() => {});
  }, [source, level]);

  // Fetch on open and whenever a filter changes; there is deliberately no
  // polling interval here (unlike DevicesView) — errors refresh on demand only.
  useEffect(() => {
    fetchErrors();
  }, [fetchErrors]);

  const clearAll = useCallback(async () => {
    try {
      await fetch("/api/errors", { method: "DELETE" });
      setErrors([]);
    } catch {
      /* leave the list as-is; the admin can retry */
    }
  }, []);

  const deleteOne = useCallback(async (id: number) => {
    try {
      await fetch(`/api/errors/${id}`, { method: "DELETE" });
      setErrors((es) => es.filter((e) => e.id !== id));
    } catch {
      /* leave the row as-is; the admin can retry */
    }
  }, []);

  return (
    <Box className={classes.view} key="errors">
      <Box className={classes.hero}>
        <Text component="span" className={classes.eyebrow}>
          Synchronizacja telefonu
        </Text>
        <Title order={1} className={classes.title}>
          Błędy
        </Title>
        <Text className={classes.subtitle}>
          Ostatnie ostrzeżenia i błędy z serwera, sortownika i telefonów
        </Text>
      </Box>

      <Group justify="space-between" align="flex-end" mb="md" wrap="nowrap">
        <Group gap="xs" wrap="nowrap">
          <label className={classes.filterField}>
            <span className={classes.filterLabel}>Źródło</span>
            <select
              aria-label="Źródło"
              className={classes.filterSelect}
              value={source}
              onChange={(e) => setSource(e.currentTarget.value)}
            >
              {ERROR_SOURCE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className={classes.filterField}>
            <span className={classes.filterLabel}>Poziom</span>
            <select
              aria-label="Poziom"
              className={classes.filterSelect}
              value={level}
              onChange={(e) => setLevel(e.currentTarget.value)}
            >
              {ERROR_LEVEL_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </Group>
        <Group gap="xs" wrap="nowrap">
          <Button variant="light" size="xs" onClick={fetchErrors}>
            Odśwież
          </Button>
          <Button variant="subtle" color="red" size="xs" onClick={clearAll}>
            Wyczyść wszystko
          </Button>
        </Group>
      </Group>

      {errors.length === 0 ? (
        <Text className={classes.emptyState}>Brak zarejestrowanych błędów.</Text>
      ) : (
        <Stack gap="xs">
          {errors.map((e) => {
            const expanded = expandedId === e.id;
            const summary = `${e.ts} · ${e.source} · ${e.level} · ${e.message}`;
            return (
              <Box key={e.id} className={classes.deviceCard}>
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <Box
                    style={{ flex: 1, cursor: "pointer" }}
                    role="button"
                    tabIndex={0}
                    onClick={() => setExpandedId(expanded ? null : e.id)}
                    onKeyDown={(ev) => {
                      if (ev.key === "Enter" || ev.key === " ")
                        setExpandedId(expanded ? null : e.id);
                    }}
                  >
                    <Text className={classes.errorSummary}>{summary}</Text>
                  </Box>
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label="Usuń błąd"
                    onClick={() => deleteOne(e.id)}
                  >
                    ✕
                  </ActionIcon>
                </Group>
                {expanded && (
                  <Stack gap="xs" mt="sm">
                    <Text className={classes.deviceMeta}>
                      Logger: {e.logger ?? "—"}
                    </Text>
                    <Text className={classes.deviceMeta}>
                      Urządzenie: {e.device_id ?? "—"}
                    </Text>
                    {e.traceback ? (
                      <Code block>{e.traceback}</Code>
                    ) : (
                      <Text className={classes.deviceMeta}>Brak śladu stosu.</Text>
                    )}
                  </Stack>
                )}
              </Box>
            );
          })}
        </Stack>
      )}
    </Box>
  );
}

function LauncherApp() {
  const [page, setPage] = useState<Page>("launcher");
  const [users, setUsers] = useState<UserEntry[]>([]);
  const [activeProfile, setActiveProfile] = useState<string | null>(null);
  const [jobStatus, setJobStatus] = useState<JobStatus>({ status: "idle" });
  const [sorterPort, setSorterPort] = useState<number>(8080);
  const [pending, setPending] = useState<{ user: string; mode: string } | null>(null);
  const [starting, setStarting] = useState(false);

  const fetchUsers = useCallback(() => {
    fetch("/api/users")
      .then((r) => r.json())
      .then(setUsers)
      .catch(() => {});
  }, []);

  const fetchStatus = useCallback(() => {
    fetch("/api/status")
      .then((r) => r.json())
      .then(setJobStatus)
      .catch(() => {});
  }, []);

  useEffect(() => {
    fetchUsers();
    fetchStatus();
    fetch("/api/config")
      .then((r) => r.json())
      .then((cfg: { sorter_port?: number }) => {
        if (cfg.sorter_port) setSorterPort(cfg.sorter_port);
      })
      .catch(() => {});
    const interval = setInterval(fetchStatus, 2000);
    return () => clearInterval(interval);
  }, [fetchUsers, fetchStatus]);

  const running = jobStatus.status === "running" ? jobStatus : null;
  const sorterUrl = `http://${window.location.hostname}:${sorterPort}`;

  const doStart = useCallback(async (user: string, mode: string) => {
    setStarting(true);
    try {
      const r = await fetch("/api/jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ user, mode }),
      });
      const data = await r.json();
      if (r.ok) setJobStatus(data);
    } catch {
      /* leave status as-is; the poll will reconcile */
    } finally {
      setStarting(false);
    }
  }, []);

  const pickAction = (user: string, mode: string) => {
    if (running) {
      if (running.user === user && running.mode === mode) return; // use "Otwórz"
      setPending({ user, mode });
      return;
    }
    doStart(user, mode);
  };

  const confirmReplace = async () => {
    if (!pending) return;
    const { user, mode } = pending;
    setPending(null);
    await fetch("/api/jobs", { method: "DELETE" }).catch(() => {});
    await doStart(user, mode);
  };

  const stopJob = () => {
    fetch("/api/jobs", { method: "DELETE" })
      .then((r) => r.json())
      .then(setJobStatus)
      .catch(() => {});
  };

  const activeEntry = users.find((u) => u.user === activeProfile) ?? null;

  return (
    <Box className={classes.root}>
      <Box className={classes.topNav}>
        <Group gap="xs">
          {page !== "launcher" && (
            <Button variant="subtle" color="gray" size="xs" onClick={() => setPage("launcher")}>
              ← Sortownik
            </Button>
          )}
          {page !== "devices" && (
            <Button variant="subtle" color="gray" size="xs" onClick={() => setPage("devices")}>
              📱 Urządzenia
            </Button>
          )}
          {page !== "settings" && (
            <Button variant="subtle" color="gray" size="xs" onClick={() => setPage("settings")}>
              ⚙️ Ustawienia
            </Button>
          )}
          {page !== "errors" && (
            <Button variant="subtle" color="gray" size="xs" onClick={() => setPage("errors")}>
              🐞 Błędy
            </Button>
          )}
        </Group>
      </Box>

      {page === "devices" ? (
        <Box className={classes.inner}>
          <DevicesView />
        </Box>
      ) : page === "settings" ? (
        <Box className={classes.inner}>
          <SettingsView />
        </Box>
      ) : page === "errors" ? (
        <Box className={classes.inner}>
          <ErrorsView />
        </Box>
      ) : (
      <Box className={classes.inner}>
        {/* Persistent running banner */}
        {running && (
          <Box className={classes.statusBanner}>
            <Group justify="space-between" align="center" wrap="nowrap">
              <Group gap="sm" wrap="nowrap">
                <Box className={classes.dot} />
                <Text size="sm" c="green.4" fw={600}>
                  W toku &mdash; {running.user}: {modeTitle(running.mode)}
                </Text>
              </Group>
              <Group gap="xs" wrap="nowrap">
                <Button
                  component="a"
                  href={sorterUrl}
                  target="_blank"
                  size="xs"
                  color="green"
                  variant="filled"
                >
                  Otwórz
                </Button>
                <Button size="xs" variant="subtle" color="red" onClick={stopJob}>
                  Zatrzymaj
                </Button>
              </Group>
            </Group>
          </Box>
        )}

        {activeEntry === null ? (
          /* ---------- Step 1: profile picker ---------- */
          <Box className={classes.view} key="profiles">
            <Box className={classes.hero}>
              <Text component="span" className={classes.eyebrow}>
                Zarządzanie zdjęciami
              </Text>
              <Title order={1} className={classes.title}>
                Sortownik zdjęć
              </Title>
              <Text className={classes.subtitle}>
                Wybierz profil, aby rozpocząć
              </Text>
            </Box>

            <Box className={classes.profileGrid}>
              {users.map((u) => {
                const isRunning = running?.user === u.user;
                return (
                  <Box
                    key={u.user}
                    className={classes.profile}
                    onClick={() => setActiveProfile(u.user)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") setActiveProfile(u.user);
                    }}
                  >
                    <Box className={classes.avatarWrap}>
                      <Avatar
                        size={104}
                        radius="50%"
                        color={avatarColor(u.user)}
                        variant="filled"
                      >
                        {initials(u.user)}
                      </Avatar>
                      {isRunning && <Box className={classes.runningRing} />}
                    </Box>
                    <Text className={classes.profileName}>{u.user}</Text>
                  </Box>
                );
              })}
            </Box>

            {users.length === 0 && (
              <Text className={classes.emptyState}>
                Nie znaleziono profili.
                <br />
                Dodaj pliki <code>config_&#123;imię&#125;_groupby.yaml</code> do
                folderu configs/.
              </Text>
            )}
          </Box>
        ) : (
          /* ---------- Step 2: action picker ---------- */
          <Box className={classes.view} key="actions">
            <Group className={classes.backRow} gap="md" wrap="nowrap">
              <ActionIcon
                variant="subtle"
                color="gray"
                size="lg"
                radius="xl"
                aria-label="Wróć"
                onClick={() => setActiveProfile(null)}
              >
                <Text fz={24} lh={1}>
                  ←
                </Text>
              </ActionIcon>
              <Avatar
                size={48}
                radius="50%"
                color={avatarColor(activeEntry.user)}
                variant="filled"
              >
                {initials(activeEntry.user)}
              </Avatar>
              <Box>
                <Text className={classes.backName}>{activeEntry.user}</Text>
                <Text className={classes.backSub}>Co chcesz zrobić?</Text>
              </Box>
            </Group>

            <Group className={classes.actionGrid} align="stretch" grow>
              {["groupby", "similarity"].map((mode) => {
                if (!activeEntry.modes.includes(mode)) return null;
                const info = MODE_INFO[mode];
                const isThisRunning =
                  running?.user === activeEntry.user && running?.mode === mode;
                const busy = starting && pending === null;

                if (isThisRunning) {
                  return (
                    <Box
                      key={mode}
                      component="a"
                      href={sorterUrl}
                      target="_blank"
                      className={`${classes.actionCard} ${classes.actionCardRunning}`}
                    >
                      <Text className={classes.actionIcon}>{info.icon}</Text>
                      <Text className={classes.actionTitle}>{info.title}</Text>
                      <Group gap={6} justify="center" mt={6}>
                        <Box className={classes.dot} />
                        <Text className={classes.runningLabel}>
                          W toku — kliknij, aby otworzyć
                        </Text>
                      </Group>
                    </Box>
                  );
                }

                return (
                  <Box
                    key={mode}
                    className={classes.actionCard}
                    role="button"
                    tabIndex={0}
                    onClick={() => !busy && pickAction(activeEntry.user, mode)}
                    onKeyDown={(e) => {
                      if ((e.key === "Enter" || e.key === " ") && !busy)
                        pickAction(activeEntry.user, mode);
                    }}
                  >
                    <Text className={classes.actionIcon}>{info.icon}</Text>
                    <Text className={classes.actionTitle}>{info.title}</Text>
                    <Text className={classes.actionDesc}>{info.desc}</Text>
                  </Box>
                );
              })}
            </Group>
          </Box>
        )}
      </Box>
      )}

      {/* Confirmation when replacing a running job */}
      <Modal
        opened={pending !== null}
        onClose={() => setPending(null)}
        centered
        withCloseButton={false}
        title="Inne zadanie jest w toku"
      >
        {running && pending && (
          <Stack gap="lg">
            <Text size="sm" c="dimmed">
              Trwa już porządkowanie dla profilu{" "}
              <Text span fw={700} c="white">
                {running.user}
              </Text>{" "}
              ({modeTitle(running.mode)}). Czy zatrzymać je i rozpocząć{" "}
              <Text span fw={700} c="white">
                „{modeTitle(pending.mode)}”
              </Text>{" "}
              dla profilu{" "}
              <Text span fw={700} c="white">
                {pending.user}
              </Text>
              ?
            </Text>
            <Group justify="flex-end" gap="sm">
              <Button variant="default" onClick={() => setPending(null)}>
                Anuluj
              </Button>
              <Button color="red" onClick={confirmReplace} loading={starting}>
                Zatrzymaj i rozpocznij
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Box>
  );
}

export function App() {
  return (
    <MantineProvider defaultColorScheme="dark">
      <LauncherApp />
    </MantineProvider>
  );
}
