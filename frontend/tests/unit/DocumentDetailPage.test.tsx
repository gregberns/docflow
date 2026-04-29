import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { server } from "../msw/server";
import { fixtures } from "../msw/handlers";
import { DocumentDetailPage } from "../../src/routes/DocumentDetailPage";

interface MockEventSource {
  url: string;
  closed: boolean;
  close: ReturnType<typeof vi.fn>;
  addEventListener: () => void;
  removeEventListener: () => void;
}

const sources: MockEventSource[] = [];

class FakeEventSource implements MockEventSource {
  url: string;
  closed = false;
  close = vi.fn(() => {
    this.closed = true;
  });
  addEventListener = () => {};
  removeEventListener = () => {};
  constructor(url: string) {
    this.url = url;
    sources.push(this);
  }
}

let pdfLoadShouldFail = false;
let documentRequestCount = 0;

vi.mock("react-pdf", () => {
  return {
    Document: ({
      children,
      onLoadSuccess,
      onLoadError,
    }: {
      children?: ReactNode;
      onLoadSuccess?: (pdf: { numPages: number }) => void;
      onLoadError?: (err: Error) => void;
    }) => {
      if (pdfLoadShouldFail) {
        queueMicrotask(() => onLoadError?.(new Error("simulated PDF failure")));
      } else {
        queueMicrotask(() => onLoadSuccess?.({ numPages: 1 }));
      }
      return <div data-testid="pdf-document-mock">{children}</div>;
    },
    Page: ({ pageNumber }: { pageNumber: number }) => (
      <div data-testid="pdf-page-mock" data-page-number={pageNumber} />
    ),
  };
});

function renderPage(documentId = fixtures.document.documentId) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/documents/${documentId}`]}>
        <Routes>
          <Route path="/documents/:documentId" element={<DocumentDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sources.length = 0;
  pdfLoadShouldFail = false;
  documentRequestCount = 0;
  vi.stubGlobal("EventSource", FakeEventSource as unknown as typeof EventSource);
  server.use(
    http.get("/api/documents/:documentId", () => {
      documentRequestCount += 1;
      return HttpResponse.json(fixtures.document);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("DocumentDetailPage", () => {
  it("renders header, two-panel layout, and the form-panel placeholder", async () => {
    renderPage();

    await screen.findByTestId("document-header");
    expect(screen.getByTestId("document-filename")).toHaveTextContent(
      fixtures.document.sourceFilename,
    );
    expect(screen.getByTestId("detail-layout")).toBeInTheDocument();
    expect(screen.getByTestId("detail-pane-left")).toBeInTheDocument();
    expect(screen.getByTestId("detail-pane-right")).toBeInTheDocument();
    expect(screen.getByTestId("pdf-viewer")).toBeInTheDocument();
    expect(screen.getByTestId("form-panel-placeholder")).toBeInTheDocument();
  });

  it("opens exactly one EventSource and issues exactly one GET /api/documents/{id}", async () => {
    renderPage();

    await screen.findByTestId("document-header");
    // Wait long enough that any duplicate fetches or duplicate SSE connections would have happened.
    await waitFor(() => {
      expect(sources).toHaveLength(1);
    });
    expect(sources[0]!.url).toBe(
      `/api/organizations/${encodeURIComponent(fixtures.document.organizationId)}/stream`,
    );
    expect(documentRequestCount).toBe(1);
  });

  it("renders the PDF onLoadError fallback while keeping the form panel usable", async () => {
    pdfLoadShouldFail = true;
    renderPage();

    await screen.findByTestId("document-header");

    await act(async () => {
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByTestId("pdf-load-error")).toBeInTheDocument();
    });
    expect(screen.getByTestId("pdf-viewer")).toHaveAttribute("data-pdf-state", "error");
    // Form panel must remain rendered and usable even when the PDF errors.
    expect(screen.getByTestId("form-panel-placeholder")).toBeInTheDocument();
    expect(screen.getByTestId("detail-pane-right")).toBeInTheDocument();
  });

  it("closes the EventSource on unmount", async () => {
    const view = renderPage();
    await screen.findByTestId("document-header");
    await waitFor(() => expect(sources).toHaveLength(1));
    expect(sources[0]!.close).not.toHaveBeenCalled();
    view.unmount();
    expect(sources[0]!.close).toHaveBeenCalledTimes(1);
  });
});
