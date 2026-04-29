import type { DocumentView, WorkflowStatus } from "../types/readModels";
import type { FieldSchema } from "../types/schema";
import type { StageSummary } from "../types/workflow";
import { ApprovalSummary } from "./ApprovalSummary";
import { TerminalSummary } from "./TerminalSummary";
import { FlagBanner } from "./FlagBanner";
import { ReextractionInProgressBanner } from "./ReextractionInProgressBanner";
import { ReextractionFailedBanner } from "./ReextractionFailedBanner";
import { ReviewForm, type ReviewFormCallbacks } from "./ReviewForm";

type FormValues = Record<string, unknown>;

export interface FormPanelHandlers {
  onApprove?: () => void;
  onFlag?: () => void;
  onBackToDocuments?: () => void;
  onDocumentTypeChange?: (newType: string) => void;
  onSubmitFields?: (values: FormValues) => void;
}

interface FormPanelProps {
  document: DocumentView;
  fields: FieldSchema[];
  docTypeOptions: ReadonlyArray<string>;
  stage?: StageSummary | null;
  pendingNewDocumentType?: string | null;
  reextractionFailureMessage?: string | null;
  isSubmitting?: boolean;
  handlers?: FormPanelHandlers;
}

function noop() {
  /* placeholder for handlers wired in later tasks */
}

const TERMINAL_STATUSES: ReadonlyArray<WorkflowStatus> = ["FILED", "REJECTED"];

type Branch =
  | "REEXTRACTION_IN_PROGRESS"
  | "REEXTRACTION_FAILED"
  | "REVIEW"
  | "REVIEW_FLAGGED"
  | "APPROVAL"
  | "TERMINAL";

function pickBranch(
  reextractionStatus: DocumentView["reextractionStatus"],
  currentStatus: WorkflowStatus | null,
  workflowOriginStage: string | null,
): Branch {
  if (reextractionStatus === "IN_PROGRESS") return "REEXTRACTION_IN_PROGRESS";
  if (reextractionStatus === "FAILED") return "REEXTRACTION_FAILED";
  if (currentStatus && TERMINAL_STATUSES.includes(currentStatus)) return "TERMINAL";
  if (currentStatus === "AWAITING_APPROVAL") return "APPROVAL";
  if (currentStatus === "FLAGGED" || workflowOriginStage) return "REVIEW_FLAGGED";
  return "REVIEW";
}

function reviewCallbacks(handlers?: FormPanelHandlers): ReviewFormCallbacks {
  const out: ReviewFormCallbacks = {};
  if (handlers?.onSubmitFields) out.onSubmitFields = handlers.onSubmitFields;
  if (handlers?.onDocumentTypeChange) out.onDocumentTypeChange = handlers.onDocumentTypeChange;
  return out;
}

export function FormPanel({
  document,
  fields,
  docTypeOptions,
  stage,
  pendingNewDocumentType,
  reextractionFailureMessage,
  isSubmitting,
  handlers,
}: FormPanelProps) {
  const { reextractionStatus, currentStatus, workflowOriginStage } = document;

  const branch = pickBranch(reextractionStatus, currentStatus, workflowOriginStage);
  const callbacks = reviewCallbacks(handlers);

  return (
    <div data-testid="form-panel" data-branch={branch}>
      {branch === "REEXTRACTION_IN_PROGRESS" && (
        <>
          <ReextractionInProgressBanner newDocumentType={pendingNewDocumentType} />
          <ReviewForm
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={Boolean(workflowOriginStage)}
            disabled={true}
            hideActions={true}
            isSubmitting={Boolean(isSubmitting)}
            callbacks={callbacks}
          />
        </>
      )}
      {branch === "REEXTRACTION_FAILED" && (
        <>
          <ReextractionFailedBanner message={reextractionFailureMessage} />
          <ReviewForm
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={Boolean(workflowOriginStage)}
            disabled={false}
            isSubmitting={Boolean(isSubmitting)}
            callbacks={callbacks}
          />
        </>
      )}
      {branch === "REVIEW" && (
        <ReviewForm
          document={document}
          fields={fields}
          docTypeOptions={docTypeOptions}
          flagged={false}
          disabled={false}
          isSubmitting={Boolean(isSubmitting)}
          callbacks={callbacks}
        />
      )}
      {branch === "REVIEW_FLAGGED" && (
        <>
          <FlagBanner originStage={workflowOriginStage ?? ""} comment={document.flagComment} />
          <ReviewForm
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={true}
            disabled={false}
            isSubmitting={Boolean(isSubmitting)}
            callbacks={callbacks}
          />
        </>
      )}
      {branch === "APPROVAL" && (
        <ApprovalSummary
          fields={fields}
          values={document.extractedFields}
          stageDisplayName={document.currentStageDisplayName}
          role={stage?.role ?? null}
          onApprove={handlers?.onApprove ?? noop}
          onFlag={handlers?.onFlag ?? noop}
          isSubmitting={isSubmitting ?? false}
        />
      )}
      {branch === "TERMINAL" && currentStatus && (
        <TerminalSummary
          fields={fields}
          values={document.extractedFields}
          status={currentStatus}
          stageDisplayName={document.currentStageDisplayName}
          onBackToDocuments={handlers?.onBackToDocuments ?? noop}
        />
      )}
    </div>
  );
}
