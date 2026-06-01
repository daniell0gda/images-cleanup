import { useEffect, useMemo, useState } from "react";
import {
  AppShell,
  Button,
  Checkbox,
  Group,
  Image,
  Loader,
  Modal,
  Notification,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";

interface SimilarityGroup {
  id: number;
  paths: string[];
}

interface ScanProgress {
  scanned: number;
  total: number;
}

interface ComparingState {
  total: number;
}

const THUMB_SIZE = 100;
const MODAL_IMAGE_HEIGHT = 360;

function imageUrl(path: string): string {
  return `/api/images/${encodeURIComponent(path)}`;
}

export default function App() {
  const [groups, setGroups] = useState<SimilarityGroup[]>([]);
  const [scanComplete, setScanComplete] = useState(false);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [progress, setProgress] = useState<ScanProgress | null>(null);
  const [comparing, setComparing] = useState<ComparingState | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [activeGroup, setActiveGroup] = useState<SimilarityGroup | null>(null);
  const [modalOpen, modalHandlers] = useDisclosure(false);
  const [confirmOpen, confirmHandlers] = useDisclosure(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const es = new EventSource("/api/stream");
    es.addEventListener("group", (evt: MessageEvent) => {
      const data: SimilarityGroup = JSON.parse(evt.data);
      setGroups((prev) => {
        const idx = prev.findIndex((g) => g.id === data.id);
        if (idx === -1) {
          // Append new group (preserve order — never reorder existing)
          return [...prev, data];
        }
        // Update the existing row in place
        const next = prev.slice();
        next[idx] = data;
        return next;
      });
    });
    es.addEventListener("progress", (evt: MessageEvent) => {
      const data: { scanned: number; total: number } = JSON.parse(evt.data);
      setProgress({ scanned: data.scanned, total: data.total });
    });
    es.addEventListener("comparing", (evt: MessageEvent) => {
      const data: { total: number } = JSON.parse(evt.data);
      setComparing({ total: data.total });
    });
    es.addEventListener("complete", () => {
      setScanComplete(true);
      es.close();
    });
    es.onerror = () => {
      setStreamError("Connection to scanner lost");
    };
    return () => {
      es.close();
    };
  }, []);

  const toggleSelected = (path: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  const openGroup = (group: SimilarityGroup) => {
    setActiveGroup(group);
    modalHandlers.open();
  };

  const selectedCount = selected.size;

  const visibleGroups = useMemo(
    () => groups.filter((g) => g.paths.length >= 2),
    [groups]
  );

  const handleConfirmDelete = async () => {
    setDeleting(true);
    try {
      const paths = Array.from(selected);
      const response = await fetch("/api/images", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(paths),
      });
      if (!response.ok) {
        notifications.show({
          color: "red",
          title: "Delete failed",
          message: `Server returned ${response.status}`,
        });
        return;
      }
      const body: { trashed: string[]; failed: { path: string; error: string }[] } =
        await response.json();
      const trashed = new Set(body.trashed);
      // Remove trashed paths from groups; drop any group whose remaining count < 2
      setGroups((prev) =>
        prev
          .map((g) => ({ ...g, paths: g.paths.filter((p) => !trashed.has(p)) }))
          .filter((g) => g.paths.length >= 2)
      );
      setSelected((prev) => {
        const next = new Set(prev);
        body.trashed.forEach((p) => next.delete(p));
        return next;
      });
      notifications.show({
        color: "green",
        title: "Deleted",
        message: `${body.trashed.length} image(s) moved to trash`,
      });
      if (body.failed.length > 0) {
        notifications.show({
          color: "orange",
          title: "Some deletions failed",
          message: body.failed.map((f) => f.path).join(", "),
        });
      }
      confirmHandlers.close();
      modalHandlers.close();
    } finally {
      setDeleting(false);
    }
  };

  return (
    <AppShell
      header={{ height: 60 }}
      padding="md"
    >
      <AppShell.Header p="md">
        <Group justify="space-between" align="center">
          <Title order={3}>Similarity Search</Title>
          <Group>
            {!scanComplete && (
              <Group gap="xs">
                <Loader size="sm" />
                <Text size="sm" c="dimmed">
                  {comparing
                    ? `Comparing ${comparing.total.toLocaleString()} images...`
                    : progress
                    ? `Scanning... ${progress.scanned.toLocaleString()} / ${progress.total.toLocaleString()} images`
                    : "Scanning..."}
                </Text>
              </Group>
            )}
            {scanComplete && (
              <Text size="sm" c="dimmed">
                Scan complete
              </Text>
            )}
            <Button
              color="red"
              disabled={selectedCount === 0 || deleting}
              onClick={() => confirmHandlers.open()}
            >
              Delete {selectedCount} selected
            </Button>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Main>
        {streamError && (
          <Notification color="red" mb="md" title="Stream error">
            {streamError}
          </Notification>
        )}

        {visibleGroups.length === 0 && !scanComplete && (
          <Stack align="center" mt="xl">
            <Loader />
            <Text c="dimmed">Looking for similar images...</Text>
          </Stack>
        )}

        {visibleGroups.length === 0 && scanComplete && (
          <Stack align="center" mt="xl">
            <Text c="dimmed">No similar image groups found.</Text>
          </Stack>
        )}

        <Stack gap="md">
          {visibleGroups.map((group) => (
            <Group key={group.id} gap="sm" wrap="wrap">
              {group.paths.map((path) => (
                <Stack key={path} gap={4} align="center">
                  <Image
                    src={imageUrl(path)}
                    w={THUMB_SIZE}
                    h={THUMB_SIZE}
                    fit="cover"
                    radius="sm"
                    onClick={() => openGroup(group)}
                    style={{ cursor: "pointer" }}
                  />
                  <Checkbox
                    checked={selected.has(path)}
                    onChange={() => toggleSelected(path)}
                    aria-label={`Select ${path}`}
                  />
                </Stack>
              ))}
            </Group>
          ))}
        </Stack>
      </AppShell.Main>

      <Modal
        opened={modalOpen}
        onClose={modalHandlers.close}
        title={activeGroup ? `Group ${activeGroup.id}` : ""}
        size="xl"
      >
        {activeGroup && (
          <Stack>
            <Group wrap="wrap" gap="md">
              {activeGroup.paths.map((path) => (
                <Stack key={path} gap={4} align="center">
                  <Image
                    src={imageUrl(path)}
                    h={MODAL_IMAGE_HEIGHT}
                    fit="contain"
                  />
                  <Checkbox
                    checked={selected.has(path)}
                    onChange={() => toggleSelected(path)}
                    label={path.split(/[\\/]/).pop()}
                  />
                </Stack>
              ))}
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal
        opened={confirmOpen}
        onClose={confirmHandlers.close}
        title="Confirm deletion"
        size="sm"
      >
        <Stack>
          <Text>
            Move {selectedCount} image(s) to the recycle bin? This cannot be undone
            from this UI.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={confirmHandlers.close} disabled={deleting}>
              Cancel
            </Button>
            <Button color="red" onClick={handleConfirmDelete} loading={deleting}>
              Delete
            </Button>
          </Group>
        </Stack>
      </Modal>
    </AppShell>
  );
}
