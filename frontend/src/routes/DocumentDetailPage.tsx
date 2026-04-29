import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDocument, getDocumentFileUrl } from "../api/documents";
import { DetailLayout } from "../components/DetailLayout";
import { PdfViewer } from "../components/PdfViewer";
import { DocumentHeader } from "../components/DocumentHeader";
import { FormPanel } from "../components/FormPanel";
import { useOrgEvents } from "../hooks/useOrgEvents";
import type { FieldSchema } from "../types/schema";

function deriveFallbackFields(extractedFields: Record<string, unknown>): FieldSchema[] {
  return Object.keys(extractedFields).map((name) => {
    const value = extractedFields[name];
    if (Array.isArray(value)) {
      const sample = (value[0] ?? {}) as Record<string, unknown>;
      const itemFields: FieldSchema[] = Object.keys(sample).map((childName) => ({
        name: childName,
        type: "STRING",
        required: false,
        enumValues: null,
        itemFields: null,
      }));
      return {
        name,
        type: "ARRAY",
        required: false,
        enumValues: null,
        itemFields,
      };
    }
    return {
      name,
      type: "STRING",
      required: false,
      enumValues: null,
      itemFields: null,
    };
  });
}

export function DocumentDetailPage() {
  const { documentId } = useParams<{ documentId: string }>();
  const navigate = useNavigate();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId ?? ""),
    enabled: typeof documentId === "string" && documentId.length > 0,
  });

  useOrgEvents(data?.organizationId, documentId ? { documentId } : undefined);

  const fields = useMemo(() => (data ? deriveFallbackFields(data.extractedFields) : []), [data]);

  const docTypeOptions = useMemo(
    () => (data?.detectedDocumentType ? [data.detectedDocumentType] : []),
    [data?.detectedDocumentType],
  );

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
              <FormPanel
                document={data}
                fields={fields}
                docTypeOptions={docTypeOptions}
                handlers={{
                  onBackToDocuments: () =>
                    navigate(`/org/${encodeURIComponent(data.organizationId)}/dashboard`),
                }}
              />
            }
          />
        </>
      )}
    </main>
  );
}
