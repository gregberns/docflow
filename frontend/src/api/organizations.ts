import { API_BASE, fetchJson } from "./client";
import type { OrganizationDetail, OrganizationListItem } from "../types/readModels";

export function listOrganizations(): Promise<OrganizationListItem[]> {
  return fetchJson<OrganizationListItem[]>(`${API_BASE}/organizations`);
}

export function getOrganization(orgId: string): Promise<OrganizationDetail> {
  return fetchJson<OrganizationDetail>(`${API_BASE}/organizations/${encodeURIComponent(orgId)}`);
}
