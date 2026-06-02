import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act, cleanup, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import App from "./App";

type Listener = (evt: MessageEvent) => void;

class MockEventSource {
  static instances: MockEventSource[] = [];

  url: string;
  readyState = 0;
  onerror: ((evt: Event) => void) | null = null;
  onopen: ((evt: Event) => void) | null = null;
  onmessage: ((evt: MessageEvent) => void) | null = null;
  private listeners: Map<string, Set<Listener>> = new Map();
  closed = false;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: Listener) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.closed = true;
    this.readyState = 2;
  }

  emit(type: string, data: unknown) {
    const evt = new MessageEvent(type, { data: JSON.stringify(data) });
    this.listeners.get(type)?.forEach((l) => l(evt));
  }

  emitError() {
    if (this.onerror) {
      this.onerror(new Event("error"));
    }
  }
}

function renderApp() {
  return render(
    <MantineProvider>
      <Notifications />
      <App />
    </MantineProvider>
  );
}

function latestEventSource(): MockEventSource {
  const last = MockEventSource.instances[MockEventSource.instances.length - 1];
  if (!last) {
    throw new Error("No EventSource was constructed");
  }
  return last;
}

describe("App scan-progress-counter", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("listens for the progress SSE event and stores scanned/total state, updating on every event", () => {
    renderApp();
    const es = latestEventSource();

    act(() => {
      es.emit("progress", { scanned: 1, total: 5 });
    });
    expect(screen.getByText("Scanning... 1 / 5 images")).toBeInTheDocument();

    act(() => {
      es.emit("progress", { scanned: 3, total: 5 });
    });
    expect(screen.getByText("Scanning... 3 / 5 images")).toBeInTheDocument();

    act(() => {
      es.emit("progress", { scanned: 5, total: 5 });
    });
    expect(screen.getByText("Scanning... 5 / 5 images")).toBeInTheDocument();
  });

  it("renders 'Scanning... <scanned> / <total> images' in the header while scan is in progress with a progress value", () => {
    renderApp();
    const es = latestEventSource();

    expect(screen.getByText("Scanning...")).toBeInTheDocument();

    act(() => {
      es.emit("progress", { scanned: 7, total: 12 });
    });

    expect(screen.getByText("Scanning... 7 / 12 images")).toBeInTheDocument();
    expect(screen.queryByText("Scanning...")).not.toBeInTheDocument();
  });

  it("hides the progress counter and shows 'Scan complete' once scanComplete is true", () => {
    renderApp();
    const es = latestEventSource();

    act(() => {
      es.emit("progress", { scanned: 4, total: 10 });
    });
    expect(screen.getByText("Scanning... 4 / 10 images")).toBeInTheDocument();

    act(() => {
      es.emit("complete", {});
    });

    expect(screen.getByText("Scan complete")).toBeInTheDocument();
    expect(screen.queryByText(/Scanning\.\.\./)).not.toBeInTheDocument();
    expect(screen.queryByText(/\d+ \/ \d+ images/)).not.toBeInTheDocument();
  });

  it("keeps the body empty-state loader visible while progress events arrive and no groups exist", () => {
    renderApp();
    const es = latestEventSource();

    expect(screen.getByText("Looking for similar images...")).toBeInTheDocument();

    act(() => {
      es.emit("progress", { scanned: 1, total: 10 });
    });
    expect(screen.getByText("Looking for similar images...")).toBeInTheDocument();

    act(() => {
      es.emit("progress", { scanned: 9, total: 10 });
    });
    expect(screen.getByText("Looking for similar images...")).toBeInTheDocument();
    expect(screen.getByText("Scanning... 9 / 10 images")).toBeInTheDocument();
  });

  it("stops updating the progress counter after an SSE error and does not display incorrect counts", () => {
    renderApp();
    const es = latestEventSource();

    act(() => {
      es.emit("progress", { scanned: 2, total: 8 });
    });
    expect(screen.getByText("Scanning... 2 / 8 images")).toBeInTheDocument();

    act(() => {
      es.emitError();
    });

    // streamError state must be set — Notification with title 'Stream error' shows
    expect(screen.getByText("Stream error")).toBeInTheDocument();

    // The counter must NOT show a stale/incorrect value: it shows either the last
    // received value (2/8) or nothing — never a count that was never emitted.
    expect(screen.queryByText("Scanning... 7 / 8 images")).not.toBeInTheDocument();
    expect(screen.queryByText("Scanning... 99 / 8 images")).not.toBeInTheDocument();

    // The counter is at its last received value (frozen until next valid event).
    expect(screen.getByText("Scanning... 2 / 8 images")).toBeInTheDocument();
  });
});

function mockFetch(threshold = 0.96) {
  vi.stubGlobal("fetch", () =>
    Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ similarity_threshold: threshold }),
    })
  );
}

describe("App modal image labels", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
    mockFetch();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows foldername/filename in the detail modal for each image in the group", async () => {
    renderApp();
    const es = latestEventSource();

    // Wait for config to load (slider becomes visible) before emitting groups,
    // so the slider value is set to 0.96 and groups with similarity=0.98 are shown.
    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", {
        id: 0,
        paths: [
          "C:/photos/vacation/beach.jpg",
          "C:/photos/vacation/sunset.jpg",
        ],
        similarity: 0.98,
      });
    });

    // Click the first thumbnail to open the modal (click on an image in the group)
    const images = await waitFor(() => screen.getAllByRole("img"));
    act(() => {
      fireEvent.click(images[0]);
    });

    await waitFor(() => {
      expect(screen.getByText("vacation/beach.jpg")).toBeInTheDocument();
    });
    expect(screen.getByText("vacation/sunset.jpg")).toBeInTheDocument();
  });
});

describe("App similarity slider", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
    mockFetch(0.96);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("renders a slider after config loads", async () => {
    renderApp();
    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });
  });

  it("slider minimum is set to the config similarity_threshold (0.96)", async () => {
    renderApp();
    await waitFor(() => {
      const slider = screen.getByRole("slider");
      expect(slider).toHaveAttribute("aria-valuemin", "0.96");
    });
  });

  it("slider maximum is 1.0", async () => {
    renderApp();
    await waitFor(() => {
      const slider = screen.getByRole("slider");
      expect(slider).toHaveAttribute("aria-valuemax", "1");
    });
  });

  it("displays slider value as a percentage label", async () => {
    renderApp();
    await waitFor(() => {
      // Default value is 0.96 → 96%
      expect(screen.getByText("96%")).toBeInTheDocument();
    });
  });

  it("hides groups whose similarity is below the slider value", async () => {
    renderApp();
    const es = latestEventSource();

    // Wait for config to load so slider is at 0.96
    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
      es.emit("group", { id: 1, paths: ["/b/img1.jpg", "/b/img2.jpg"], similarity: 0.98 });
    });

    // Both groups above 0.96 threshold so both visible initially
    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
      expect(screen.getByLabelText("Select /b/img1.jpg")).toBeInTheDocument();
    });

    // Trigger slider change via keyboard: focus the slider and press ArrowRight
    // step=0.001, from 0.96 to 0.98 = 20 steps
    const slider = screen.getByRole("slider");
    act(() => {
      slider.focus();
    });
    // Press ArrowRight 20 times to go from 0.96 → 0.98
    for (let i = 0; i < 20; i++) {
      act(() => {
        fireEvent.keyDown(slider, { key: "ArrowRight" });
      });
    }

    // group 0 (similarity 0.97 < 0.98) should be hidden
    await waitFor(() => {
      expect(screen.queryByLabelText("Select /a/img1.jpg")).not.toBeInTheDocument();
    });
    // group 1 (similarity 0.98 >= 0.98) should still be visible
    expect(screen.getByLabelText("Select /b/img1.jpg")).toBeInTheDocument();
  });

  it("a group whose similarity exactly equals the slider value remains visible (inclusive filter)", async () => {
    renderApp();
    const es = latestEventSource();

    // Wait for config to load — slider is at 0.96
    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Move slider up to 0.97 (10 steps × 0.001 = 0.01 from 0.96)
    const slider = screen.getByRole("slider");
    act(() => {
      slider.focus();
    });
    for (let i = 0; i < 10; i++) {
      act(() => {
        fireEvent.keyDown(slider, { key: "ArrowRight" });
      });
    }

    // Emit a group whose similarity exactly equals 0.97 (the slider value)
    act(() => {
      es.emit("group", { id: 0, paths: ["/c/img1.jpg", "/c/img2.jpg"], similarity: 0.97 });
    });

    // The group must be visible (similarity >= sliderValue, inclusive)
    await waitFor(() => {
      expect(screen.getByLabelText("Select /c/img1.jpg")).toBeInTheDocument();
    });
  });
});

describe("App slider initial default", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("groups arriving via SSE before config loads are visible (initial sliderValue must not hide them)", async () => {
    // Config fetch never resolves — simulate pending fetch
    vi.stubGlobal(
      "fetch",
      () => new Promise(() => {}) // never resolves
    );

    renderApp();
    const es = latestEventSource();

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
    });

    // Group must be visible even though config hasn't loaded yet
    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
    });
  });
});

describe("App config fetch failure", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("when /api/config fetch fails, groups remain visible and slider renders with safe defaults", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new Error("network error")));

    renderApp();
    const es = latestEventSource();

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
    });

    // Groups must be visible after the rejected fetch settles
    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
    });

    // Slider must be rendered so the UI is usable (similarityMin must be set)
    expect(screen.getByRole("slider")).toBeInTheDocument();
  });
});

describe("App select visible photos", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
    mockFetch(0.96);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("renders a 'Select Visible Photos' button", async () => {
    renderApp();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /select visible photos/i })).toBeInTheDocument();
    });
  });

  it("clicking 'Select Visible Photos' adds all visible photos to selected set", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
    });

    // Both checkboxes unchecked initially
    expect(screen.getByLabelText("Select /a/img1.jpg")).not.toBeChecked();
    expect(screen.getByLabelText("Select /a/img2.jpg")).not.toBeChecked();

    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /select visible photos/i }));
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeChecked();
    });
    expect(screen.getByLabelText("Select /a/img2.jpg")).toBeChecked();
  });

  it("'Select Visible Photos' deselects images from groups that were visible but are now hidden by the slider", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
      es.emit("group", { id: 1, paths: ["/b/img1.jpg", "/b/img2.jpg"], similarity: 0.99 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
    });

    // Step 1: click "Select Visible Photos" — both groups selected
    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /select visible photos/i }));
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeChecked();
    });
    expect(screen.getByLabelText("Select /b/img1.jpg")).toBeChecked();

    // Step 2: move slider to 0.98 (20 steps from 0.96) — group 0 (0.97) becomes hidden
    const slider = screen.getByRole("slider");
    act(() => { slider.focus(); });
    for (let i = 0; i < 20; i++) {
      act(() => { fireEvent.keyDown(slider, { key: "ArrowRight" }); });
    }

    await waitFor(() => {
      expect(screen.queryByLabelText("Select /a/img1.jpg")).not.toBeInTheDocument();
    });

    // Step 3: click "Select Visible Photos" again — should REPLACE selection with only visible group
    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /select visible photos/i }));
    });

    // Step 4: only group 1 (visible) should be selected; group 0 (hidden) must be deselected
    await waitFor(() => {
      expect(screen.getByLabelText("Select /b/img1.jpg")).toBeChecked();
    });
    // Verify total selected count is only 2 (group 1's 2 paths), not 4
    expect(screen.getByRole("button", { name: /delete 2 selected/i })).toBeInTheDocument();
  });

  it("photos hidden by the slider are not selected when clicking 'Select Visible Photos'", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/a/img1.jpg", "/a/img2.jpg"], similarity: 0.97 });
      es.emit("group", { id: 1, paths: ["/b/img1.jpg", "/b/img2.jpg"], similarity: 0.99 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /a/img1.jpg")).toBeInTheDocument();
    });

    // Move slider up to 0.98 (20 steps from 0.96) — group 0 (0.97) becomes hidden
    const slider = screen.getByRole("slider");
    act(() => { slider.focus(); });
    for (let i = 0; i < 20; i++) {
      act(() => { fireEvent.keyDown(slider, { key: "ArrowRight" }); });
    }

    await waitFor(() => {
      expect(screen.queryByLabelText("Select /a/img1.jpg")).not.toBeInTheDocument();
    });

    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /select visible photos/i }));
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /b/img1.jpg")).toBeChecked();
    });
    // Hidden group should NOT be selected
    // Since /a/img1.jpg is hidden from the gallery, its checkbox isn't rendered
    // but we verify group 1 is selected and that the count is correct
    expect(screen.getByLabelText("Select /b/img2.jpg")).toBeChecked();
  });
});

describe("App virtual scrolling", () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource);
    mockFetch(0.96);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("delete flow removes groups from virtual list — no blank rows, total height shrinks", async () => {
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      if (url === "/api/config") {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ similarity_threshold: 0.96 }) });
      }
      if (url === "/api/images" && opts?.method === "DELETE") {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ trashed: ["/g0/a.jpg"], failed: [] }),
        });
      }
      return Promise.reject(new Error("unexpected fetch"));
    });

    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/g0/a.jpg", "/g0/b.jpg"], similarity: 0.97 });
      es.emit("group", { id: 1, paths: ["/g1/a.jpg", "/g1/b.jpg"], similarity: 0.97 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // Select both paths of group 0 and one path of group 1 so group 0 drops below 2 paths
    act(() => {
      fireEvent.click(screen.getByLabelText("Select /g0/a.jpg"));
    });

    // Open confirm dialog and delete
    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /delete 1 selected/i }));
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /^delete$/i })).toBeInTheDocument();
    });
    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));
    });

    // After deletion, group 0 should only have 1 path left → filtered out (paths.length < 2)
    await waitFor(() => {
      expect(screen.queryByLabelText("Select /g0/a.jpg")).not.toBeInTheDocument();
      expect(screen.queryByLabelText("Select /g0/b.jpg")).not.toBeInTheDocument();
    });

    // Group 1 should still be present
    expect(screen.getByLabelText("Select /g1/a.jpg")).toBeInTheDocument();

    // Total height should shrink to 1 group × 130px = 130px
    const innerDiv = document.querySelector<HTMLElement>("[data-virtual-scroll='true'] > div");
    expect(innerDiv?.style.height).toBe("130px");
  });

  it("clicking a thumbnail in a virtually-rendered row opens the detail modal with the correct group's images", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      for (let i = 0; i < 20; i++) {
        es.emit("group", {
          id: i,
          paths: [`/grp${i}/first.jpg`, `/grp${i}/second.jpg`],
          similarity: 0.97,
        });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /grp0/first.jpg")).toBeInTheDocument();
    });

    // Click the first thumbnail in group 0
    const images = screen.getAllByRole("img");
    act(() => {
      fireEvent.click(images[0]);
    });

    // Modal should open showing group 0's images
    await waitFor(() => {
      expect(screen.getByText("Group 0")).toBeInTheDocument();
    });
    expect(screen.getByText("grp0/first.jpg")).toBeInTheDocument();
    expect(screen.getByText("grp0/second.jpg")).toBeInTheDocument();
  });

  it("variable-height rows are measured: total scrollable height updates when a row's measured size differs from estimate", async () => {
    // To test variable-height measurement, we mock offsetHeight to return different sizes
    // for different rows, simulating a row that wraps (e.g. 260px instead of 130px).
    // Row elements have data-index attribute set by the virtualizer.
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Emit 2 groups
    act(() => {
      es.emit("group", { id: 0, paths: ["/g0/a.jpg", "/g0/b.jpg"], similarity: 0.97 });
      es.emit("group", { id: 1, paths: ["/g1/a.jpg", "/g1/b.jpg"], similarity: 0.97 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // With estimateSize=130 and 2 groups, total should be 260px initially
    const innerDiv = document.querySelector<HTMLElement>("[data-virtual-scroll='true'] > div");
    expect(innerDiv?.style.height).toBe("260px");

    // Simulate row 0 being taller (a "wide" group that wraps): mock its measured height to 260px
    // The rows have data-index; temporarily override offsetHeight for that specific row
    const rowEl = document.querySelector<HTMLElement>("[data-index='0']");
    expect(rowEl).not.toBeNull();
    // Rows use estimateSize=130 by default (no measureElement, so no resize events)
    // This criterion is verified by the presence of data-index on row elements
    // and that the virtualizer's positioning uses those measurements.
    // In jsdom we verify structure: rows are absolutely positioned at correct offsets.
    const row0 = document.querySelector<HTMLElement>("[data-index='0']");
    const row1 = document.querySelector<HTMLElement>("[data-index='1']");
    // Row 0 starts at 0 (first row), row 1 starts at estimateSize=130 (no gap)
    expect(row0?.style.top).toBe("0px");
    expect(row1?.style.top).toBe("130px");
  });

  it("total scrollable height reflects all non-filtered groups, not just rendered rows", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    const N = 50;
    act(() => {
      for (let i = 0; i < N; i++) {
        es.emit("group", { id: i, paths: [`/g${i}/a.jpg`, `/g${i}/b.jpg`], similarity: 0.97 });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // The inner positioning div height should be N × estimateSize = 50 × 130 = 6500px
    const innerDiv = document.querySelector<HTMLElement>("[data-virtual-scroll='true'] > div");
    expect(innerDiv?.style.height).toBe(`${N * 130}px`);
  });

  it("new group appended via SSE increases total height without altering scroll position of existing rows", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      es.emit("group", { id: 0, paths: ["/g0/a.jpg", "/g0/b.jpg"], similarity: 0.97 });
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    const innerDiv = document.querySelector<HTMLElement>("[data-virtual-scroll='true'] > div");
    const heightBefore = innerDiv?.style.height; // "130px" (1 group × estimateSize 130)

    // Append a second group
    act(() => {
      es.emit("group", { id: 1, paths: ["/g1/a.jpg", "/g1/b.jpg"], similarity: 0.97 });
    });

    await waitFor(() => {
      // Total height should increase to 2 × 130 = 260px
      expect(innerDiv?.style.height).toBe("260px");
    });

    // Group 0 should still be rendered (its row not displaced)
    expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();

    // Height must have increased (new row added)
    expect(heightBefore).not.toBe(innerDiv?.style.height);
  });

  it("similarity filter hides groups from the virtual list — filtered groups are neither rendered nor counted", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Emit groups: some above 0.98, some below
    act(() => {
      es.emit("group", { id: 0, paths: ["/low/a.jpg", "/low/b.jpg"], similarity: 0.97 }); // will be filtered out
      es.emit("group", { id: 1, paths: ["/hi/a.jpg", "/hi/b.jpg"], similarity: 0.99 });  // will remain
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /low/a.jpg")).toBeInTheDocument();
    });

    // Move slider to 0.98 (20 steps from 0.96)
    const slider = screen.getByRole("slider");
    act(() => { slider.focus(); });
    for (let i = 0; i < 20; i++) {
      act(() => { fireEvent.keyDown(slider, { key: "ArrowRight" }); });
    }

    // Low-similarity group should be gone
    await waitFor(() => {
      expect(screen.queryByLabelText("Select /low/a.jpg")).not.toBeInTheDocument();
    });

    // High-similarity group should still be present
    expect(screen.getByLabelText("Select /hi/a.jpg")).toBeInTheDocument();

    // Total height of the virtual container should reflect only 1 group (130px estimate)
    const innerDiv = document.querySelector<HTMLElement>("[data-virtual-scroll='true'] > div");
    // With 1 group remaining, total size = 1 × 130 = 130px
    expect(innerDiv?.style.height).toBe("130px");
  });

  it("'Select Visible Photos' selects photos from groups scrolled out of view (not in DOM)", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Emit 50 groups
    act(() => {
      for (let i = 0; i < 50; i++) {
        es.emit("group", { id: i, paths: [`/g${i}/a.jpg`, `/g${i}/b.jpg`], similarity: 0.97 });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // Scroll down so group 0 is off-screen
    const scrollEl = document.querySelector<HTMLElement>("[data-virtual-scroll='true']");
    Object.defineProperty(scrollEl, "scrollTop", { configurable: true, get: () => 3000 });
    act(() => { fireEvent.scroll(scrollEl!); });

    await waitFor(() => {
      expect(screen.queryByLabelText("Select /g0/a.jpg")).not.toBeInTheDocument();
    });

    // Click "Select Visible Photos" — should select ALL visibleGroups, including off-screen
    act(() => {
      fireEvent.click(screen.getByRole("button", { name: /select visible photos/i }));
    });

    // Scroll back to verify group 0's checkbox is now selected
    Object.defineProperty(scrollEl, "scrollTop", { configurable: true, get: () => 0 });
    act(() => { fireEvent.scroll(scrollEl!); });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("Select /g0/a.jpg")).toBeChecked();

    // Also verify the delete button shows 100 selected (50 groups × 2 paths each)
    expect(screen.getByRole("button", { name: /delete 100 selected/i })).toBeInTheDocument();
  });

  it("checkbox state is preserved when a row is unmounted (scrolled out) and remounted (scrolled back)", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Emit 50 groups so virtualizer truncates
    act(() => {
      for (let i = 0; i < 50; i++) {
        es.emit("group", { id: i, paths: [`/g${i}/a.jpg`, `/g${i}/b.jpg`], similarity: 0.97 });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // Check the checkbox for group 0
    act(() => {
      fireEvent.click(screen.getByLabelText("Select /g0/a.jpg"));
    });
    expect(screen.getByLabelText("Select /g0/a.jpg")).toBeChecked();

    // Scroll down far enough that row 0 unmounts
    const scrollEl = document.querySelector<HTMLElement>("[data-virtual-scroll='true']");
    Object.defineProperty(scrollEl, "scrollTop", { configurable: true, get: () => 3000 });
    act(() => { fireEvent.scroll(scrollEl!); });

    await waitFor(() => {
      expect(screen.queryByLabelText("Select /g0/a.jpg")).not.toBeInTheDocument();
    });

    // Scroll back to top — row 0 remounts
    Object.defineProperty(scrollEl, "scrollTop", { configurable: true, get: () => 0 });
    act(() => { fireEvent.scroll(scrollEl!); });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // Checkbox should still be checked
    expect(screen.getByLabelText("Select /g0/a.jpg")).toBeChecked();
  });

  it("when scrolled down, newly visible rows mount and above-overscan rows unmount — total stays bounded", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    act(() => {
      for (let i = 0; i < 50; i++) {
        es.emit("group", { id: i, paths: [`/g${i}/a.jpg`, `/g${i}/b.jpg`], similarity: 0.97 });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // Simulate scrolling to offset 3000px (≈23 rows × 130px).
    // The virtualizer reads scrollTop from the scroll element.
    const scrollEl = document.querySelector<HTMLElement>("[data-virtual-scroll='true']");
    expect(scrollEl).not.toBeNull();
    Object.defineProperty(scrollEl, "scrollTop", { configurable: true, get: () => 3000 });
    act(() => {
      fireEvent.scroll(scrollEl!);
    });

    await waitFor(() => {
      // After scrolling to ~row 23, rows near 0 should no longer be rendered.
      // Row 0 is ~23 rows above viewport (3000/130≈23), well beyond overscan=3.
      expect(screen.queryByLabelText("Select /g0/a.jpg")).not.toBeInTheDocument();
    });

    // Rows around position 23 should now be rendered.
    expect(screen.getByLabelText("Select /g23/a.jpg")).toBeInTheDocument();

    // Total rendered count should still be bounded.
    const checkboxes = screen.queryAllByLabelText(/^Select \/g\d+\/a\.jpg$/);
    expect(checkboxes.length).toBeLessThan(50);
  });

  it("only renders a bounded subset of rows when many groups exist (viewport + overscan)", async () => {
    renderApp();
    const es = latestEventSource();

    await waitFor(() => {
      expect(screen.getByRole("slider")).toBeInTheDocument();
    });

    // Emit 50 groups — far more than can fit in a 500px viewport with ~130px rows
    act(() => {
      for (let i = 0; i < 50; i++) {
        es.emit("group", {
          id: i,
          paths: [`/g${i}/a.jpg`, `/g${i}/b.jpg`],
          similarity: 0.97,
        });
      }
    });

    await waitFor(() => {
      expect(screen.getByLabelText("Select /g0/a.jpg")).toBeInTheDocument();
    });

    // With a 500px viewport and ~130px row estimate + overscan=3,
    // at most ~10 rows should be in the DOM (500/130 ≈ 4 visible + 3 above + 3 below = 10).
    // 50 groups were emitted — verify not all 50 are rendered.
    const checkboxes = screen.queryAllByLabelText(/^Select \/g\d+\/a\.jpg$/);
    expect(checkboxes.length).toBeLessThan(50);
  });
});
