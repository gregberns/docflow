import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useOrgEvents } from "../../src/hooks/useOrgEvents";

interface MockEventSource {
  url: string;
  listeners: Map<string, Set<(event: MessageEvent<string>) => void>>;
  closed: boolean;
  close: ReturnType<typeof vi.fn>;
  addEventListener: (name: string, handler: (event: MessageEvent<string>) => void) => void;
  removeEventListener: (name: string, handler: (event: MessageEvent<string>) => void) => void;
  dispatch: (name: string, data: string) => void;
}

const sources: MockEventSource[] = [];

class FakeEventSource implements MockEventSource {
  url: string;
  listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  closed = false;
  close = vi.fn(() => {
    this.closed = true;
  });

  constructor(url: string) {
    this.url = url;
    sources.push(this);
  }

  addEventListener(name: string, handler: (event: MessageEvent<string>) => void) {
    if (!this.listeners.has(name)) {
      this.listeners.set(name, new Set());
    }
    this.listeners.get(name)!.add(handler);
  }

  removeEventListener(name: string, handler: (event: MessageEvent<string>) => void) {
    this.listeners.get(name)?.delete(handler);
  }

  dispatch(name: string, data: string) {
    const set = this.listeners.get(name);
    if (!set) {
      return;
    }
    const event = new MessageEvent(name, { data });
    for (const handler of set) {
      handler(event);
    }
  }
}

function ConsumerHarness({ orgId, documentId }: { orgId?: string; documentId?: string }) {
  useOrgEvents(orgId, documentId ? { documentId } : undefined);
  return null;
}

function wrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  sources.length = 0;
  vi.stubGlobal("EventSource", FakeEventSource as unknown as typeof EventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useOrgEvents", () => {
  it("opens exactly one EventSource at /api/organizations/{orgId}/stream (AC5.1)", () => {
    const client = new QueryClient();
    const Wrapper = wrapper(client);
    render(
      <Wrapper>
        <ConsumerHarness orgId="pinnacle-legal" />
      </Wrapper>,
    );
    expect(sources).toHaveLength(1);
    expect(sources[0]!.url).toBe("/api/organizations/pinnacle-legal/stream");
  });

  it("invalidates the dashboard query on ProcessingStepChanged (AC5.2)", () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, "invalidateQueries");
    const Wrapper = wrapper(client);
    render(
      <Wrapper>
        <ConsumerHarness orgId="pinnacle-legal" />
      </Wrapper>,
    );
    spy.mockClear();
    act(() => {
      sources[0]!.dispatch(
        "ProcessingStepChanged",
        JSON.stringify({ organizationId: "pinnacle-legal" }),
      );
    });
    expect(spy).toHaveBeenCalledWith({ queryKey: ["dashboard", "pinnacle-legal"] });
  });

  it("invalidates dashboard + per-document query on DocumentStateChanged (AC5.3)", () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, "invalidateQueries");
    const Wrapper = wrapper(client);
    render(
      <Wrapper>
        <ConsumerHarness orgId="pinnacle-legal" />
      </Wrapper>,
    );
    spy.mockClear();
    act(() => {
      sources[0]!.dispatch(
        "DocumentStateChanged",
        JSON.stringify({ documentId: "doc-42", organizationId: "pinnacle-legal" }),
      );
    });
    const calls = spy.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: ["dashboard", "pinnacle-legal"] });
    expect(calls).toContainEqual({ queryKey: ["document", "doc-42"] });
  });

  it("closes the EventSource on unmount (AC5.4)", () => {
    const client = new QueryClient();
    const Wrapper = wrapper(client);
    const view = render(
      <Wrapper>
        <ConsumerHarness orgId="pinnacle-legal" />
      </Wrapper>,
    );
    expect(sources[0]!.close).not.toHaveBeenCalled();
    view.unmount();
    expect(sources[0]!.close).toHaveBeenCalledTimes(1);
  });

  it("does not open a connection when orgId is undefined", () => {
    const client = new QueryClient();
    const Wrapper = wrapper(client);
    render(
      <Wrapper>
        <ConsumerHarness orgId={undefined} />
      </Wrapper>,
    );
    expect(sources).toHaveLength(0);
  });
});
