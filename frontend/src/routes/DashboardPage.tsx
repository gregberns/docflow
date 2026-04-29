import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDashboard } from "../api/dashboard";
import type { WorkflowStatus } from "../types/readModels";
import { DashboardStatsBar } from "../components/DashboardStatsBar";
import { DashboardFilterBar } from "../components/DashboardFilterBar";
import { ProcessingSection } from "../components/ProcessingSection";
import { DocumentsSection } from "../components/DocumentsSection";

export function DashboardPage() {
  const { orgId } = useParams<{ orgId: string }>();
  const [statusFilter, setStatusFilter] = useState<WorkflowStatus | "ALL">("ALL");
  const [docTypeFilter, setDocTypeFilter] = useState<string | "ALL">("ALL");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dashboard", orgId],
    queryFn: () => getDashboard(orgId ?? ""),
    enabled: typeof orgId === "string" && orgId.length > 0,
  });

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

  return (
    <main data-testid="dashboard-page" data-org-id={orgId}>
      <header>
        <h1>Documents</h1>
      </header>
      {isLoading && <p data-testid="dashboard-loading">Loading dashboard…</p>}
      {isError && (
        <p data-testid="dashboard-error" role="alert">
          {error instanceof Error ? error.message : "Unable to load dashboard."}
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
          />
          <ProcessingSection items={data.processing} />
          <DocumentsSection documents={filteredDocuments} />
        </>
      )}
    </main>
  );
}
