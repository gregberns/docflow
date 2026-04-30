export function formatDecimal(value: unknown): string {
  if (value === null || value === undefined || value === "") {
    return "";
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return value.toString();
  }
  if (typeof value === "string") {
    return value;
  }
  return String(value);
}

export function parseDecimal(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string") return null;
  const stripped = value.replace(/,/g, "").trim();
  if (stripped === "") return null;
  const parsed = Number(stripped);
  return Number.isFinite(parsed) ? parsed : null;
}

export function formatDate(value: unknown): string {
  if (value === null || value === undefined || value === "") return "";
  return String(value);
}

export function formatDisplay(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value === "" ? "—" : value;
  if (typeof value === "number") return value.toString();
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return JSON.stringify(value);
}

export function formatDocType(value: string): string {
  if (!value) return value;
  const spaced = value.replace(/_/g, " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function formatFieldName(value: string): string {
  if (!value) return value;
  const camelSplit = value.replace(/([a-z])([A-Z])/g, "$1 $2");
  const spaced = camelSplit.replace(/_/g, " ");
  return spaced
    .split(/\s+/)
    .map((word) => (word ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}

export function formatMoney(value: unknown, currency = "USD"): string {
  const num = parseDecimal(value);
  if (num === null) return formatDisplay(value);
  return num.toLocaleString("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export function formatPercent(value: unknown): string {
  const num = parseDecimal(value);
  if (num === null) return formatDisplay(value);
  return num.toLocaleString("en-US", {
    style: "percent",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export function formatNumber(value: unknown): string {
  const num = parseDecimal(value);
  if (num === null) return formatDisplay(value);
  return num.toLocaleString("en-US");
}

const DOC_PRIMARY_FIELD: Record<string, string> = {
  invoice: "invoiceNumber",
  "retainer-agreement": "retainerNumber",
  "expense-report": "submissionDate",
  receipt: "date",
  "change-order": "projectCode",
  "lien-waiver": "projectCode",
};

const DOC_SUBTITLE_FIELD: Record<string, string> = {
  invoice: "vendor",
  "retainer-agreement": "clientName",
  "expense-report": "attorneyName",
  receipt: "merchant",
  "change-order": "projectName",
  "lien-waiver": "subcontractor",
};

export function docDisplayId(
  detectedDocumentType: string | null,
  extractedFields: Record<string, unknown>,
  sourceFilename: string,
): string {
  if (!detectedDocumentType) return sourceFilename;
  const field = DOC_PRIMARY_FIELD[detectedDocumentType];
  if (!field) return sourceFilename;
  const val = extractedFields[field];
  if (val === null || val === undefined || val === "") return sourceFilename;
  return String(val);
}

export function docSubtitle(
  detectedDocumentType: string | null,
  extractedFields: Record<string, unknown>,
): string | null {
  if (!detectedDocumentType) return null;
  const field = DOC_SUBTITLE_FIELD[detectedDocumentType];
  if (!field) return null;
  const val = extractedFields[field];
  if (val === null || val === undefined || val === "") return null;
  return String(val);
}

export function formatFieldValue(
  fieldType: string,
  format: string | undefined,
  value: unknown,
): string {
  if (value === null || value === undefined || value === "") return "—";

  if (format) {
    if (format.startsWith("currency:")) {
      const code = format.slice("currency:".length) || "USD";
      return formatMoney(value, code);
    }
    if (format === "percent") {
      return formatPercent(value);
    }
  }

  const upper = fieldType.toUpperCase();
  if (upper === "DECIMAL") return formatNumber(value);
  return formatDisplay(value);
}
