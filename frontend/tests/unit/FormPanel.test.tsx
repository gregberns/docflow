import { describe, expect, it } from "vitest";
import { render as rtlRender, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FormPanel } from "../../src/components/FormPanel";
import type { DocumentView } from "../../src/types/readModels";
import type { FieldSchema } from "../../src/types/schema";
import type { StageSummary } from "../../src/types/workflow";

function render(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return rtlRender(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const PINNACLE_INVOICE_FIELDS: FieldSchema[] = [
  { name: "vendor", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "invoiceDate", type: "DATE", required: true, enumValues: null, itemFields: null },
  { name: "total", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
  {
    name: "lineItems",
    type: "ARRAY",
    required: false,
    enumValues: null,
    itemFields: [
      { name: "label", type: "STRING", required: true, enumValues: null, itemFields: null },
      { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
    ],
  },
];

const RIVERSIDE_RECEIPT_FIELDS: FieldSchema[] = [
  {
    name: "category",
    type: "ENUM",
    required: true,
    enumValues: ["MEAL", "FUEL", "SUPPLIES"],
    itemFields: null,
  },
  { name: "merchant", type: "STRING", required: true, enumValues: null, itemFields: null },
];

const DOCTYPE_OPTIONS = ["pinnacle-invoice", "client-intake"] as const;

function makeDocument(overrides: Partial<DocumentView> = {}): DocumentView {
  return {
    documentId: "doc-1",
    organizationId: "pinnacle",
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
    extractedFields: {
      vendor: "Acme Co",
      invoiceDate: "2026-04-01",
      total: "1,234.56",
      lineItems: [{ label: "Service", amount: "1,234.56" }],
    },
    reextractionStatus: "NONE",
    ...overrides,
  };
}

describe("FormPanel — branch dispatch", () => {
  it("REEXTRACTION_IN_PROGRESS branch shows the spinner banner, disables inputs, hides Approve/Reject (AC6.2)", () => {
    const doc = makeDocument({ reextractionStatus: "IN_PROGRESS" });
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        pendingNewDocumentType="client-intake"
      />,
    );
    const banner = screen.getByTestId("reextraction-in-progress-banner");
    expect(banner).toBeInTheDocument();
    expect(banner.textContent).toMatch(/Re-extracting as client-intake/i);

    expect(screen.getByTestId("form-panel")).toHaveAttribute(
      "data-branch",
      "REEXTRACTION_IN_PROGRESS",
    );

    const fieldset = screen.getByTestId("review-fields") as HTMLFieldSetElement;
    expect(fieldset).toBeDisabled();

    expect(screen.queryByTestId("approve-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("resolve-button")).not.toBeInTheDocument();
  });

  it("REEXTRACTION_FAILED branch shows the error banner with a re-enabled form (AC6.3)", () => {
    const doc = makeDocument({ reextractionStatus: "FAILED" });
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        reextractionFailureMessage="Anthropic returned 503"
      />,
    );
    expect(screen.getByTestId("reextraction-failed-banner")).toBeInTheDocument();
    expect(screen.getByTestId("reextraction-failed-message").textContent).toMatch(
      /Anthropic returned 503/,
    );

    const fieldset = screen.getByTestId("review-fields") as HTMLFieldSetElement;
    expect(fieldset).not.toBeDisabled();

    // Prior values are still bound.
    const vendorInput = screen.getByTestId("input-vendor") as HTMLInputElement;
    expect(vendorInput.value).toBe("Acme Co");
  });

  it("REVIEW (editable, not flagged) renders dropdown + Approve + Reject (AC6.4)", () => {
    const doc = makeDocument();
    render(
      <FormPanel
        document={doc}
        fields={RIVERSIDE_RECEIPT_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.getByTestId("form-panel")).toHaveAttribute("data-branch", "REVIEW");
    expect(screen.getByTestId("doctype-select")).toBeInTheDocument();
    const buttons = screen.getByTestId("review-action-bar").querySelectorAll("button");
    expect(buttons).toHaveLength(2);
    expect(screen.getByTestId("approve-button")).toBeInTheDocument();
    expect(screen.getByTestId("reject-button")).toBeInTheDocument();
    expect(screen.queryByTestId("resolve-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("flag-banner")).not.toBeInTheDocument();
  });

  it("REVIEW_FLAGGED renders FlagBanner and replaces Approve with Resolve (AC6.5)", () => {
    const doc = makeDocument({
      currentStatus: "FLAGGED",
      workflowOriginStage: "ATTORNEY_APPROVAL",
      flagComment: "Missing signature",
    });
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.getByTestId("form-panel")).toHaveAttribute("data-branch", "REVIEW_FLAGGED");
    const banner = screen.getByTestId("flag-banner");
    expect(banner).toBeInTheDocument();
    expect(screen.getByTestId("flag-banner-origin").textContent).toBe("ATTORNEY_APPROVAL");
    expect(screen.getByTestId("flag-banner-comment").textContent).toBe("Missing signature");

    expect(screen.getByTestId("resolve-button")).toBeInTheDocument();
    expect(screen.queryByTestId("approve-button")).not.toBeInTheDocument();
    expect(screen.getByTestId("reject-button")).toBeInTheDocument();
  });

  it("APPROVAL renders read-only summary with role suffix and Approve+Flag (AC6.6)", () => {
    const doc = makeDocument({
      currentStatus: "AWAITING_APPROVAL",
      currentStageDisplayName: "Attorney Approval",
    });
    const stage: StageSummary = {
      id: "attorney-approval",
      displayName: "Attorney Approval",
      kind: "APPROVAL",
      canonicalStatus: "AWAITING_APPROVAL",
      role: "Attorney",
    };
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        stage={stage}
      />,
    );
    expect(screen.getByTestId("form-panel")).toHaveAttribute("data-branch", "APPROVAL");
    expect(screen.getByTestId("approval-summary-heading").textContent).toBe(
      "Attorney Approval — role: Attorney",
    );

    // No editable form rendered in approval mode.
    expect(screen.queryByTestId("review-form")).not.toBeInTheDocument();
    expect(screen.queryByTestId("review-fields")).not.toBeInTheDocument();
    const actionBar = screen.getByTestId("approval-action-bar");
    expect(actionBar.querySelectorAll("button")).toHaveLength(2);
    expect(screen.getByTestId("approve-button")).toBeInTheDocument();
    expect(screen.getByTestId("flag-button")).toBeInTheDocument();
  });

  it("TERMINAL FILED renders the read-only summary with only Back to Documents (AC6.7)", () => {
    const doc = makeDocument({
      currentStatus: "FILED",
      currentStageDisplayName: "Filed",
    });
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.getByTestId("form-panel")).toHaveAttribute("data-branch", "TERMINAL");
    const actionBar = screen.getByTestId("terminal-action-bar");
    expect(actionBar.querySelectorAll("button")).toHaveLength(1);
    expect(screen.getByTestId("back-to-documents-button")).toBeInTheDocument();
  });

  it("TERMINAL REJECTED renders read-only with Back to Documents (AC6.7)", () => {
    const doc = makeDocument({
      currentStatus: "REJECTED",
      currentStageDisplayName: "Rejected",
    });
    render(
      <FormPanel
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.getByTestId("form-panel")).toHaveAttribute("data-branch", "TERMINAL");
    expect(screen.getByTestId("back-to-documents-button")).toBeInTheDocument();
    expect(screen.queryByTestId("approve-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-button")).not.toBeInTheDocument();
  });

  it("AC8.3 — array fields in non-Review modes render no inputs and no add/remove buttons", () => {
    // APPROVAL
    const approvalDoc = makeDocument({ currentStatus: "AWAITING_APPROVAL" });
    const { unmount: unmountApproval } = render(
      <FormPanel
        document={approvalDoc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    let table = screen.getByTestId("readonly-array");
    expect(table.querySelectorAll("input").length).toBe(0);
    expect(table.querySelectorAll("button").length).toBe(0);
    expect(screen.queryByTestId("add-row-lineItems")).not.toBeInTheDocument();
    expect(screen.queryByTestId("remove-row-lineItems-0")).not.toBeInTheDocument();
    unmountApproval();

    // FILED
    const filedDoc = makeDocument({ currentStatus: "FILED" });
    const { unmount: unmountFiled } = render(
      <FormPanel
        document={filedDoc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    table = screen.getByTestId("readonly-array");
    expect(table.querySelectorAll("input").length).toBe(0);
    expect(table.querySelectorAll("button").length).toBe(0);
    unmountFiled();

    // REJECTED
    const rejectedDoc = makeDocument({ currentStatus: "REJECTED" });
    render(
      <FormPanel
        document={rejectedDoc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    table = screen.getByTestId("readonly-array");
    expect(table.querySelectorAll("input").length).toBe(0);
    expect(table.querySelectorAll("button").length).toBe(0);
  });

  it("APPROVAL omits the role suffix when the stage has no role configured", () => {
    const doc = makeDocument({
      currentStatus: "AWAITING_APPROVAL",
      currentStageDisplayName: "Approval",
    });
    render(
      <FormPanel
        document={doc}
        fields={RIVERSIDE_RECEIPT_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
      />,
    );
    expect(screen.getByTestId("approval-summary-heading").textContent).toBe("Approval");
  });
});
