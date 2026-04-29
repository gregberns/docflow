import { z } from "zod";
import type { FieldSchema } from "../types/schema";

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function buildScalar(field: FieldSchema): z.ZodTypeAny {
  const upper = field.type.toUpperCase();

  if (upper === "STRING") {
    let schema: z.ZodTypeAny = z.string();
    if (field.required) {
      schema = (schema as z.ZodString).min(1, { message: `${field.name} is required` });
    } else {
      schema = (schema as z.ZodString).optional().or(z.literal(""));
    }
    return schema;
  }

  if (upper === "DATE") {
    let schema: z.ZodTypeAny = z
      .string()
      .regex(ISO_DATE_RE, { message: `${field.name} must be an ISO date (YYYY-MM-DD)` });
    if (!field.required) {
      schema = z
        .string()
        .optional()
        .refine((value) => value === undefined || value === "" || ISO_DATE_RE.test(value), {
          message: `${field.name} must be an ISO date (YYYY-MM-DD)`,
        });
    }
    return schema;
  }

  if (upper === "DECIMAL") {
    const inner = z
      .number({ message: `${field.name} must be a number` })
      .refine((n) => Number.isFinite(n), { message: `${field.name} must be a number` });
    const required = z.preprocess((value) => {
      if (value === null || value === undefined) return undefined;
      if (typeof value === "number") return value;
      if (typeof value === "string") {
        const trimmed = value.trim();
        if (trimmed === "") return undefined;
        const stripped = trimmed.replace(/,/g, "");
        const parsed = Number(stripped);
        return Number.isNaN(parsed) ? value : parsed;
      }
      return value;
    }, inner);

    if (field.required) {
      return required;
    }
    return z.preprocess((value) => {
      if (value === null || value === undefined) return undefined;
      if (typeof value === "string" && value.trim() === "") return undefined;
      if (typeof value === "string") {
        const stripped = value.trim().replace(/,/g, "");
        const parsed = Number(stripped);
        return Number.isNaN(parsed) ? value : parsed;
      }
      return value;
    }, inner.optional());
  }

  if (upper === "ENUM") {
    const values = field.enumValues ?? [];
    if (values.length === 0) {
      // No enum values configured: fall back to string.
      return field.required ? z.string().min(1) : z.string().optional();
    }
    const enumSchema = z.enum(values as [string, ...string[]]);
    if (field.required) {
      return enumSchema;
    }
    return enumSchema.optional().or(z.literal(""));
  }

  // Fallback for unknown types: permissive string.
  return field.required ? z.string().min(1) : z.string().optional();
}

function buildArray(field: FieldSchema): z.ZodTypeAny {
  const itemFields = field.itemFields ?? [];
  const itemShape: Record<string, z.ZodTypeAny> = {};
  for (const child of itemFields) {
    itemShape[child.name] = buildField(child);
  }
  const itemSchema = z.object(itemShape);
  let arr: z.ZodTypeAny = z.array(itemSchema);
  if (field.required) {
    arr = z.array(itemSchema).min(1, { message: `${field.name} requires at least one row` });
  } else {
    arr = z.array(itemSchema).optional();
  }
  return arr;
}

export function buildField(field: FieldSchema): z.ZodTypeAny {
  const upper = field.type.toUpperCase();
  if (upper === "ARRAY") {
    return buildArray(field);
  }
  return buildScalar(field);
}

export function buildZodFromFieldSchema(fields: FieldSchema[]): z.ZodObject<z.ZodRawShape> {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const field of fields) {
    shape[field.name] = buildField(field);
  }
  return z.object(shape);
}
