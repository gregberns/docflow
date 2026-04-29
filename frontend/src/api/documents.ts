import { API_BASE, fetchJson } from "./client";
import type { DocumentView, UploadAccepted } from "../types/readModels";

export function getDocument(documentId: string): Promise<DocumentView> {
  return fetchJson<DocumentView>(`${API_BASE}/documents/${encodeURIComponent(documentId)}`);
}

export function getDocumentFileUrl(documentId: string): string {
  return `${API_BASE}/documents/${encodeURIComponent(documentId)}/file`;
}

export async function uploadDocument(orgId: string, file: File): Promise<UploadAccepted> {
  const formData = new FormData();
  formData.append("file", file);
  return fetchJson<UploadAccepted>(
    `${API_BASE}/organizations/${encodeURIComponent(orgId)}/documents`,
    {
      method: "POST",
      body: formData,
    },
  );
}
