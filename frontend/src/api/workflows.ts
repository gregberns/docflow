import { API_BASE, fetchJson } from "./client";
import type { DocumentView, RetypeAccepted } from "../types/readModels";
import type { ReviewFieldsPatch, RetypeRequest, WorkflowAction } from "../types/workflow";

const JSON_HEADERS: Record<string, string> = { "Content-Type": "application/json" };

export function applyAction(documentId: string, action: WorkflowAction): Promise<DocumentView> {
  return fetchJson<DocumentView>(
    `${API_BASE}/documents/${encodeURIComponent(documentId)}/actions`,
    {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify(action),
    },
  );
}

export function patchReviewFields(
  documentId: string,
  patch: ReviewFieldsPatch,
): Promise<DocumentView> {
  return fetchJson<DocumentView>(
    `${API_BASE}/documents/${encodeURIComponent(documentId)}/review/fields`,
    {
      method: "PATCH",
      headers: JSON_HEADERS,
      body: JSON.stringify(patch),
    },
  );
}

export function retypeDocument(
  documentId: string,
  request: RetypeRequest,
): Promise<RetypeAccepted> {
  return fetchJson<RetypeAccepted>(
    `${API_BASE}/documents/${encodeURIComponent(documentId)}/review/retype`,
    {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify(request),
    },
  );
}
