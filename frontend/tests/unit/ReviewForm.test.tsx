import { describe, expect, it } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReviewForm } from "../../src/components/ReviewForm";
import type { DocumentView } from "../../src/types/readModels";
import type { FieldSchema } from "../../src/types/schema";
import { server } from "../msw/server";

// Pinnacle Invoice — invoice has scalar fields in the seed; lineItems is the array case
// exercised in c6-frontend-spec.md AC8.1/AC8.2 and the existing FormPanel test.
const PINNACLE_INVOICE_FIELDS: FieldSchema[] = [
  { name: "vendor", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "invoiceDate", type: "DATE", required: true, enumValues: null, itemFields: null },
  { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
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

// Riverside Receipt — direct from backend/src/main/resources/seed/doc-types/riverside-bistro/receipt.yaml.
const RIVERSIDE_RECEIPT_FIELDS: FieldSchema[] = [
  { name: "merchant", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "date", type: "DATE", required: true, enumValues: null, itemFields: null },
  { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
  { name: "paymentMethod", type: "STRING", required: true, enumValues: null, itemFields: null },
  {
    name: "category",
    type: "ENUM",
    required: true,
    enumValues: ["food", "supplies", "equipment", "services"],
    itemFields: null,
  },
];

// Ironworks Lien Waiver — direct from backend/src/main/resources/seed/doc-types/ironworks-construction/lien-waiver.yaml.
const IRONWORKS_LIEN_WAIVER_FIELDS: FieldSchema[] = [
  { name: "subcontractor", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "projectCode", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "projectName", type: "STRING", required: true, enumValues: null, itemFields: null },
  { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
  { name: "throughDate", type: "DATE", required: true, enumValues: null, itemFields: null },
  {
    name: "waiverType",
    type: "ENUM",
    required: true,
    enumValues: ["conditional", "unconditional"],
    itemFields: null,
  },
];

const DOCTYPE_OPTIONS = ["pinnacle-invoice", "riverside-receipt", "ironworks-lien-waiver"] as const;

function makeDocument(overrides: Partial<DocumentView> = {}): DocumentView {
  return {
    documentId: "11111111-1111-1111-1111-111111111111",
    organizationId: "pinnacle-legal",
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
      amount: "1,234.56",
      lineItems: [{ label: "Service", amount: "1,234.56" }],
    },
    reextractionStatus: "NONE",
    ...overrides,
  };
}

function renderWithClient(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    client,
    ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>),
  };
}

describe("ReviewForm — Pinnacle Invoice (lineItems array)", () => {
  it("renders one input per scalar field plus an editable lineItems table (AC8.1, AC8.2)", () => {
    renderWithClient(
      <ReviewForm
        document={makeDocument()}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );

    expect(screen.getByTestId("input-vendor")).toBeInTheDocument();
    expect(screen.getByTestId("input-invoiceDate")).toBeInTheDocument();
    expect(screen.getByTestId("input-amount")).toBeInTheDocument();

    const arrayTable = screen.getByTestId("field-array-lineItems");
    expect(arrayTable).toBeInTheDocument();
    expect(screen.getByTestId("input-lineItems-0-label")).toBeInTheDocument();
    expect(screen.getByTestId("input-lineItems-0-amount")).toBeInTheDocument();
    expect(screen.getByTestId("add-row-lineItems")).toBeInTheDocument();
    expect(screen.getByTestId("remove-row-lineItems-0")).toBeInTheDocument();
  });

  it("Add row appends a blank lineItems row; Remove drops it (AC8.2)", () => {
    renderWithClient(
      <ReviewForm
        document={makeDocument()}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );
    expect(screen.getAllByTestId("field-array-lineItems-row")).toHaveLength(1);

    fireEvent.click(screen.getByTestId("add-row-lineItems"));
    expect(screen.getAllByTestId("field-array-lineItems-row")).toHaveLength(2);

    fireEvent.click(screen.getByTestId("remove-row-lineItems-1"));
    expect(screen.getAllByTestId("field-array-lineItems-row")).toHaveLength(1);
  });

  it("Approve button submits the form and triggers the Approve action mutation", async () => {
    let observedAction: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedAction = await request.json();
        return HttpResponse.json(makeDocument());
      }),
    );

    const doc = makeDocument();
    renderWithClient(
      <ReviewForm
        document={doc}
        fields={PINNACLE_INVOICE_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );

    fireEvent.click(screen.getByTestId("approve-button"));
    await waitFor(() => {
      expect(observedAction).toEqual({ action: "Approve" });
    });
  });
});

describe("ReviewForm — Riverside Receipt (category enum)", () => {
  it("renders the category as a <select> with the configured enum values", () => {
    const doc = makeDocument({
      detectedDocumentType: "receipt",
      extractedFields: {
        merchant: "Sunny Farms",
        date: "2026-04-15",
        amount: "42.50",
        paymentMethod: "card",
        category: "food",
      },
    });
    renderWithClient(
      <ReviewForm
        document={doc}
        fields={RIVERSIDE_RECEIPT_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );

    const select = screen.getByTestId("input-category") as HTMLSelectElement;
    expect(select.tagName.toLowerCase()).toBe("select");
    const optionValues = Array.from(select.options).map((o) => o.value);
    expect(optionValues).toEqual(["", "food", "supplies", "equipment", "services"]);
    expect(select.value).toBe("food");
  });

  it("rejects an out-of-enum value with an inline field error on submit", async () => {
    const doc = makeDocument({
      detectedDocumentType: "receipt",
      extractedFields: {
        merchant: "Sunny Farms",
        date: "2026-04-15",
        amount: "42.50",
        paymentMethod: "card",
        category: "",
      },
    });
    renderWithClient(
      <ReviewForm
        document={doc}
        fields={RIVERSIDE_RECEIPT_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );

    fireEvent.click(screen.getByTestId("approve-button"));
    await waitFor(() => {
      expect(screen.getByTestId("error-category")).toBeInTheDocument();
    });
  });
});

describe("ReviewForm — Ironworks Lien Waiver (waiverType enum)", () => {
  it("renders waiverType as <select> with conditional / unconditional options", () => {
    const doc = makeDocument({
      organizationId: "ironworks-construction",
      detectedDocumentType: "lien-waiver",
      extractedFields: {
        subcontractor: "Alpha Iron",
        projectCode: "PROJ-9",
        projectName: "Elm St Bridge",
        amount: "10000",
        throughDate: "2026-03-31",
        waiverType: "conditional",
      },
    });
    renderWithClient(
      <ReviewForm
        document={doc}
        fields={IRONWORKS_LIEN_WAIVER_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={false}
        disabled={false}
      />,
    );

    const select = screen.getByTestId("input-waiverType") as HTMLSelectElement;
    expect(select.tagName.toLowerCase()).toBe("select");
    expect(Array.from(select.options).map((o) => o.value)).toEqual([
      "",
      "conditional",
      "unconditional",
    ]);
    expect(select.value).toBe("conditional");
  });

  it("submits Resolve when the form is in flagged mode", async () => {
    let observedAction: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedAction = await request.json();
        return HttpResponse.json(makeDocument());
      }),
    );

    const doc = makeDocument({
      organizationId: "ironworks-construction",
      detectedDocumentType: "lien-waiver",
      currentStatus: "FLAGGED",
      workflowOriginStage: "GC_APPROVAL",
      flagComment: "Missing through date",
      extractedFields: {
        subcontractor: "Alpha Iron",
        projectCode: "PROJ-9",
        projectName: "Elm St Bridge",
        amount: "10000",
        throughDate: "2026-03-31",
        waiverType: "unconditional",
      },
    });
    renderWithClient(
      <ReviewForm
        document={doc}
        fields={IRONWORKS_LIEN_WAIVER_FIELDS}
        docTypeOptions={DOCTYPE_OPTIONS}
        flagged={true}
        disabled={false}
      />,
    );

    fireEvent.click(screen.getByTestId("resolve-button"));
    await waitFor(() => {
      expect(observedAction).toEqual({ action: "Resolve" });
    });
  });
});
