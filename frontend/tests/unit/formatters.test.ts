import { describe, expect, it } from "vitest";
import {
  formatDate,
  formatDecimal,
  formatDisplay,
  formatFieldValue,
  formatMoney,
  formatPercent,
  parseDecimal,
} from "../../src/util/formatters";

describe("formatDecimal", () => {
  it("returns empty string for null/undefined/empty input", () => {
    expect(formatDecimal(null)).toBe("");
    expect(formatDecimal(undefined)).toBe("");
    expect(formatDecimal("")).toBe("");
  });

  it("stringifies finite numbers", () => {
    expect(formatDecimal(0)).toBe("0");
    expect(formatDecimal(42)).toBe("42");
    expect(formatDecimal(-3.14)).toBe("-3.14");
  });

  it("passes strings through unchanged", () => {
    expect(formatDecimal("123.45")).toBe("123.45");
    expect(formatDecimal("not a number")).toBe("not a number");
  });

  it("falls back to String() for non-finite numbers and other types", () => {
    expect(formatDecimal(Number.POSITIVE_INFINITY)).toBe("Infinity");
    expect(formatDecimal(Number.NaN)).toBe("NaN");
    expect(formatDecimal(true)).toBe("true");
  });
});

describe("parseDecimal", () => {
  it("returns null for null and undefined", () => {
    expect(parseDecimal(null)).toBeNull();
    expect(parseDecimal(undefined)).toBeNull();
  });

  it("returns the same number when finite, null otherwise", () => {
    expect(parseDecimal(7)).toBe(7);
    expect(parseDecimal(-2.5)).toBe(-2.5);
    expect(parseDecimal(Number.POSITIVE_INFINITY)).toBeNull();
    expect(parseDecimal(Number.NaN)).toBeNull();
  });

  it("returns null for non-string, non-number values", () => {
    expect(parseDecimal(true)).toBeNull();
    expect(parseDecimal({})).toBeNull();
  });

  it("strips commas, trims whitespace, and parses numeric strings", () => {
    expect(parseDecimal("1,234.5")).toBe(1234.5);
    expect(parseDecimal("  42 ")).toBe(42);
  });

  it("returns null for empty or non-numeric strings", () => {
    expect(parseDecimal("")).toBeNull();
    expect(parseDecimal("   ")).toBeNull();
    expect(parseDecimal("abc")).toBeNull();
  });
});

describe("formatDate", () => {
  it("returns empty string for null/undefined/empty input", () => {
    expect(formatDate(null)).toBe("");
    expect(formatDate(undefined)).toBe("");
    expect(formatDate("")).toBe("");
  });

  it("stringifies any other value", () => {
    expect(formatDate("2026-04-29")).toBe("2026-04-29");
    expect(formatDate(0)).toBe("0");
  });
});

describe("formatDisplay", () => {
  it("returns em-dash for null/undefined/empty string", () => {
    expect(formatDisplay(null)).toBe("—");
    expect(formatDisplay(undefined)).toBe("—");
    expect(formatDisplay("")).toBe("—");
  });

  it("returns the string as-is when non-empty", () => {
    expect(formatDisplay("hello")).toBe("hello");
  });

  it("stringifies numbers and booleans", () => {
    expect(formatDisplay(0)).toBe("0");
    expect(formatDisplay(3.14)).toBe("3.14");
    expect(formatDisplay(true)).toBe("Yes");
    expect(formatDisplay(false)).toBe("No");
  });

  it("JSON-stringifies arrays and objects", () => {
    expect(formatDisplay([1, 2])).toBe("[1,2]");
    expect(formatDisplay({ a: 1 })).toBe('{"a":1}');
  });
});

describe("formatMoney", () => {
  it("formats a numeric value as USD by default", () => {
    expect(formatMoney(199.73)).toBe("$199.73");
  });

  it("respects the currency code argument", () => {
    expect(formatMoney(199.73, "EUR")).toBe("€199.73");
  });

  it("falls through to formatDisplay for null/undefined/non-numeric", () => {
    expect(formatMoney(null)).toBe("—");
    expect(formatMoney(undefined)).toBe("—");
    expect(formatMoney("abc")).toBe("abc");
  });
});

describe("formatPercent", () => {
  it("formats a decimal fraction as a percentage", () => {
    expect(formatPercent(0.075)).toBe("7.50%");
  });

  it("falls through to formatDisplay for null/undefined/non-numeric", () => {
    expect(formatPercent(null)).toBe("—");
    expect(formatPercent(undefined)).toBe("—");
    expect(formatPercent("abc")).toBe("abc");
  });
});

describe("formatFieldValue", () => {
  it("formats DECIMAL with currency:USD format", () => {
    expect(formatFieldValue("DECIMAL", "currency:USD", 199.73)).toBe("$199.73");
  });

  it("formats DECIMAL with currency:EUR format", () => {
    expect(formatFieldValue("DECIMAL", "currency:EUR", 199.73)).toBe("€199.73");
  });

  it("formats DECIMAL with percent format", () => {
    expect(formatFieldValue("DECIMAL", "percent", 0.075)).toBe("7.50%");
  });

  it("falls back to formatNumber for DECIMAL with unknown format", () => {
    expect(formatFieldValue("DECIMAL", "unknown", 199.73)).toBe("199.73");
  });

  it("falls back to formatNumber for DECIMAL with undefined format", () => {
    expect(formatFieldValue("DECIMAL", undefined, 199.73)).toBe("199.73");
  });

  it("uses formatDisplay for STRING type", () => {
    expect(formatFieldValue("STRING", undefined, "Acme")).toBe("Acme");
  });

  it("returns em-dash for null/undefined/empty value", () => {
    expect(formatFieldValue("STRING", undefined, null)).toBe("—");
    expect(formatFieldValue("STRING", undefined, undefined)).toBe("—");
    expect(formatFieldValue("STRING", undefined, "")).toBe("—");
  });
});
