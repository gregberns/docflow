import type { ProblemDetail, ProblemDetailFieldError } from "../types/schema";

export interface DocflowApiErrorPayload {
  code: string;
  message: string;
  details: ProblemDetailFieldError[];
  status: number;
}

export class DocflowApiError extends Error {
  override readonly name = "DocflowApiError";
  readonly code: string;
  readonly details: ProblemDetailFieldError[];
  readonly status: number;

  constructor(payload: DocflowApiErrorPayload) {
    super(payload.message);
    this.code = payload.code;
    this.details = payload.details;
    this.status = payload.status;
  }
}

const DEFAULT_HEADERS: Record<string, string> = {
  Accept: "application/json, application/problem+json",
};

function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.code === "string" ||
    typeof candidate.message === "string" ||
    typeof candidate.title === "string"
  );
}

function toFieldErrors(value: unknown): ProblemDetailFieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const out: ProblemDetailFieldError[] = [];
  for (const entry of value) {
    if (
      typeof entry === "object" &&
      entry !== null &&
      typeof (entry as Record<string, unknown>).path === "string" &&
      typeof (entry as Record<string, unknown>).message === "string"
    ) {
      const e = entry as Record<string, unknown>;
      out.push({ path: e.path as string, message: e.message as string });
    }
  }
  return out;
}

async function decodeProblem(response: Response): Promise<DocflowApiErrorPayload> {
  let parsed: unknown = null;
  try {
    parsed = await response.json();
  } catch {
    parsed = null;
  }
  if (!isProblemDetail(parsed)) {
    return {
      code: "unknown",
      message: response.statusText || `HTTP ${response.status}`,
      details: [],
      status: response.status,
    };
  }
  const problem = parsed;
  const code = typeof problem.code === "string" ? problem.code : "unknown";
  const message =
    typeof problem.message === "string" && problem.message.length > 0
      ? problem.message
      : (problem.title ?? response.statusText ?? `HTTP ${response.status}`);
  const details = toFieldErrors(problem.details);
  return {
    code,
    message,
    details,
    status: typeof problem.status === "number" ? problem.status : response.status,
  };
}

export interface FetchJsonOptions extends Omit<RequestInit, "headers"> {
  headers?: Record<string, string>;
}

export async function fetchJson<T>(url: string, init: FetchJsonOptions = {}): Promise<T> {
  const { headers, ...rest } = init;
  const merged: Record<string, string> = { ...DEFAULT_HEADERS, ...(headers ?? {}) };
  const response = await fetch(url, { ...rest, headers: merged });
  if (!response.ok) {
    const payload = await decodeProblem(response);
    throw new DocflowApiError(payload);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (text.length === 0) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export const API_BASE = "/api";
