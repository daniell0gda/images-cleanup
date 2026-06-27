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

type Page = "launcher" | "devices";

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
        <Button
          variant="subtle"
          color="gray"
          size="xs"
          onClick={() => setPage(page === "devices" ? "launcher" : "devices")}
        >
          {page === "devices" ? "← Sortownik" : "📱 Urządzenia"}
        </Button>
      </Box>

      {page === "devices" ? (
        <Box className={classes.inner}>
          <DevicesView />
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
