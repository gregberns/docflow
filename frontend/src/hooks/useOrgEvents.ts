import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";

export interface UseOrgEventsOptions {
  documentId?: string;
}

interface DocumentStateChangedPayload {
  documentId?: string;
}

function parseDocumentId(raw: string): string | null {
  try {
    const parsed = JSON.parse(raw) as DocumentStateChangedPayload;
    if (typeof parsed.documentId === "string" && parsed.documentId.length > 0) {
      return parsed.documentId;
    }
  } catch {
    return null;
  }
  return null;
}

export function useOrgEvents(orgId: string | undefined, options: UseOrgEventsOptions = {}): void {
  const queryClient = useQueryClient();
  const { documentId } = options;

  useEffect(() => {
    if (!orgId) {
      return;
    }
    const source = new EventSource(`/api/organizations/${encodeURIComponent(orgId)}/stream`);

    const refetchDashboard = () => {
      void queryClient.invalidateQueries({ queryKey: ["dashboard", orgId] });
    };

    const onProcessingStepChanged = () => {
      refetchDashboard();
    };

    const onDocumentStateChanged = (event: MessageEvent<string>) => {
      refetchDashboard();
      const id = parseDocumentId(event.data);
      if (id) {
        void queryClient.invalidateQueries({ queryKey: ["document", id] });
      } else if (documentId) {
        void queryClient.invalidateQueries({ queryKey: ["document", documentId] });
      }
    };

    source.addEventListener("ProcessingStepChanged", onProcessingStepChanged);
    source.addEventListener("DocumentStateChanged", onDocumentStateChanged as EventListener);

    return () => {
      source.removeEventListener("ProcessingStepChanged", onProcessingStepChanged);
      source.removeEventListener("DocumentStateChanged", onDocumentStateChanged as EventListener);
      source.close();
    };
  }, [orgId, documentId, queryClient]);
}
