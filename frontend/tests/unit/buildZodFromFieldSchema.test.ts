import { describe, expect, it } from "vitest";
import { buildZodFromFieldSchema } from "../../src/schemas/buildZodFromFieldSchema";

describe("buildZodFromFieldSchema", () => {
  it("validates a required STRING field and rejects empty strings", () => {
    const schema = buildZodFromFieldSchema([
      { name: "caseNumber", type: "STRING", required: true, enumValues: null, itemFields: null },
    ]);
    expect(schema.safeParse({ caseNumber: "ABC-1" }).success).toBe(true);
    const failed = schema.safeParse({ caseNumber: "" });
    expect(failed.success).toBe(false);
  });

  it("allows an optional STRING to be empty or missing", () => {
    const schema = buildZodFromFieldSchema([
      { name: "note", type: "STRING", required: false, enumValues: null, itemFields: null },
    ]);
    expect(schema.safeParse({ note: "" }).success).toBe(true);
    expect(schema.safeParse({ note: "hello" }).success).toBe(true);
  });

  it("validates DATE in ISO format and rejects malformed input", () => {
    const schema = buildZodFromFieldSchema([
      { name: "filingDate", type: "DATE", required: true, enumValues: null, itemFields: null },
    ]);
    expect(schema.safeParse({ filingDate: "2026-04-01" }).success).toBe(true);
    expect(schema.safeParse({ filingDate: "04/01/2026" }).success).toBe(false);
    expect(schema.safeParse({ filingDate: "" }).success).toBe(false);
  });

  it("DECIMAL preprocess accepts both '1,234.56' and '1234.56' and parses to a number", () => {
    const schema = buildZodFromFieldSchema([
      { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
    ]);
    const withComma = schema.safeParse({ amount: "1,234.56" });
    const withoutComma = schema.safeParse({ amount: "1234.56" });
    expect(withComma.success).toBe(true);
    expect(withoutComma.success).toBe(true);
    if (withComma.success) {
      expect((withComma.data as { amount: number }).amount).toBe(1234.56);
    }
    if (withoutComma.success) {
      expect((withoutComma.data as { amount: number }).amount).toBe(1234.56);
    }
  });

  it("DECIMAL accepts a number passthrough and rejects non-numeric strings", () => {
    const schema = buildZodFromFieldSchema([
      { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
    ]);
    expect(schema.safeParse({ amount: 42 }).success).toBe(true);
    expect(schema.safeParse({ amount: "not-a-number" }).success).toBe(false);
  });

  it("optional DECIMAL allows empty string and undefined", () => {
    const schema = buildZodFromFieldSchema([
      { name: "amount", type: "DECIMAL", required: false, enumValues: null, itemFields: null },
    ]);
    expect(schema.safeParse({ amount: "" }).success).toBe(true);
    expect(schema.safeParse({}).success).toBe(true);
    expect(schema.safeParse({ amount: "9,876.5" }).success).toBe(true);
  });

  it("ENUM accepts only listed values when required", () => {
    const schema = buildZodFromFieldSchema([
      {
        name: "category",
        type: "ENUM",
        required: true,
        enumValues: ["MEAL", "FUEL", "SUPPLIES"],
        itemFields: null,
      },
    ]);
    expect(schema.safeParse({ category: "MEAL" }).success).toBe(true);
    expect(schema.safeParse({ category: "OTHER" }).success).toBe(false);
  });

  it("optional ENUM permits empty string", () => {
    const schema = buildZodFromFieldSchema([
      {
        name: "category",
        type: "ENUM",
        required: false,
        enumValues: ["A", "B"],
        itemFields: null,
      },
    ]);
    expect(schema.safeParse({ category: "" }).success).toBe(true);
    expect(schema.safeParse({ category: "A" }).success).toBe(true);
  });

  it("ARRAY validates each row against its itemFields", () => {
    const schema = buildZodFromFieldSchema([
      {
        name: "lineItems",
        type: "ARRAY",
        required: true,
        enumValues: null,
        itemFields: [
          { name: "label", type: "STRING", required: true, enumValues: null, itemFields: null },
          { name: "amount", type: "DECIMAL", required: true, enumValues: null, itemFields: null },
        ],
      },
    ]);
    const ok = schema.safeParse({
      lineItems: [
        { label: "Filing fee", amount: "1,234.56" },
        { label: "Service", amount: "50" },
      ],
    });
    expect(ok.success).toBe(true);

    const badRow = schema.safeParse({
      lineItems: [{ label: "", amount: "12" }],
    });
    expect(badRow.success).toBe(false);

    const empty = schema.safeParse({ lineItems: [] });
    expect(empty.success).toBe(false);
  });

  it("optional ARRAY tolerates missing values", () => {
    const schema = buildZodFromFieldSchema([
      {
        name: "lineItems",
        type: "ARRAY",
        required: false,
        enumValues: null,
        itemFields: [
          { name: "label", type: "STRING", required: true, enumValues: null, itemFields: null },
        ],
      },
    ]);
    expect(schema.safeParse({}).success).toBe(true);
    expect(schema.safeParse({ lineItems: [] }).success).toBe(true);
  });
});
