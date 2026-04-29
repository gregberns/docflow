import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDocument, getDocumentFileUrl } from "../api/documents";
import { getWorkflow } from "../api/workflows";
import { DetailLayout } from "../components/DetailLayout";
import { PdfViewer } from "../components/PdfViewer";
import { DocumentHeader } from "../components/DocumentHeader";
import { FormPanel } from "../components/FormPanel";
import { StageProgress } from "../components/StageProgress";
import { useDocumentActions } from "../hooks/useDocumentActions";
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

  const orgId = data?.organizationId;
  const docTypeId = data?.detectedDocumentType;
  const { data: workflow } = useQuery({
    queryKey: ["workflow", orgId, docTypeId],
    queryFn: () => getWorkflow(orgId ?? "", docTypeId ?? ""),
    enabled: typeof orgId === "string" && orgId.length > 0 && typeof docTypeId === "string",
  });

  const actions = useDocumentActions({
    documentId: documentId ?? "",
    organizationId: orgId ?? "",
  });

  const fields = useMemo(() => (data ? deriveFallbackFields(data.extractedFields) : []), [data]);

  const docTypeOptions = useMemo(
    () => (data?.detectedDocumentType ? [data.detectedDocumentType] : []),
    [data?.detectedDocumentType],
  );

  return (
    <main data-testid="document-detail-page" data-document-id={documentId}>
      {isLoading && (
        <p
          data-testid="document-loading"
          className="mx-auto mt-8 max-w-md rounded-lg border border-neutral-200 bg-card px-6 py-4 text-center text-13 text-neutral-500"
        >
          Loading document…
        </p>
      )}
      {isError && (
        <p
          data-testid="document-error"
          role="alert"
          className="mx-auto mt-8 max-w-md rounded-lg border border-danger-soft bg-stage-rejected-bg px-6 py-4 text-center text-13 text-danger"
        >
          {error instanceof Error ? error.message : "Unable to load document."}
        </p>
      )}
      {data && documentId && (
        <>
          <DocumentHeader document={data} />
          {workflow &&
            (data.currentStageId === null ? (
              <StageProgress mode="in-flight" currentStep="EXTRACTING" stages={workflow.stages} />
            ) : (
              <StageProgress
                mode="processed"
                stages={workflow.stages}
                currentStageId={data.currentStageId}
                currentStatus={data.currentStatus}
                originStage={data.workflowOriginStage}
              />
            ))}
          <DetailLayout
            left={<PdfViewer fileUrl={getDocumentFileUrl(documentId)} />}
            right={
              <FormPanel
                document={data}
                fields={fields}
                docTypeOptions={docTypeOptions}
                isSubmitting={actions.approve.isPending}
                handlers={{
                  onApprove: () => actions.approve.mutate(),
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
