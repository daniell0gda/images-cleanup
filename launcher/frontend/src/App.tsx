import { useState, useEffect, useCallback } from "react";
import {
  MantineProvider,
  Avatar,
  Text,
  Button,
  Loader,
  Badge,
  Stack,
  Group,
  Anchor,
  Box,
  Title,
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

function modeLabel(mode: string): string {
  return mode === "groupby" ? "Tags" : "Similarity";
}

function LauncherApp() {
  const [users, setUsers] = useState<UserEntry[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [jobStatus, setJobStatus] = useState<JobStatus>({ status: "idle" });
  const [sorterPort, setSorterPort] = useState<number>(8080);

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

  const startJob = (user: string, mode: string) => {
    fetch("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ user, mode }),
    })
      .then((r) => r.json())
      .then(setJobStatus)
      .catch(() => {});
  };

  const stopJob = () => {
    fetch("/api/jobs", { method: "DELETE" })
      .then((r) => r.json())
      .then(setJobStatus)
      .catch(() => {});
  };

  const running = jobStatus.status === "running" ? jobStatus : null;
  const selectedEntry = users.find((u) => u.user === selected) ?? null;
  const sorterUrl = `http://${window.location.hostname}:${sorterPort}`;

  return (
    <Box className={classes.root}>
      <Box className={classes.inner}>
        {/* Header */}
        <Box className={classes.hero}>
          <Text component="span" className={classes.eyebrow}>
            Photo Management
          </Text>
          <Title order={1} className={classes.title}>
            Image Sorter
          </Title>
          <Text className={classes.subtitle}>
            Select a profile to begin organizing your photos
          </Text>
        </Box>

        {/* Running status banner */}
        {running && (
          <Box className={classes.statusBanner}>
            <Group justify="space-between" align="center">
              <Group gap="sm">
                <Box className={classes.dot} />
                <Loader size="xs" color="green" type="dots" />
                <Text size="sm" c="green.4" fw={600}>
                  {running.user} &mdash;{" "}
                  {running.mode === "groupby" ? "GroupByTags" : "Similarity Search"}
                </Text>
              </Group>
              <Group gap="sm">
                <Anchor href={sorterUrl} target="_blank" size="sm" c="green.3" fw={700}>
                  Open UI →
                </Anchor>
                <Button
                  size="xs"
                  variant="subtle"
                  color="red"
                  onClick={stopJob}
                >
                  Stop
                </Button>
              </Group>
            </Group>
          </Box>
        )}

        {/* User cards */}
        <Group justify="center" gap="lg" mb="xl" align="flex-start">
          {users.map((u) => (
            <Box
              key={u.user}
              className={`${classes.userCard} ${selected === u.user ? classes.userCardSelected : ""}`}
              onClick={() => setSelected(u.user)}
            >
              <Avatar
                size={72}
                radius="xl"
                color={avatarColor(u.user)}
                variant="filled"
                mx="auto"
              >
                {initials(u.user)}
              </Avatar>
              <Text className={classes.userName}>{u.user}</Text>
              <Group justify="center" gap={4}>
                {u.modes.map((m) => (
                  <Badge
                    key={m}
                    size="xs"
                    variant="dot"
                    color={m === "groupby" ? "blue" : "violet"}
                  >
                    {modeLabel(m)}
                  </Badge>
                ))}
              </Group>
            </Box>
          ))}

          {users.length === 0 && (
            <Text className={classes.emptyState}>
              No configs found.
              <br />
              Add <code>config_&#123;user&#125;_groupby.yaml</code> files to the configs/ folder.
            </Text>
          )}
        </Group>

        {/* Action panel */}
        {selectedEntry && (
          <Box className={classes.actionPanel}>
            <Text component="span" className={classes.panelLabel}>
              {selectedEntry.user}
            </Text>
            <Stack gap="sm">
              {selectedEntry.modes.includes("groupby") && (
                <Button
                  size="md"
                  fullWidth
                  disabled={!!running}
                  variant="gradient"
                  gradient={{ from: "blue", to: "indigo", deg: 135 }}
                  onClick={() => startJob(selectedEntry.user, "groupby")}
                >
                  Start GroupByTags
                </Button>
              )}
              {selectedEntry.modes.includes("similarity") && (
                <Button
                  size="md"
                  fullWidth
                  disabled={!!running}
                  variant="gradient"
                  gradient={{ from: "violet", to: "grape", deg: 135 }}
                  onClick={() => startJob(selectedEntry.user, "similarity")}
                >
                  Start Similarity Search
                </Button>
              )}
            </Stack>
          </Box>
        )}
      </Box>
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
