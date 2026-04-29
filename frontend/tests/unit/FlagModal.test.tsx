import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FlagModal } from "../../src/components/FlagModal";
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
    currentStageId: "approval",
    currentStageDisplayName: "Approval",
    currentStatus: "AWAITING_APPROVAL",
    workflowOriginStage: null,
    flagComment: null,
    detectedDocumentType: "pinnacle-invoice",
    extractedFields: { vendor: "Acme Co" },
    reextractionStatus: "NONE",
    ...overrides,
  };
}

describe("FlagModal — direct render", () => {
  it("renders role=dialog with required textarea and submit-disabled when empty (AC9.1, AC9.2)", () => {
    renderWithClient(
      <FlagModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        onCancel={() => {}}
        onSubmitted={() => {}}
      />,
    );
    const modal = screen.getByTestId("flag-modal");
    expect(modal).toHaveAttribute("role", "dialog");
    const textarea = screen.getByTestId("flag-modal-comment") as HTMLTextAreaElement;
    expect(textarea.tagName.toLowerCase()).toBe("textarea");
    expect(textarea).toBeRequired();
    expect(screen.getByTestId("flag-modal-submit")).toBeDisabled();
  });

  it("Submit stays disabled when textarea contains only whitespace (AC9.2)", () => {
    renderWithClient(
      <FlagModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        onCancel={() => {}}
        onSubmitted={() => {}}
      />,
    );
    const textarea = screen.getByTestId("flag-modal-comment");
    fireEvent.change(textarea, { target: { value: "   \n  " } });
    expect(screen.getByTestId("flag-modal-submit")).toBeDisabled();
    fireEvent.change(textarea, { target: { value: "\t\t" } });
    expect(screen.getByTestId("flag-modal-submit")).toBeDisabled();
  });

  it("Submit becomes enabled when textarea contains non-whitespace (AC9.2)", () => {
    renderWithClient(
      <FlagModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        onCancel={() => {}}
        onSubmitted={() => {}}
      />,
    );
    fireEvent.change(screen.getByTestId("flag-modal-comment"), {
      target: { value: "Missing signature" },
    });
    expect(screen.getByTestId("flag-modal-submit")).toBeEnabled();
  });

  it("Submit POSTs the canonical /actions endpoint with { action: Flag, comment } (AC9.3)", async () => {
    let observedUrl: string | null = null;
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedUrl = new URL(request.url).pathname;
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
      // The spec-drift URL `/{id}/{stageId}/flag` must NOT be hit. If something does
      // hit it, MSW's onUnhandledRequest=error in setup.ts will fail the test.
    );
    const onSubmitted = vi.fn();
    renderWithClient(
      <FlagModal
        documentId={fixtures.document.documentId}
        organizationId="pinnacle-legal"
        onCancel={() => {}}
        onSubmitted={onSubmitted}
      />,
    );

    fireEvent.change(screen.getByTestId("flag-modal-comment"), {
      target: { value: "Totals don't match line items." },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId("flag-modal-submit"));
    });

    await waitFor(() => {
      expect(observedBody).toEqual({
        action: "Flag",
        comment: "Totals don't match line items.",
      });
    });
    expect(observedUrl).toBe(`/api/documents/${fixtures.document.documentId}/actions`);
    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalledTimes(1);
    });
  });

  it("Cancel invokes onCancel without firing the action", () => {
    const onCancel = vi.fn();
    let actionCalled = false;
    server.use(
      http.post("/api/documents/:documentId/actions", () => {
        actionCalled = true;
        return HttpResponse.json(fixtures.document);
      }),
    );
    renderWithClient(
      <FlagModal
        documentId="doc-1"
        organizationId="pinnacle-legal"
        onCancel={onCancel}
        onSubmitted={() => {}}
      />,
    );
    fireEvent.click(screen.getByTestId("flag-modal-cancel"));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(actionCalled).toBe(false);
  });
});

describe("FlagModal — wired into FormPanel approval branch", () => {
  it("clicking Flag on the approval action bar opens the flag-modal (AC9.1)", () => {
    const doc = makeDocument();
    renderWithClient(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.queryByTestId("flag-modal")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("flag-button"));
    expect(screen.getByTestId("flag-modal")).toBeInTheDocument();
  });

  it("submitting the flag-modal closes it and invalidates the document query", async () => {
    server.use(
      http.post("/api/documents/:documentId/actions", () => HttpResponse.json(fixtures.document)),
    );
    const doc = makeDocument();
    const { client } = renderWithClient(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");

    fireEvent.click(screen.getByTestId("flag-button"));
    fireEvent.change(screen.getByTestId("flag-modal-comment"), {
      target: { value: "Missing total" },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId("flag-modal-submit"));
    });

    await waitFor(() => {
      expect(screen.queryByTestId("flag-modal")).not.toBeInTheDocument();
    });
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] } | undefined)?.queryKey,
    );
    expect(invalidatedKeys).toContainEqual(["document", doc.documentId]);
  });
});
