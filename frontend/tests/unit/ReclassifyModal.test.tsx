import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReclassifyModal } from "../../src/components/ReclassifyModal";
import { FormPanel } from "../../src/components/FormPanel";
import type { DocumentView } from "../../src/types/readModels";
import type { FieldSchema } from "../../src/types/schema";
import { server } from "../msw/server";
import { fixtures } from "../msw/handlers";

function renderWithClient(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    client,
    ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>),
  };
}

const PINNACLE_INVOICE_FIELDS: FieldSchema[] = [
  { name: "vendor", type: "STRING", required: true, enumValues: null, itemFields: null },
];

const DOCTYPE_OPTIONS = ["pinnacle-invoice", "client-intake"] as const;

function makeDocument(overrides: Partial<DocumentView> = {}): DocumentView {
  return {
    documentId: fixtures.document.documentId,
    organizationId: fixtures.document.organizationId,
    sourceFilename: "invoice.pdf",
    mimeType: "application/pdf",
    uploadedAt: "2026-04-01T00:00:00Z",
    processedAt: "2026-04-01T00:00:30Z",
    rawText: null,
    currentStageId: "review",
    currentStageDisplayName: "Review",
    currentStatus: "AWAITING_REVIEW",
    workflowOriginStage: null,
    flagComment: null,
    detectedDocumentType: "pinnacle-invoice",
    extractedFields: { vendor: "Acme Co" },
    reextractionStatus: "NONE",
    ...overrides,
  };
}

describe("ReclassifyModal — direct render", () => {
  it("renders a role=dialog with reclassify-modal testid", () => {
    renderWithClient(
      <ReclassifyModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        newDocumentType="client-intake"
        previousDocumentType="pinnacle-invoice"
        onCancel={() => {}}
        onConfirmed={() => {}}
      />,
    );
    const modal = screen.getByTestId("reclassify-modal");
    expect(modal).toHaveAttribute("role", "dialog");
    expect(modal.textContent).toMatch(/client-intake/);
    expect(modal.textContent).toMatch(/pinnacle-invoice/);
  });

  it("Cancel invokes onCancel and does NOT call /review/retype", () => {
    const onCancel = vi.fn();
    let retypeCalled = false;
    server.use(
      http.post("/api/documents/:documentId/review/retype", () => {
        retypeCalled = true;
        return HttpResponse.json(fixtures.retype, { status: 202 });
      }),
    );
    renderWithClient(
      <ReclassifyModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        newDocumentType="client-intake"
        previousDocumentType="pinnacle-invoice"
        onCancel={onCancel}
        onConfirmed={() => {}}
      />,
    );
    fireEvent.click(screen.getByTestId("reclassify-cancel"));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(retypeCalled).toBe(false);
  });

  it("Confirm POSTs /review/retype with newDocumentType and calls onConfirmed (AC8.4)", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/review/retype", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.retype, { status: 202 });
      }),
    );
    const onConfirmed = vi.fn();
    renderWithClient(
      <ReclassifyModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        newDocumentType="client-intake"
        previousDocumentType="pinnacle-invoice"
        onCancel={() => {}}
        onConfirmed={onConfirmed}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId("reclassify-confirm"));
    });
    await waitFor(() => {
      expect(observedBody).toEqual({ newDocumentType: "client-intake" });
    });
    await waitFor(() => {
      expect(onConfirmed).toHaveBeenCalledWith("client-intake");
    });
  });
});

describe("ReclassifyModal — wired into FormPanel", () => {
  it("changing the doc-type dropdown opens the reclassify-modal (AC8.4)", () => {
    const doc = makeDocument();
    renderWithClient(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.queryByTestId("reclassify-modal")).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("doctype-select"), {
      target: { value: "client-intake" },
    });
    expect(screen.getByTestId("reclassify-modal")).toBeInTheDocument();
  });

  it("Cancel reverts the doc-type dropdown to the previous value (AC8.4)", () => {
    const doc = makeDocument();
    renderWithClient(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );

    const select = screen.getByTestId("doctype-select") as HTMLSelectElement;
    expect(select.value).toBe("pinnacle-invoice");

    fireEvent.change(select, { target: { value: "client-intake" } });
    expect(select.value).toBe("client-intake");
    expect(screen.getByTestId("reclassify-modal")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("reclassify-cancel"));
    expect(screen.queryByTestId("reclassify-modal")).not.toBeInTheDocument();
    // The dropdown reverts to the previous (committed) document.detectedDocumentType.
    expect((screen.getByTestId("doctype-select") as HTMLSelectElement).value).toBe(
      "pinnacle-invoice",
    );
  });

  it("Confirm fires retype and dismisses the modal (AC8.4)", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/review/retype", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.retype, { status: 202 });
      }),
    );
    const doc = makeDocument();
    renderWithClient(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );

    fireEvent.change(screen.getByTestId("doctype-select"), {
      target: { value: "client-intake" },
    });
    expect(screen.getByTestId("reclassify-modal")).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByTestId("reclassify-confirm"));
    });
    await waitFor(() => {
      expect(observedBody).toEqual({ newDocumentType: "client-intake" });
    });
    await waitFor(() => {
      expect(screen.queryByTestId("reclassify-modal")).not.toBeInTheDocument();
    });
  });
});
