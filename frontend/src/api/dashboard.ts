import { API_BASE, fetchJson } from "./client";
import type { DashboardResponse, WorkflowStatus } from "../types/readModels";

export interface DashboardQuery {
  status?: WorkflowStatus;
  docType?: string;
}

export function getDashboard(
  orgId: string,
  query: DashboardQuery = {},
): Promise<DashboardResponse> {
  const params = new URLSearchParams();
  if (query.status) {
    params.set("status", query.status);
  }
  if (query.docType) {
    params.set("docType", query.docType);
  }
  const suffix = params.toString();
  const path = `${API_BASE}/organizations/${encodeURIComponent(orgId)}/documents`;
  return fetchJson<DashboardResponse>(suffix.length > 0 ? `${path}?${suffix}` : path);
}
