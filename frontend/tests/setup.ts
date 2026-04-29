import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./msw/server";

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
