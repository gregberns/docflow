import { API_BASE, fetchJson } from "./client";
import type { DashboardResponse, DocumentCursor, WorkflowStatus } from "../types/readModels";

export interface DashboardQuery {
  status?: WorkflowStatus;
  stage?: string;
  docType?: string;
  cursor?: DocumentCursor;
}

export function getDashboard(
  orgId: string,
  query: DashboardQuery = {},
): Promise<DashboardResponse> {
  const params = new URLSearchParams();
  if (query.status) {
    params.set("status", query.status);
  }
  if (query.stage) {
    params.set("stage", query.stage);
  }
  if (query.docType) {
    params.set("docType", query.docType);
  }
  if (query.cursor) {
    params.set("cursorUpdatedAt", query.cursor.updatedAt);
    params.set("cursorId", query.cursor.id);
  }
  const suffix = params.toString();
  const path = `${API_BASE}/organizations/${encodeURIComponent(orgId)}/documents`;
  return fetchJson<DashboardResponse>(suffix.length > 0 ? `${path}?${suffix}` : path);
}
