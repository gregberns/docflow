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

const MONEY_NAME_PATTERN =
  /(amount|total|subtotal|tax|fee|price|cost|balance|charge|payment|deposit|refund|discount|wage|salary|paid|due|retainage)/i;

export function looksLikeMoney(fieldName: string): boolean {
  return MONEY_NAME_PATTERN.test(fieldName);
}

export function formatMoney(value: unknown): string {
  const num = parseDecimal(value);
  if (num === null) return formatDisplay(value);
  return num.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export function formatNumber(value: unknown): string {
  const num = parseDecimal(value);
  if (num === null) return formatDisplay(value);
  return num.toLocaleString("en-US");
}

export function formatFieldValue(
  fieldName: string,
  fieldType: string,
  value: unknown,
): string {
  if (value === null || value === undefined || value === "") return "—";
  const upper = fieldType.toUpperCase();
  if (upper === "DECIMAL") {
    return looksLikeMoney(fieldName) ? formatMoney(value) : formatNumber(value);
  }
  return formatDisplay(value);
}
