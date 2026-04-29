import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./msw/server";

// jsdom lacks several canvas-related globals that pdfjs-dist touches at module scope.
// Provide no-op shims so imports don't throw; tests that exercise rendering mock react-pdf.
if (typeof (globalThis as { DOMMatrix?: unknown }).DOMMatrix === "undefined") {
  class NoopDOMMatrix {}
  (globalThis as { DOMMatrix?: unknown }).DOMMatrix = NoopDOMMatrix as unknown as typeof DOMMatrix;
}
if (typeof (globalThis as { Path2D?: unknown }).Path2D === "undefined") {
  class NoopPath2D {}
  (globalThis as { Path2D?: unknown }).Path2D = NoopPath2D as unknown as typeof Path2D;
}
if (typeof (globalThis as { ImageData?: unknown }).ImageData === "undefined") {
  class NoopImageData {}
  (globalThis as { ImageData?: unknown }).ImageData = NoopImageData as unknown as typeof ImageData;
}

if (typeof globalThis.EventSource === "undefined") {
  class NoopEventSource {
    url: string;
    readyState = 0;
    withCredentials = false;
    onopen: ((event: Event) => void) | null = null;
    onmessage: ((event: MessageEvent) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    constructor(url: string) {
      this.url = url;
    }
    addEventListener() {}
    removeEventListener() {}
    dispatchEvent(): boolean {
      return true;
    }
    close() {}
  }
  globalThis.EventSource = NoopEventSource as unknown as typeof EventSource;
}

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
