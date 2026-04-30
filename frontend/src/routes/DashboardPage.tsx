import { useMemo, useRef, useState, type ChangeEvent } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDashboard } from "../api/dashboard";
import { getOrganization } from "../api/organizations";
import { DashboardStatsBar } from "../components/DashboardStatsBar";
import { DashboardFilterBar } from "../components/DashboardFilterBar";
import { ProcessingSection } from "../components/ProcessingSection";
import { DocumentsSection } from "../components/DocumentsSection";
import { useOrgEvents } from "../hooks/useOrgEvents";
import { useUploadDocument } from "../hooks/useUploadDocument";
import type { DocumentCursor, DocumentView } from "../types/readModels";

export function DashboardPage() {
  const { orgId } = useParams<{ orgId: string }>();
  const [stageFilter, setStageFilter] = useState<string | "ALL">("ALL");
  const [docTypeFilter, setDocTypeFilter] = useState<string | "ALL">("ALL");
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [extraPages, setExtraPages] = useState<DocumentView[]>([]);
  const [activeCursor, setActiveCursor] = useState<DocumentCursor | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dashboard", orgId],
    queryFn: () => {
      setExtraPages([]);
      setActiveCursor(null);
      setLoadMoreError(null);
      return getDashboard(orgId ?? "");
    },
    enabled: typeof orgId === "string" && orgId.length > 0,
  });

  const { data: orgDetail } = useQuery({
    queryKey: ["organization", orgId],
    queryFn: () => getOrganization(orgId ?? ""),
    enabled: typeof orgId === "string" && orgId.length > 0,
  });

  useOrgEvents(orgId);
  const upload = useUploadDocument(orgId);

  const allDocuments = useMemo<DocumentView[]>(() => {
    if (!data) {
      return [];
    }
    return [...data.documents, ...extraPages];
  }, [data, extraPages]);

  const nextCursor: DocumentCursor | null = activeCursor ?? data?.nextCursor ?? null;

  const stageOptions = useMemo<ReadonlyArray<string>>(() => {
    if (!orgDetail) {
      return [];
    }
    const seen = new Set<string>();
    for (const workflow of orgDetail.workflows) {
      for (const stage of workflow.stages) {
        seen.add(stage.displayName);
      }
    }
    return [...seen];
  }, [orgDetail]);

  const docTypeOptions = useMemo<ReadonlyArray<string>>(() => {
    const seen = new Set<string>();
    for (const doc of allDocuments) {
      if (doc.detectedDocumentType) {
        seen.add(doc.detectedDocumentType);
      }
    }
    return [...seen];
  }, [allDocuments]);

  const filteredDocuments = useMemo(() => {
    return allDocuments.filter((doc) => {
      if (stageFilter !== "ALL" && doc.currentStageDisplayName !== stageFilter) {
        return false;
      }
      if (docTypeFilter !== "ALL" && doc.detectedDocumentType !== docTypeFilter) {
        return false;
      }
      return true;
    });
  }, [allDocuments, stageFilter, docTypeFilter]);

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

  const onLoadMore = async () => {
    if (!orgId || !nextCursor || loadingMore) {
      return;
    }
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const next = await getDashboard(orgId, { cursor: nextCursor });
      setExtraPages((prev) => [...prev, ...next.documents]);
      setActiveCursor(next.nextCursor);
    } catch (err) {
      setLoadMoreError(err instanceof Error ? err.message : "Unable to load more documents.");
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <main
      data-testid="dashboard-page"
      data-org-id={orgId}
      className="mx-auto max-w-[1400px] px-8 py-6"
    >
      <header className="mb-5 flex items-center justify-between">
        <h1 className="text-22 font-bold text-brand-navy">Documents</h1>
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
        <p
          data-testid="upload-error"
          role="alert"
          className="mb-4 rounded-md border border-danger-soft bg-stage-rejected-bg px-4 py-2.5 text-13 text-danger"
        >
          {uploadError}
        </p>
      )}
      {data && (
        <>
          <DashboardStatsBar stats={data.stats} />
          <DashboardFilterBar
            stage={stageFilter}
            docType={docTypeFilter}
            stageOptions={stageOptions}
            docTypeOptions={docTypeOptions}
            onStageChange={setStageFilter}
            onDocTypeChange={setDocTypeFilter}
            onUploadClick={onUploadClick}
            uploadDisabled={upload.isPending || !orgId}
          />
          <ProcessingSection items={data.processing} />
          <DocumentsSection
            documents={filteredDocuments}
            hasMore={nextCursor !== null}
            loadingMore={loadingMore}
            onLoadMore={onLoadMore}
            loadMoreError={loadMoreError}
          />
        </>
      )}
    </main>
  );
}
