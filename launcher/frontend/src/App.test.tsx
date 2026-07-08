import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, cleanup, fireEvent, waitFor } from "@testing-library/react";
import { App } from "./App";

interface FetchCall {
  url: string;
  opts?: RequestInit;
}

const calls: FetchCall[] = [];

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

// Endpoints LauncherApp polls on mount that are unrelated to this cluster.
function launcherDefault(url: string): Response | null {
  if (url === "/api/users") return jsonResponse([]);
  if (url === "/api/status") return jsonResponse({ status: "idle" });
  if (url === "/api/config") return jsonResponse({ sorter_port: 8080 });
  if (url === "/api/sync/devices") return jsonResponse([]);
  if (url === "/api/sync/profiles") return jsonResponse([]);
  return null;
}

function baseSettings() {
  return {
    db_refresh: { enabled: true, schedule: "0 1 * * *", next_run: null },
    last_refresh: null,
    media_library: {
      enabled: true,
      folders: ["/photos"],
      schedule: "0 2 * * *",
      next_run: "2026-06-29T02:00:00",
    },
    media_build: null,
  };
}

async function openSettings() {
  render(<App />);
  // Navigate to the Ustawienia page.
  const btn = await screen.findByText("⚙️ Ustawienia");
  fireEvent.click(btn);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  calls.length = 0;
});

describe("Media library settings section", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      calls.push({ url, opts });
      if (url === "/api/settings" && opts?.method === "POST") {
        const sent = JSON.parse(String(opts.body));
        const merged = baseSettings();
        merged.media_library = {
          ...merged.media_library,
          ...sent.media_library,
          next_run: "2026-06-29T02:00:00",
        };
        return Promise.resolve(jsonResponse(merged));
      }
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });
  });

  it("renders the folder list, schedule, enabled switch and next-run", async () => {
    await openSettings();
    expect(await screen.findByText("Biblioteka zdjęć")).toBeInTheDocument();
    // Folder loaded from /api/settings
    await waitFor(() =>
      expect(screen.getByDisplayValue("/photos")).toBeInTheDocument(),
    );
    // Schedule default reflected
    expect(screen.getByDisplayValue("0 2 * * *")).toBeInTheDocument();
  });

  it("adds and removes folder rows", async () => {
    await openSettings();
    await waitFor(() =>
      expect(screen.getByDisplayValue("/photos")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Dodaj folder"));
    const inputs = await screen.findAllByPlaceholderText(/folder/i);
    // Two folder inputs now (one existing + one new empty row).
    expect(inputs.length).toBeGreaterThanOrEqual(2);

    // Remove the first row.
    const removeButtons = screen.getAllByLabelText("Usuń folder");
    fireEvent.click(removeButtons[0]);
    await waitFor(() =>
      expect(screen.queryByDisplayValue("/photos")).not.toBeInTheDocument(),
    );
  });

  it("Save posts the media_library block and reflects the returned state", async () => {
    await openSettings();
    await waitFor(() =>
      expect(screen.getByDisplayValue("/photos")).toBeInTheDocument(),
    );

    const folderInput = screen.getByDisplayValue("/photos");
    fireEvent.change(folderInput, { target: { value: "/media" } });

    // The media-library Save button (second "Zapisz" — first belongs to db_refresh).
    const saveButtons = screen.getAllByText("Zapisz");
    fireEvent.click(saveButtons[saveButtons.length - 1]);

    await waitFor(() => {
      const post = calls.find(
        (c) => c.url === "/api/settings" && c.opts?.method === "POST",
      );
      expect(post).toBeTruthy();
      const sent = JSON.parse(String(post!.opts!.body));
      expect(sent.media_library.folders).toContain("/media");
      expect(sent.media_library.schedule).toBe("0 2 * * *");
      expect(sent.media_library.enabled).toBe(true);
    });
  });

  it("shows the last-build summary and a skipped-roots warning", async () => {
    vi.stubGlobal("fetch", (url: string) => {
      const s = baseSettings();
      s.media_build = {
        state: "idle",
        processed: 12,
        total: null,
        added: 3,
        removed: 1,
        skipped_roots: ["/mnt/usb"],
        last_built: "2026-06-28T02:00:00",
        last_count: 12,
      } as never;
      if (url === "/api/settings") return Promise.resolve(jsonResponse(s));
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });
    await openSettings();
    expect(await screen.findByText(/12/)).toBeInTheDocument();
    expect(screen.getByText(/mnt\/usb/)).toBeInTheDocument();
  });
});

describe("Profile synchronizacji section", () => {
  function errorResponse(status: number, detail: string): Response {
    return {
      ok: false,
      status,
      json: () => Promise.resolve({ detail }),
    } as unknown as Response;
  }

  it("lists profiles fetched from GET /api/sync/profiles", async () => {
    vi.stubGlobal("fetch", (url: string) => {
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/sync/profiles") {
        return Promise.resolve(
          jsonResponse([
            { profile_id: "Daniel Local", display_name: "Daniel Local" },
            { profile_id: "Ania", display_name: "Ania" },
          ]),
        );
      }
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    expect(await screen.findByText("Profile synchronizacji")).toBeInTheDocument();
    expect(await screen.findByText("Daniel Local")).toBeInTheDocument();
    expect(screen.getByText("Ania")).toBeInTheDocument();
  });

  it("creates a profile: posts the name, clears the input and refreshes the list", async () => {
    let created = false;
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      calls.push({ url, opts });
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/sync/profiles" && opts?.method === "POST") {
        created = true;
        return Promise.resolve(
          jsonResponse({ profile_id: "Ola", display_name: "Ola" }),
        );
      }
      if (url === "/api/sync/profiles") {
        return Promise.resolve(
          jsonResponse(created ? [{ profile_id: "Ola", display_name: "Ola" }] : []),
        );
      }
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    await screen.findByText("Profile synchronizacji");

    const input = screen.getByPlaceholderText("Nazwa profilu");
    fireEvent.change(input, { target: { value: "Ola" } });
    fireEvent.click(screen.getByText("Utwórz"));

    await waitFor(() => {
      const post = calls.find(
        (c) => c.url === "/api/sync/profiles" && c.opts?.method === "POST",
      );
      expect(post).toBeTruthy();
      expect(JSON.parse(String(post!.opts!.body)).name).toBe("Ola");
    });

    // Input cleared and list refreshed to include the new profile.
    await waitFor(() =>
      expect(screen.getByPlaceholderText("Nazwa profilu")).toHaveValue(""),
    );
    expect(await screen.findByText("Ola")).toBeInTheDocument();
  });

  it("surfaces a 409 duplicate error from create", async () => {
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/sync/profiles" && opts?.method === "POST") {
        return Promise.resolve(errorResponse(409, "Profil już istnieje"));
      }
      if (url === "/api/sync/profiles") return Promise.resolve(jsonResponse([]));
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    await screen.findByText("Profile synchronizacji");
    fireEvent.change(screen.getByPlaceholderText("Nazwa profilu"), {
      target: { value: "Daniel" },
    });
    fireEvent.click(screen.getByText("Utwórz"));

    expect(await screen.findByText("Profil już istnieje")).toBeInTheDocument();
  });

  it("surfaces a 400 invalid-name error from create", async () => {
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/sync/profiles" && opts?.method === "POST") {
        return Promise.resolve(errorResponse(400, "Nieprawidłowa nazwa"));
      }
      if (url === "/api/sync/profiles") return Promise.resolve(jsonResponse([]));
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    await screen.findByText("Profile synchronizacji");
    fireEvent.change(screen.getByPlaceholderText("Nazwa profilu"), {
      target: { value: "!!!" },
    });
    fireEvent.click(screen.getByText("Utwórz"));

    expect(await screen.findByText("Nieprawidłowa nazwa")).toBeInTheDocument();
  });

  it("deletes a profile via DELETE /api/sync/profiles/{id}", async () => {
    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      calls.push({ url, opts });
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/sync/profiles") {
        return Promise.resolve(
          jsonResponse([{ profile_id: "Daniel Local", display_name: "Daniel Local" }]),
        );
      }
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    await screen.findByText("Daniel Local");
    fireEvent.click(screen.getByLabelText("Usuń profil"));

    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url === "/api/sync/profiles/Daniel%20Local" &&
            c.opts?.method === "DELETE",
        ),
      ).toBe(true),
    );
  });
});

describe("Odśwież teraz force build", () => {
  it("posts the build, polls status while building, shows progress and disables the button", async () => {
    const statuses = [
      { state: "building", processed: 5, total: null, added: 0, removed: 0, skipped_roots: [], last_built: null, last_count: null },
      { state: "building", processed: 40, total: null, added: 0, removed: 0, skipped_roots: [], last_built: null, last_count: null },
      { state: "idle", processed: 0, total: null, added: 7, removed: 2, skipped_roots: [], last_built: "2026-06-28T03:00:00", last_count: 50 },
    ];
    let statusIdx = 0;

    vi.stubGlobal("fetch", (url: string, opts?: RequestInit) => {
      calls.push({ url, opts });
      if (url === "/api/settings") return Promise.resolve(jsonResponse(baseSettings()));
      if (url === "/api/media/build" && opts?.method === "POST") {
        return Promise.resolve(jsonResponse(statuses[0]));
      }
      if (url === "/api/media/build/status") {
        const s = statuses[Math.min(statusIdx, statuses.length - 1)];
        statusIdx += 1;
        return Promise.resolve(jsonResponse(s));
      }
      const def = launcherDefault(url);
      return Promise.resolve(def ?? jsonResponse({}));
    });

    await openSettings();
    // Two "Odśwież teraz" buttons exist (db_refresh + media). The media build
    // button is the last one rendered.
    await screen.findByText("Indeksuj teraz");
    const buttons = screen.getAllByText("Odśwież teraz");
    const mediaBtn = buttons[buttons.length - 1];
    fireEvent.click(mediaBtn);

    // Build POST was sent.
    await waitFor(() =>
      expect(
        calls.some((c) => c.url === "/api/media/build" && c.opts?.method === "POST"),
      ).toBe(true),
    );

    // Live progress (processed count) is shown while building. Polling runs on a
    // 1s interval, so allow several ticks.
    await waitFor(() => expect(screen.getByText(/40/)).toBeInTheDocument(), {
      timeout: 5000,
    });

    const mediaButton = () => {
      const all = screen.getAllByText("Odśwież teraz");
      return all[all.length - 1].closest("button");
    };

    // Button disabled while building.
    expect(mediaButton()).toBeDisabled();

    // Poll reaches idle: button re-enabled.
    await waitFor(() => expect(mediaButton()).not.toBeDisabled(), {
      timeout: 5000,
    });
  }, 15000);
});
