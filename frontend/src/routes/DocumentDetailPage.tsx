import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDocument, getDocumentFileUrl } from "../api/documents";
import { DetailLayout } from "../components/DetailLayout";
import { PdfViewer } from "../components/PdfViewer";
import { DocumentHeader } from "../components/DocumentHeader";
import { useOrgEvents } from "../hooks/useOrgEvents";

export function DocumentDetailPage() {
  const { documentId } = useParams<{ documentId: string }>();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId ?? ""),
    enabled: typeof documentId === "string" && documentId.length > 0,
  });

  useOrgEvents(data?.organizationId, documentId ? { documentId } : undefined);

  return (
    <main data-testid="document-detail-page" data-document-id={documentId}>
      {isLoading && <p data-testid="document-loading">Loading document…</p>}
      {isError && (
        <p data-testid="document-error" role="alert">
          {error instanceof Error ? error.message : "Unable to load document."}
        </p>
      )}
      {data && documentId && (
        <>
          <DocumentHeader document={data} />
          <DetailLayout
            left={<PdfViewer fileUrl={getDocumentFileUrl(documentId)} />}
            right={
              <div data-testid="form-panel-placeholder">Form panel coming soon (df-6m8.8).</div>
            }
          />
        </>
      )}
    </main>
  );
}
