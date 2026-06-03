import { useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  ActionIcon,
  AppShell,
  Button,
  Checkbox,
  Group,
  Image,
  Loader,
  Modal,
  Notification,
  SimpleGrid,
  Slider,
  Stack,
  Text,
  Title,
  Tooltip,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";

interface SimilarityGroup {
  id: number;
  paths: string[];
  similarity: number;
}

interface TagGroup {
  name: string;
  destination: string;
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
  const [images, setImages] = useState<string[]>([]);
  const [mode, setMode] = useState<string>("SimilaritySearch");
  const [tagGroups, setTagGroups] = useState<TagGroup[]>([]);
  const [scanComplete, setScanComplete] = useState(false);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [progress, setProgress] = useState<ScanProgress | null>(null);
  const [comparing, setComparing] = useState<ComparingState | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [activeGroup, setActiveGroup] = useState<SimilarityGroup | null>(null);
  const [activeImageIndex, setActiveImageIndex] = useState<number>(-1);
  const [modalOpen, modalHandlers] = useDisclosure(false);
  const [confirmOpen, confirmHandlers] = useDisclosure(false);
  const [pickerOpen, pickerHandlers] = useDisclosure(false);
  const [deleting, setDeleting] = useState(false);
  const [similarityMin, setSimilarityMin] = useState<number | null>(null);
  const [sliderValue, setSliderValue] = useState<number>(0.0);

  useEffect(() => {
    fetch("/api/config")
      .then((r) => r.json())
      .then((cfg: { similarity_threshold: number; mode?: string; tag_groups?: TagGroup[] }) => {
        setSimilarityMin(cfg.similarity_threshold);
        setSliderValue(cfg.similarity_threshold);
        if (cfg.mode) setMode(cfg.mode);
        if (cfg.tag_groups) setTagGroups(cfg.tag_groups);
      })
      .catch(() => {
        setSimilarityMin(0.0);
        setSliderValue(0.0);
      });
  }, []);

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
    es.addEventListener("image", (evt: MessageEvent) => {
      const data: { path: string } = JSON.parse(evt.data);
      setImages((prev) => [...prev, data.path]);
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

  const openImageModal = (index: number) => {
    setActiveImageIndex(index);
    modalHandlers.open();
  };

  const selectedCount = selected.size;

  const visibleGroups = useMemo(
    () => groups.filter((g) => g.paths.length >= 2 && g.similarity >= sliderValue),
    [groups, sliderValue]
  );

  const selectVisiblePhotos = () => {
    if (mode === "GroupByTags") {
      setSelected(new Set(images));
    } else {
      const paths = visibleGroups.flatMap((g) => g.paths);
      setSelected(new Set(paths));
    }
  };

  const activeIndex = useMemo(
    () => (activeGroup ? visibleGroups.findIndex((g) => g.id === activeGroup.id) : -1),
    [activeGroup, visibleGroups]
  );

  const goNext = () => {
    if (mode === "GroupByTags") {
      if (activeImageIndex < images.length - 1) {
        setActiveImageIndex(activeImageIndex + 1);
      }
    } else {
      if (activeIndex < visibleGroups.length - 1) {
        setActiveGroup(visibleGroups[activeIndex + 1]);
      }
    }
  };

  const goPrev = () => {
    if (mode === "GroupByTags") {
      if (activeImageIndex > 0) {
        setActiveImageIndex(activeImageIndex - 1);
      }
    } else {
      if (activeIndex > 0) {
        setActiveGroup(visibleGroups[activeIndex - 1]);
      }
    }
  };

  const maxCols = useMemo(
    () => (visibleGroups.length === 0 ? 0 : Math.max(...visibleGroups.map((g) => g.paths.length))),
    [visibleGroups]
  );

  const toggleColumn = (n: number) => {
    const nthPaths = visibleGroups
      .filter((g) => g.paths.length >= n)
      .map((g) => g.paths[n - 1]);
    const allSelected = nthPaths.every((p) => selected.has(p));
    setSelected((prev) => {
      const next = new Set(prev);
      if (allSelected) {
        nthPaths.forEach((p) => next.delete(p));
      } else {
        nthPaths.forEach((p) => next.add(p));
      }
      return next;
    });
  };

  const scrollParentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: visibleGroups.length,
    getScrollElement: () => scrollParentRef.current,
    estimateSize: () => 130,
    overscan: 3,
  });

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
      if (mode === "GroupByTags") {
        setImages((prev) => prev.filter((p) => !trashed.has(p)));
      } else {
        setGroups((prev) =>
          prev
            .map((g) => ({ ...g, paths: g.paths.filter((p) => !trashed.has(p)) }))
            .filter((g) => g.paths.length >= 2)
        );
      }
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

  const moveToSorted = async (groupName: string) => {
    const paths = Array.from(selected);
    const response = await fetch("/api/move-to-group", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ paths, group_name: groupName }),
    });
    if (!response.ok) {
      notifications.show({ color: "red", title: "Move failed", message: `Server returned ${response.status}` });
      return;
    }
    const body: { results: { path: string; ok: boolean }[] } = await response.json();
    const moved = new Set(body.results.filter((r) => r.ok).map((r) => r.path));
    setImages((prev) => prev.filter((p) => !moved.has(p)));
    setSelected((prev) => {
      const next = new Set(prev);
      moved.forEach((p) => next.delete(p));
      return next;
    });
    pickerHandlers.close();
  };

  const handleMoveToSorted = () => {
    if (tagGroups.length === 1) {
      moveToSorted(tagGroups[0].name);
    } else {
      pickerHandlers.open();
    }
  };

  const isGroupByTags = mode === "GroupByTags";

  return (
    <AppShell
      header={{ height: 60 }}
      padding="md"
    >
      <AppShell.Header p="md">
        <Group justify="space-between" align="center">
          <Title order={3}>{isGroupByTags ? "Group By Tags" : "Similarity Search"}</Title>
          <Group>
            {!isGroupByTags && similarityMin !== null && (
              <Group gap="xs" align="center">
                <Text size="sm">Min similarity:</Text>
                <Slider
                  min={similarityMin}
                  max={1}
                  step={0.001}
                  value={sliderValue}
                  onChange={setSliderValue}
                  label={(v) => `${Math.round(v * 100)}%`}
                  w={200}
                />
                <Text size="sm">{Math.round(sliderValue * 100)}%</Text>
              </Group>
            )}
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
            <Button variant="default" onClick={selectVisiblePhotos}>
              Select Visible Photos
            </Button>
            <Button
              variant="default"
              data-testid="deselect-all"
              disabled={selectedCount === 0}
              onClick={() => setSelected(new Set())}
            >
              Deselect All
            </Button>
            <Button
              color="red"
              disabled={selectedCount === 0 || deleting}
              onClick={() => confirmHandlers.open()}
            >
              Delete {selectedCount} selected
            </Button>
            {isGroupByTags && (
              <Button
                disabled={selectedCount === 0}
                onClick={handleMoveToSorted}
              >
                Move to sorted
              </Button>
            )}
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Main>
        {streamError && (
          <Notification color="red" mb="md" title="Stream error">
            {streamError}
          </Notification>
        )}

        {isGroupByTags ? (
          <>
            {images.length === 0 && !scanComplete && (
              <Stack align="center" mt="xl">
                <Loader />
                <Text c="dimmed">Scanning for unclassified images...</Text>
              </Stack>
            )}
            {images.length === 0 && scanComplete && (
              <Stack align="center" mt="xl">
                <Text c="dimmed">No unclassified images found.</Text>
              </Stack>
            )}
            <SimpleGrid cols={6} spacing="sm">
              {images.map((path, idx) => (
                <Stack key={path} gap={4} align="center">
                  <Image
                    src={imageUrl(path)}
                    w={THUMB_SIZE}
                    h={THUMB_SIZE}
                    fit="cover"
                    radius="sm"
                    onClick={() => openImageModal(idx)}
                    style={{ cursor: "pointer" }}
                  />
                  <Checkbox
                    checked={selected.has(path)}
                    onChange={() => toggleSelected(path)}
                    aria-label={`Select ${path}`}
                  />
                </Stack>
              ))}
            </SimpleGrid>
          </>
        ) : (
          <>
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

            {maxCols > 0 && (
              <Group gap="xs" style={{ position: "sticky", top: 0, zIndex: 10, background: "var(--mantine-color-body)", padding: "4px 0" }}>
                {Array.from({ length: maxCols }, (_, i) => i + 1).map((n) => (
                  <Button key={n} variant="default" size="xs" data-testid={`col-btn-${n}`} onClick={() => toggleColumn(n)}>
                    {n}
                  </Button>
                ))}
              </Group>
            )}

            <div
              ref={scrollParentRef}
              data-virtual-scroll="true"
              style={{ height: "calc(100vh - 60px - var(--mantine-spacing-md) * 2)", overflowY: "auto" }}
            >
              <div style={{ height: rowVirtualizer.getTotalSize(), position: "relative" }}>
                {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                  const group = visibleGroups[virtualRow.index];
                  return (
                    <div
                      key={group.id}
                      data-index={virtualRow.index}
                      ref={rowVirtualizer.measureElement}
                      style={{ position: "absolute", top: virtualRow.start, width: "100%" }}
                    >
                      <Group gap="sm" wrap="wrap" pb="md">
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
                    </div>
                  );
                })}
              </div>
            </div>
          </>
        )}
      </AppShell.Main>

      {/* Detail modal: GroupByTags mode shows single image; SimilaritySearch shows group */}
      <Modal
        opened={modalOpen}
        onClose={modalHandlers.close}
        title={isGroupByTags
          ? (activeImageIndex >= 0 && activeImageIndex < images.length ? `Image ${activeImageIndex + 1}` : "")
          : (activeGroup ? `Group ${activeGroup.id}` : "")}
        size="xl"
      >
        {isGroupByTags && activeImageIndex >= 0 && activeImageIndex < images.length ? (
          <Stack>
            <Group justify="space-between">
              <ActionIcon
                data-testid="modal-prev"
                variant="default"
                onClick={goPrev}
                disabled={activeImageIndex <= 0}
              >
                ‹
              </ActionIcon>
              <ActionIcon
                data-testid="modal-next"
                variant="default"
                onClick={goNext}
                disabled={activeImageIndex >= images.length - 1}
              >
                ›
              </ActionIcon>
            </Group>
            <Stack align="center" gap={4}>
              <Image
                src={imageUrl(images[activeImageIndex])}
                h={MODAL_IMAGE_HEIGHT}
                fit="contain"
              />
              <Checkbox
                checked={selected.has(images[activeImageIndex])}
                onChange={() => toggleSelected(images[activeImageIndex])}
                aria-label={`Select ${images[activeImageIndex]}`}
              />
            </Stack>
          </Stack>
        ) : !isGroupByTags && activeGroup ? (
          <Stack>
            <Group justify="space-between">
              <ActionIcon
                data-testid="modal-prev"
                variant="default"
                onClick={goPrev}
                disabled={activeIndex <= 0}
              >
                ‹
              </ActionIcon>
              <ActionIcon
                data-testid="modal-next"
                variant="default"
                onClick={goNext}
                disabled={activeIndex === -1 || activeIndex >= visibleGroups.length - 1}
              >
                ›
              </ActionIcon>
            </Group>
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
                    label={(() => {
                      const parts = path.split(/[\\/]/);
                      const filename = parts[parts.length - 1];
                      const folder = parts[parts.length - 2] ?? "";
                      return folder ? `${folder}/${filename}` : filename;
                    })()}
                  />
                </Stack>
              ))}
            </Group>
          </Stack>
        ) : null}
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

      {/* Group picker modal for move-to-sorted with multiple tag groups */}
      <Modal
        opened={pickerOpen}
        onClose={pickerHandlers.close}
        title="Choose destination group"
        size="sm"
      >
        <Stack>
          {tagGroups.map((g) => (
            <Tooltip key={g.name} label={g.destination}>
              <Button variant="default" onClick={() => moveToSorted(g.name)}>
                {g.name}
              </Button>
            </Tooltip>
          ))}
        </Stack>
      </Modal>
    </AppShell>
  );
}
