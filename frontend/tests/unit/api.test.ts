import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "../msw/server";
import { fixtures } from "../msw/handlers";
import { DocflowApiError, fetchJson } from "../../src/api/client";
import { getOrganization, listOrganizations } from "../../src/api/organizations";
import { getDashboard } from "../../src/api/dashboard";
import { getDocument, uploadDocument } from "../../src/api/documents";
import { applyAction, patchReviewFields, retypeDocument } from "../../src/api/workflows";

describe("API client", () => {
  it("listOrganizations returns the canonical happy-path fixture", async () => {
    const result = await listOrganizations();
    expect(result).toEqual(fixtures.organizations);
  });

  it("getOrganization returns the detail fixture", async () => {
    const result = await getOrganization("pinnacle-legal");
    expect(result.id).toBe("pinnacle-legal");
    expect(result.workflows).toHaveLength(1);
    expect(result.fieldSchemas["court-filing"]).toHaveLength(2);
  });

  it("getDashboard appends status + docType filters as query params", async () => {
    let observedUrl = "";
    server.use(
      http.get("/api/organizations/:orgId/documents", ({ request }) => {
        observedUrl = request.url;
        return HttpResponse.json(fixtures.dashboard);
      }),
    );
    const result = await getDashboard("pinnacle-legal", {
      status: "AWAITING_REVIEW",
      docType: "court-filing",
    });
    expect(result).toEqual(fixtures.dashboard);
    expect(observedUrl).toContain("status=AWAITING_REVIEW");
    expect(observedUrl).toContain("docType=court-filing");
  });

  it("getDocument returns the document fixture", async () => {
    const result = await getDocument(fixtures.document.documentId);
    expect(result.documentId).toBe(fixtures.document.documentId);
    expect(result.currentStatus).toBe("AWAITING_REVIEW");
  });

  it("uploadDocument posts multipart form-data and returns ids", async () => {
    const file = new File([new Uint8Array([1, 2, 3])], "test.pdf", { type: "application/pdf" });
    const result = await uploadDocument("pinnacle-legal", file);
    expect(result).toEqual(fixtures.upload);
  });

  it("applyAction posts the workflow action body", async () => {
    let observedBody: unknown = null;
    server.use(
      http.post("/api/documents/:documentId/actions", async ({ request }) => {
        observedBody = await request.json();
        return HttpResponse.json(fixtures.document);
      }),
    );
    await applyAction(fixtures.document.documentId, { action: "Flag", comment: "blurry" });
    expect(observedBody).toEqual({ action: "Flag", comment: "blurry" });
  });

  it("patchReviewFields sends a PATCH with extractedFields", async () => {
    let observedMethod = "";
    server.use(
      http.patch("/api/documents/:documentId/review/fields", ({ request }) => {
        observedMethod = request.method;
        return HttpResponse.json(fixtures.document);
      }),
    );
    const result = await patchReviewFields(fixtures.document.documentId, {
      extractedFields: { caseNumber: "2026-CV-99999" },
    });
    expect(observedMethod).toBe("PATCH");
    expect(result).toEqual(fixtures.document);
  });

  it("retypeDocument returns the RetypeAccepted fixture", async () => {
    const result = await retypeDocument(fixtures.document.documentId, {
      newDocumentType: "client-intake",
    });
    expect(result).toEqual(fixtures.retype);
  });

  it("decodes RFC 7807 ProblemDetail responses into DocflowApiError", async () => {
    server.use(
      http.get("/api/organizations", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Bad Request",
            status: 400,
            code: "VALIDATION_FAILED",
            message: "Request body validation failed",
            details: [{ path: "name", message: "must not be blank" }],
          },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    await expect(listOrganizations()).rejects.toMatchObject({
      name: "DocflowApiError",
      code: "VALIDATION_FAILED",
      status: 400,
      message: "Request body validation failed",
      details: [{ path: "name", message: "must not be blank" }],
    });
  });

  it("falls back to a synthetic ProblemDetail when the body is not JSON", async () => {
    server.use(
      http.get("/api/organizations", () =>
        HttpResponse.text("not json", {
          status: 503,
          statusText: "Service Unavailable",
        }),
      ),
    );
    let captured: unknown;
    try {
      await listOrganizations();
    } catch (e) {
      captured = e;
    }
    expect(captured).toBeInstanceOf(DocflowApiError);
    const err = captured as DocflowApiError;
    expect(err.code).toBe("unknown");
    expect(err.status).toBe(503);
    expect(err.details).toEqual([]);
  });

  it("returns undefined for 204 No Content", async () => {
    server.use(http.get("/api/empty", () => new HttpResponse(null, { status: 204 })));
    const result = await fetchJson<void>("/api/empty");
    expect(result).toBeUndefined();
  });
});
