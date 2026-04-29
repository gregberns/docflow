import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  it("renders header, two-panel layout, and the FormPanel right pane", async () => {
    renderPage();

    await screen.findByTestId("document-header");
    expect(screen.getByTestId("document-filename")).toHaveTextContent(
      fixtures.document.sourceFilename,
    );
    expect(screen.getByTestId("detail-layout")).toBeInTheDocument();
    expect(screen.getByTestId("detail-pane-left")).toBeInTheDocument();
    expect(screen.getByTestId("detail-pane-right")).toBeInTheDocument();
    expect(screen.getByTestId("pdf-viewer")).toBeInTheDocument();
    expect(screen.getByTestId("form-panel")).toBeInTheDocument();
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
    expect(screen.getByTestId("form-panel")).toBeInTheDocument();
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

  it("mounts StageProgress in processed mode for an AWAITING_APPROVAL document", async () => {
    server.use(
      http.get("/api/documents/:documentId", () =>
        HttpResponse.json({
          ...fixtures.document,
          currentStageId: "approval",
          currentStageDisplayName: "Approval",
          currentStatus: "AWAITING_APPROVAL",
          workflowOriginStage: null,
        }),
      ),
    );

    renderPage();

    const stageProgress = await screen.findByTestId("stage-progress");
    expect(stageProgress).toBeInTheDocument();

    // Workflow stages should render with their displayNames + processed-mode states.
    await waitFor(() => {
      expect(screen.getByTestId("stage-workflow-approval")).toBeInTheDocument();
    });
    expect(screen.getByTestId("stage-workflow-review")).toHaveAttribute("data-state", "done");
    expect(screen.getByTestId("stage-workflow-approval")).toHaveAttribute(
      "data-state",
      "highlighted-pink",
    );
    expect(screen.getByTestId("stage-workflow-filed")).toHaveAttribute("data-state", "upcoming");
  });

  it("wires the FormPanel APPROVAL-branch onApprove handler to applyAction", async () => {
    const approvalDocument = {
      ...fixtures.document,
      currentStageId: "approval",
      currentStageDisplayName: "Approval",
      currentStatus: "AWAITING_APPROVAL" as const,
      workflowOriginStage: null,
    };
    let observedBody: unknown = null;
    let actionCallCount = 0;
    server.use(
      http.get("/api/documents/:documentId", () => HttpResponse.json(approvalDocument)),
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        actionCallCount += 1;
        observedBody = await request.json();
        return HttpResponse.json(approvalDocument);
      }),
    );

    renderPage();

    const approveButton = await screen.findByTestId("approve-button");
    fireEvent.click(approveButton);

    await waitFor(() => {
      expect(actionCallCount).toBe(1);
    });
    expect(observedBody).toEqual({ action: "Approve" });
  });
});
