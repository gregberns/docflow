import { useNavigate } from "react-router-dom";
import type { DocumentView, WorkflowStatus } from "../types/readModels";
import { ChevronRightIcon } from "./icons/Icons";

interface DocumentsSectionProps {
  documents: DocumentView[];
}

const STATUS_BADGE_CLASSES: Record<WorkflowStatus, string> = {
  AWAITING_REVIEW: "bg-stage-review-bg text-stage-review-fg",
  AWAITING_APPROVAL: "bg-stage-approval-bg text-stage-approval-fg",
  FILED: "bg-stage-filed-bg text-stage-filed-fg",
  REJECTED: "bg-stage-rejected-bg text-stage-rejected-fg",
  FLAGGED: "bg-stage-flagged-bg text-stage-flagged-fg border border-[#fecdd3]",
};

const NEUTRAL_BADGE = "bg-stage-type-neutral-bg text-stage-type-neutral-fg";

const BADGE_BASE =
  "inline-flex items-center rounded-sm px-2 py-0.5 text-11 font-semibold tracking-[0.3px]";

function StageBadge({ status, label }: { status: WorkflowStatus | null; label: string }) {
  const variant = status ? STATUS_BADGE_CLASSES[status] : NEUTRAL_BADGE;
  const testId = status === "FLAGGED" ? "badge-flagged" : "badge-stage";
  return (
    <span data-testid={testId} className={`${BADGE_BASE} ${variant}`}>
      {label}
    </span>
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

export function DocumentsSection({ documents }: DocumentsSectionProps) {
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
                  Status
                </th>
                <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
                  Updated
                </th>
                <th className="w-8 border-b border-neutral-200 px-4 py-2.5" aria-hidden="true" />
              </tr>
            </thead>
            <tbody>
              {documents.map((doc) => (
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
                    <span className="font-semibold text-brand-navy">{doc.sourceFilename}</span>
                  </td>
                  <td className="px-4 py-3 text-13 align-middle">
                    <TypeBadge value={doc.detectedDocumentType} />
                  </td>
                  <td className="px-4 py-3 text-13 align-middle">
                    {doc.currentStageDisplayName ? (
                      <span className={`${BADGE_BASE} ${NEUTRAL_BADGE}`}>
                        {doc.currentStageDisplayName}
                      </span>
                    ) : (
                      <span className="text-neutral-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-13 align-middle">
                    {doc.currentStatus ? (
                      <StageBadge status={doc.currentStatus} label={doc.currentStatus} />
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
              ))}
            </tbody>
          </table>
          <div className="flex items-center justify-between border-t border-neutral-200 bg-table-head px-4 py-3">
            <span className="text-12 text-neutral-500">
              Showing {documents.length} of {documents.length} documents
            </span>
          </div>
        </>
      )}
    </section>
  );
}
