import type { DocumentView, WorkflowStatus } from "../types/readModels";
import { ChevronLeftIcon } from "./icons/Icons";

interface DocumentHeaderProps {
  document: DocumentView;
}

function stageBadgeClass(status: WorkflowStatus | null, stageName: string | null): string {
  const base =
    "inline-flex items-center rounded-sm px-2 py-[2px] text-11 font-semibold tracking-[0.3px]";
  if (status === "REJECTED") {
    return `${base} bg-stage-rejected-bg text-stage-rejected-fg`;
  }
  if (status === "FILED") {
    return `${base} bg-stage-filed-bg text-stage-filed-fg`;
  }
  if (status === "AWAITING_APPROVAL") {
    return `${base} bg-stage-approval-bg text-stage-approval-fg`;
  }
  if (status === "AWAITING_REVIEW") {
    return `${base} bg-stage-review-bg text-stage-review-fg`;
  }
  if (stageName) {
    return `${base} bg-stage-processing-bg text-stage-processing-fg`;
  }
  return `${base} bg-stage-type-neutral-bg text-stage-type-neutral-fg`;
}

export function DocumentHeader({ document }: DocumentHeaderProps) {
  const {
    sourceFilename,
    detectedDocumentType,
    currentStageDisplayName,
    currentStatus,
    uploadedAt,
  } = document;
  return (
    <header
      data-testid="document-header"
      className="flex-shrink-0 border-b border-neutral-100 px-6 pb-4 pt-5"
    >
      <a className="mb-3 inline-flex cursor-pointer items-center gap-1 text-12 text-neutral-500 transition-colors hover:text-brand-blue">
        <ChevronLeftIcon />
        Back to Documents
      </a>
      <h1
        data-testid="document-filename"
        className="text-18 font-bold leading-tight text-brand-navy"
      >
        {sourceFilename}
      </h1>
      <dl className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-12 text-neutral-400">
        {currentStageDisplayName && (
          <div className="flex items-center gap-1">
            <dt className="sr-only">Stage</dt>
            <dd data-testid="document-stage">
              <span className={stageBadgeClass(currentStatus, currentStageDisplayName)}>
                {currentStageDisplayName}
              </span>
            </dd>
          </div>
        )}
        {detectedDocumentType && (
          <div className="flex items-center gap-1">
            <dt className="sr-only">Type</dt>
            <dd data-testid="document-doc-type">
              <span className="inline-flex items-center rounded-sm bg-stage-type-neutral-bg px-2 py-[2px] text-11 font-semibold tracking-[0.3px] text-stage-type-neutral-fg">
                {detectedDocumentType}
              </span>
            </dd>
          </div>
        )}
        {currentStatus && (
          <div className="sr-only">
            <dt>Status</dt>
            <dd data-testid="document-status">{currentStatus}</dd>
          </div>
        )}
        <div className="flex items-center gap-1">
          <dt>Uploaded</dt>
          <dd data-testid="document-uploaded-at">{uploadedAt}</dd>
        </div>
      </dl>
    </header>
  );
}
