import { describe, expect, it } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { server } from "../msw/server";
import { fixtures } from "../msw/handlers";
import { useUploadDocument } from "../../src/hooks/useUploadDocument";
import type { DashboardResponse } from "../../src/types/readModels";

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function makeFile(name = "upload.pdf"): File {
  return new File([new Uint8Array([1, 2, 3])], name, { type: "application/pdf" });
}

function seedDashboard(client: QueryClient, orgId: string): DashboardResponse {
  const seed: DashboardResponse = {
    processing: [],
    documents: [],
    stats: { inProgress: 0, awaitingReview: 0, flagged: 0, filedThisMonth: 0 },
  };
  client.setQueryData(["dashboard", orgId], seed);
  return seed;
}

describe("useUploadDocument", () => {
  it("inserts an optimistic processing row immediately on mutate (AC4.2)", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const orgId = "pinnacle-legal";
    seedDashboard(client, orgId);

    const observed: DashboardResponse[] = [];
    const originalSet = client.setQueryData.bind(client);
    vi.spyOn(client, "setQueryData").mockImplementation(((
      key: unknown,
      value: unknown,
      ...rest: unknown[]
    ) => {
      if (
        Array.isArray(key) &&
        key[0] === "dashboard" &&
        key[1] === orgId &&
        value &&
        typeof value === "object"
      ) {
        observed.push(structuredClone(value as DashboardResponse));
      }
      return (originalSet as (...args: unknown[]) => unknown)(key, value, ...rest);
    }) as typeof client.setQueryData);

    const { result } = renderHook(() => useUploadDocument(orgId), {
      wrapper: makeWrapper(client),
    });

    let returned: unknown;
    await act(async () => {
      returned = await result.current.mutateAsync({ file: makeFile("invoice.pdf") });
    });

    expect(observed.length).toBeGreaterThan(0);
    const optimisticSnapshot = observed[0]!;
    expect(optimisticSnapshot.processing).toHaveLength(1);
    expect(optimisticSnapshot.processing[0]?.sourceFilename).toBe("invoice.pdf");
    expect(optimisticSnapshot.processing[0]?.currentStep).toBe("TEXT_EXTRACTING");
    expect(returned).toEqual(fixtures.upload);
  });

  it("rolls back the cache on upload failure (onError)", async () => {
    server.use(
      http.post("/api/organizations/:orgId/documents", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Unsupported Media Type",
            status: 415,
            code: "UNSUPPORTED_MEDIA_TYPE",
            message: "Only PDF and image files are supported",
            details: [],
          },
          { status: 415, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const orgId = "pinnacle-legal";
    const seed = seedDashboard(client, orgId);

    const { result } = renderHook(() => useUploadDocument(orgId), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      try {
        await result.current.mutateAsync({ file: makeFile("bad.txt") });
      } catch {
        // expected
      }
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    const cached = client.getQueryData<DashboardResponse>(["dashboard", orgId]);
    expect(cached).toEqual(seed);
  });

  it("invalidates the dashboard query on settled (success path)", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const orgId = "pinnacle-legal";
    seedDashboard(client, orgId);

    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useUploadDocument(orgId), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync({ file: makeFile() });
    });

    expect(
      invalidateSpy.mock.calls.some(
        (call) =>
          Array.isArray(call[0]?.queryKey) &&
          call[0].queryKey[0] === "dashboard" &&
          call[0].queryKey[1] === orgId,
      ),
    ).toBe(true);
  });
});
