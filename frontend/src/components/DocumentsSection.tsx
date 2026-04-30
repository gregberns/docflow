import { useNavigate } from "react-router-dom";
import type { DocumentView, WorkflowStatus } from "../types/readModels";
import { ChevronRightIcon } from "./icons/Icons";
import { docDisplayId, docSubtitle } from "../util/formatters";

interface DocumentsSectionProps {
  documents: DocumentView[];
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
  loadMoreError?: string | null;
}

type StageColorKey =
  | "AWAITING_REVIEW"
  | "AWAITING_APPROVAL"
  | "FILED"
  | "REJECTED"
  | "FLAGGED"
  | null;

const STAGE_BADGE_CLASSES: Record<NonNullable<StageColorKey>, string> = {
  AWAITING_REVIEW: "bg-stage-review-bg text-stage-review-fg",
  AWAITING_APPROVAL: "bg-stage-approval-bg text-stage-approval-fg",
  FILED: "bg-stage-filed-bg text-stage-filed-fg",
  REJECTED: "bg-stage-rejected-bg text-stage-rejected-fg",
  FLAGGED: "bg-stage-review-bg text-stage-review-fg",
};

const NEUTRAL_BADGE = "bg-stage-type-neutral-bg text-stage-type-neutral-fg";

const BADGE_BASE =
  "inline-flex items-center rounded-sm px-2 py-0.5 text-11 font-semibold tracking-[0.3px]";

function StageBadge({
  status,
  label,
  flagged,
}: {
  status: WorkflowStatus | null;
  label: string;
  flagged: boolean;
}) {
  const colorKey: NonNullable<StageColorKey> = status ?? "AWAITING_REVIEW";
  const variant = STAGE_BADGE_CLASSES[colorKey] ?? NEUTRAL_BADGE;
  return (
    <div className="flex flex-col items-start gap-1">
      <span data-testid="badge-stage" className={`${BADGE_BASE} ${variant}`}>
        {label}
      </span>
      {flagged && (
        <span
          data-testid="badge-flagged"
          className="flex items-center gap-1 text-11 font-semibold text-stage-flagged-fg"
        >
          <span className="inline-block h-1.5 w-1.5 rounded-full bg-stage-flagged-fg" />
          Flagged
        </span>
      )}
    </div>
  );
}

function TypeBadge({ value }: { value: string | null }) {
  if (!value) {
    return <span className="text-neutral-400">—</span>;
  }
  return (
    <span data-testid="badge-type" className={`${BADGE_BASE} ${NEUTRAL_BADGE}`}>
      {value}
    </span>
  );
}

export function DocumentsSection({
  documents,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  loadMoreError = null,
}: DocumentsSectionProps) {
  const navigate = useNavigate();
  return (
    <section
      data-testid="documents-section"
      className="overflow-hidden rounded-lg border border-neutral-200 bg-card"
    >
      <h2 className="border-b border-neutral-200 bg-table-head px-4 py-2.5 text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
        Documents
      </h2>
      {documents.length === 0 ? (
        <p
          data-testid="documents-empty"
          className="mx-auto my-6 max-w-md rounded-lg border border-dashed border-neutral-200 bg-card px-6 py-8 text-center text-13 text-neutral-500"
        >
          No documents match the current filters.
        </p>
      ) : (
        <>
          <table className="w-full border-collapse">
            <thead className="bg-table-head">
              <tr>
                <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
                  Document
                </th>
                <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
                  Type
                </th>
                <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
                  Stage
                </th>
                <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
                  Updated
                </th>
                <th className="w-8 border-b border-neutral-200 px-4 py-2.5" aria-hidden="true" />
              </tr>
            </thead>
            <tbody>
              {documents.map((doc) => {
                const primaryId = docDisplayId(
                  doc.detectedDocumentType,
                  doc.extractedFields,
                  doc.sourceFilename,
                );
                const subtitle = docSubtitle(doc.detectedDocumentType, doc.extractedFields);
                const isFlagged = doc.currentStatus === "FLAGGED";
                const stageStatus = isFlagged ? "AWAITING_REVIEW" : doc.currentStatus;
                return (
                  <tr
                    key={doc.documentId}
                    data-testid="document-row"
                    data-document-id={doc.documentId}
                    data-status={doc.currentStatus ?? ""}
                    data-doc-type={doc.detectedDocumentType ?? ""}
                    onClick={() => navigate(`/documents/${doc.documentId}`)}
                    className="cursor-pointer transition-colors hover:bg-table-head [&>td]:border-b [&>td]:border-neutral-100 last:[&>td]:border-b-0"
                  >
                    <td className="px-4 py-3 text-13 align-middle">
                      <div className="font-semibold text-brand-navy">{primaryId}</div>
                      {subtitle && (
                        <div className="mt-0.5 text-12 text-neutral-500">{subtitle}</div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-13 align-middle">
                      <TypeBadge value={doc.detectedDocumentType} />
                    </td>
                    <td className="px-4 py-3 text-13 align-middle">
                      {doc.currentStageDisplayName ? (
                        <StageBadge
                          status={stageStatus}
                          label={doc.currentStageDisplayName}
                          flagged={isFlagged}
                        />
                      ) : (
                        <span className="text-neutral-400">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-12 text-neutral-500 align-middle">
                      {doc.processedAt ?? doc.uploadedAt}
                    </td>
                    <td
                      className="px-4 py-3 text-neutral-300 align-middle group-hover:text-brand-blue"
                      aria-hidden="true"
                    >
                      <ChevronRightIcon />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="flex items-center justify-between gap-3 border-t border-neutral-200 bg-table-head px-4 py-3">
            <span className="text-12 text-neutral-500">
              Showing {documents.length} document{documents.length === 1 ? "" : "s"}
            </span>
            {hasMore && onLoadMore && (
              <button
                type="button"
                data-testid="documents-load-more"
                onClick={onLoadMore}
                disabled={loadingMore}
                className="rounded-md border border-neutral-200 bg-card px-3 py-1.5 text-12 font-semibold text-brand-navy transition-colors hover:bg-neutral-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {loadingMore ? "Loading…" : "Load more"}
              </button>
            )}
          </div>
          {loadMoreError && (
            <p
              data-testid="documents-load-more-error"
              role="alert"
              className="border-t border-danger-soft bg-stage-rejected-bg px-4 py-2 text-12 text-danger"
            >
              {loadMoreError}
            </p>
          )}
        </>
      )}
    </section>
  );
}
