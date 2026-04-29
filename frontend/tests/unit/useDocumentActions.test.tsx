import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { server } from "../msw/server";
import { fixtures } from "../msw/handlers";
import { useDocumentActions } from "../../src/hooks/useDocumentActions";
import { setNotifier } from "../../src/util/notify";

const DOCUMENT_ID = fixtures.document.documentId;
const ORG_ID = fixtures.document.organizationId;

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

const notifications: string[] = [];

beforeEach(() => {
  notifications.length = 0;
  setNotifier((m) => notifications.push(m));
});

afterEach(() => {
  setNotifier(null);
});

describe("useDocumentActions — success paths", () => {
  it("Approve POSTs { action: 'Approve' } and invalidates document + dashboard on success", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
    );

    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      await result.current.approve.mutateAsync();
    });

    expect(observedBody).toEqual({ action: "Approve" });
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] } | undefined)?.queryKey,
    );
    expect(invalidatedKeys).toContainEqual(["document", DOCUMENT_ID]);
    expect(invalidatedKeys).toContainEqual(["dashboard", ORG_ID]);
  });

  it("Reject POSTs { action: 'Reject' }", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
    );
    const client = makeClient();
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );
    await act(async () => {
      await result.current.reject.mutateAsync();
    });
    expect(observedBody).toEqual({ action: "Reject" });
  });

  it("Flag POSTs { action: 'Flag', comment } with the supplied comment", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
    );
    const client = makeClient();
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );
    await act(async () => {
      await result.current.flag.mutateAsync({ comment: "Missing signature" });
    });
    expect(observedBody).toEqual({ action: "Flag", comment: "Missing signature" });
  });

  it("Resolve POSTs { action: 'Resolve' } (no newDocumentType payload)", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
    );
    const client = makeClient();
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );
    await act(async () => {
      await result.current.resolve.mutateAsync();
    });
    expect(observedBody).toEqual({ action: "Resolve" });
  });

  it("Retype POSTs to /review/retype with newDocumentType and invalidates both keys", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/review/retype", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.retype, { status: 202 });
      }),
    );
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );
    await act(async () => {
      await result.current.retype.mutateAsync({ newDocumentType: "client-intake" });
    });
    expect(observedBody).toEqual({ newDocumentType: "client-intake" });
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] } | undefined)?.queryKey,
    );
    expect(invalidatedKeys).toContainEqual(["document", DOCUMENT_ID]);
    expect(invalidatedKeys).toContainEqual(["dashboard", ORG_ID]);
  });
});

describe("useDocumentActions — error paths", () => {
  it("on a generic 500 error, fires the toast and invalidates ['document', id]", async () => {
    server.use(
      http.post("/api/documents/:documentId/actions", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Internal Error",
            status: 500,
            code: "INTERNAL_ERROR",
            message: "Save failed",
            details: [],
          },
          { status: 500, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      try {
        await result.current.approve.mutateAsync();
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(result.current.approve.isError).toBe(true);
    });
    expect(notifications).toEqual(["Save failed; refresh and retry"]);
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] } | undefined)?.queryKey,
    );
    expect(invalidatedKeys).toContainEqual(["document", DOCUMENT_ID]);
  });

  it("400 VALIDATION_FAILED maps each detail.path → setFieldError and skips the toast", async () => {
    server.use(
      http.post("/api/documents/:documentId/actions", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Bad Request",
            status: 400,
            code: "VALIDATION_FAILED",
            message: "Validation failed",
            details: [
              { path: "vendor", message: "must not be blank" },
              { path: "amount", message: "must be a number" },
            ],
          },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );

    const setFieldError = vi.fn();
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(
      () =>
        useDocumentActions({
          documentId: DOCUMENT_ID,
          organizationId: ORG_ID,
          setFieldError,
        }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      try {
        await result.current.approve.mutateAsync();
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(result.current.approve.isError).toBe(true);
    });

    expect(setFieldError).toHaveBeenCalledWith("vendor", "must not be blank");
    expect(setFieldError).toHaveBeenCalledWith("amount", "must be a number");
    expect(notifications).toEqual([]);
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] } | undefined)?.queryKey,
    );
    expect(invalidatedKeys).toContainEqual(["document", DOCUMENT_ID]);
  });

  it("400 VALIDATION_FAILED with no setFieldError still falls through to the generic toast", async () => {
    server.use(
      http.post("/api/documents/:documentId/actions", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Bad Request",
            status: 400,
            code: "VALIDATION_FAILED",
            message: "Validation failed",
            details: [{ path: "vendor", message: "must not be blank" }],
          },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );

    const client = makeClient();
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      try {
        await result.current.approve.mutateAsync();
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(result.current.approve.isError).toBe(true);
    });
    expect(notifications).toEqual(["Save failed; refresh and retry"]);
  });

  it("Retype error path also fires the toast and invalidates document", async () => {
    server.use(
      http.post("/api/documents/:documentId/review/retype", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Not Found",
            status: 404,
            code: "UNKNOWN_DOC_TYPE",
            message: "Unknown doc type",
            details: [],
          },
          { status: 404, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );

    const client = makeClient();
    const { result } = renderHook(
      () => useDocumentActions({ documentId: DOCUMENT_ID, organizationId: ORG_ID }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      try {
        await result.current.retype.mutateAsync({ newDocumentType: "missing" });
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(result.current.retype.isError).toBe(true);
    });
    expect(notifications).toEqual(["Save failed; refresh and retry"]);
  });
});
