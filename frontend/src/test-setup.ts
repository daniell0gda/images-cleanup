import "@testing-library/jest-dom";
import { vi } from "vitest";

// Mantine reads matchMedia for color-scheme detection; jsdom does not implement it.
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
});

// jsdom has no layout engine. @tanstack/react-virtual measures elements via offsetHeight.
// - Scroll container (data-virtual-scroll): 500px height, 800px width.
// - Virtual rows (data-index): 130px height — matches estimateSize so positions are stable.
// - Everything else: 0 (jsdom default).
Object.defineProperty(HTMLElement.prototype, "offsetHeight", {
  configurable: true,
  get() {
    const el = this as HTMLElement;
    if (el.hasAttribute("data-virtual-scroll")) return 500;
    if (el.hasAttribute("data-index")) return 130;
    return 0;
  },
});
Object.defineProperty(HTMLElement.prototype, "offsetWidth", {
  configurable: true,
  get() {
    const el = this as HTMLElement;
    if (el.hasAttribute("data-virtual-scroll")) return 800;
    if (el.hasAttribute("data-index")) return 800;
    return 0;
  },
});
