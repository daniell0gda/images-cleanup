import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act, cleanup } from "@testing-library/react";
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
