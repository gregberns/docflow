import { useMemo, useRef, useState, type ChangeEvent } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDashboard } from "../api/dashboard";
import type { WorkflowStatus } from "../types/readModels";
import { DashboardStatsBar } from "../components/DashboardStatsBar";
import { DashboardFilterBar } from "../components/DashboardFilterBar";
import { ProcessingSection } from "../components/ProcessingSection";
import { DocumentsSection } from "../components/DocumentsSection";
import { useOrgEvents } from "../hooks/useOrgEvents";
import { useUploadDocument } from "../hooks/useUploadDocument";

export function DashboardPage() {
  const { orgId } = useParams<{ orgId: string }>();
  const [statusFilter, setStatusFilter] = useState<WorkflowStatus | "ALL">("ALL");
  const [docTypeFilter, setDocTypeFilter] = useState<string | "ALL">("ALL");
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dashboard", orgId],
    queryFn: () => getDashboard(orgId ?? ""),
    enabled: typeof orgId === "string" && orgId.length > 0,
  });

  useOrgEvents(orgId);
  const upload = useUploadDocument(orgId);

  const statusOptions = useMemo<ReadonlyArray<WorkflowStatus>>(() => {
    if (!data) {
      return [];
    }
    const seen = new Set<WorkflowStatus>();
    for (const doc of data.documents) {
      if (doc.currentStatus) {
        seen.add(doc.currentStatus);
      }
    }
    return [...seen];
  }, [data]);

  const docTypeOptions = useMemo<ReadonlyArray<string>>(() => {
    if (!data) {
      return [];
    }
    const seen = new Set<string>();
    for (const doc of data.documents) {
      if (doc.detectedDocumentType) {
        seen.add(doc.detectedDocumentType);
      }
    }
    return [...seen];
  }, [data]);

  const filteredDocuments = useMemo(() => {
    if (!data) {
      return [];
    }
    return data.documents.filter((doc) => {
      if (statusFilter !== "ALL" && doc.currentStatus !== statusFilter) {
        return false;
      }
      if (docTypeFilter !== "ALL" && doc.detectedDocumentType !== docTypeFilter) {
        return false;
      }
      return true;
    });
  }, [data, statusFilter, docTypeFilter]);

  const onUploadClick = () => {
    setUploadError(null);
    fileInputRef.current?.click();
  };

  const onFileSelected = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !orgId) {
      return;
    }
    upload.mutate(
      { file },
      {
        onError: (mutationError) => {
          setUploadError(mutationError instanceof Error ? mutationError.message : "Upload failed");
        },
      },
    );
  };

  return (
    <main data-testid="dashboard-page" data-org-id={orgId}>
      <header>
        <h1>Documents</h1>
      </header>
      {isLoading && (
        <p
          data-testid="dashboard-loading"
          className="mx-auto mt-8 max-w-md rounded-lg border border-neutral-200 bg-card px-6 py-4 text-center text-13 text-neutral-500"
        >
          Loading dashboard…
        </p>
      )}
      {isError && (
        <p
          data-testid="dashboard-error"
          role="alert"
          className="mx-auto mt-8 max-w-md rounded-lg border border-danger-soft bg-stage-rejected-bg px-6 py-4 text-center text-13 text-danger"
        >
          {error instanceof Error ? error.message : "Unable to load dashboard."}
        </p>
      )}
      <input
        ref={fileInputRef}
        type="file"
        data-testid="upload-file-input"
        accept="application/pdf,image/*"
        hidden
        onChange={onFileSelected}
      />
      {uploadError && (
        <p data-testid="upload-error" role="alert">
          {uploadError}
        </p>
      )}
      {data && (
        <>
          <DashboardStatsBar stats={data.stats} />
          <DashboardFilterBar
            status={statusFilter}
            docType={docTypeFilter}
            statusOptions={statusOptions}
            docTypeOptions={docTypeOptions}
            onStatusChange={setStatusFilter}
            onDocTypeChange={setDocTypeFilter}
            onUploadClick={onUploadClick}
            uploadDisabled={upload.isPending || !orgId}
          />
          <ProcessingSection items={data.processing} />
          <DocumentsSection documents={filteredDocuments} />
        </>
      )}
    </main>
  );
}
