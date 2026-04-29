import { useState } from "react";
import type { DocumentView, WorkflowStatus } from "../types/readModels";
import type { FieldSchema } from "../types/schema";
import type { StageSummary } from "../types/workflow";
import { ApprovalSummary } from "./ApprovalSummary";
import { TerminalSummary } from "./TerminalSummary";
import { FlagBanner } from "./FlagBanner";
import { ReextractionInProgressBanner } from "./ReextractionInProgressBanner";
import { ReextractionFailedBanner } from "./ReextractionFailedBanner";
import { ReviewForm, type ReviewFormCallbacks } from "./ReviewForm";
import { ReclassifyModal } from "./ReclassifyModal";
import { FlagModal } from "./FlagModal";

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

  const [pendingDocType, setPendingDocType] = useState<string | null>(null);
  const [flagOpen, setFlagOpen] = useState(false);

  const reviewCallbacks: ReviewFormCallbacks = {
    onDocumentTypeChange: (newType: string) => {
      if (newType && newType !== document.detectedDocumentType) {
        setPendingDocType(newType);
      }
      handlers?.onDocumentTypeChange?.(newType);
    },
  };
  if (handlers?.onSubmitFields) reviewCallbacks.onSubmitFields = handlers.onSubmitFields;

  const onFlag = (): void => {
    setFlagOpen(true);
    handlers?.onFlag?.();
  };

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
            callbacks={reviewCallbacks}
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
            selectedDocumentType={pendingDocType}
            callbacks={reviewCallbacks}
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
          selectedDocumentType={pendingDocType}
          callbacks={reviewCallbacks}
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
            selectedDocumentType={pendingDocType}
            callbacks={reviewCallbacks}
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
          onFlag={onFlag}
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
      {pendingDocType && (
        <ReclassifyModal
          documentId={document.documentId}
          organizationId={document.organizationId}
          newDocumentType={pendingDocType}
          previousDocumentType={document.detectedDocumentType ?? ""}
          onCancel={() => setPendingDocType(null)}
          onConfirmed={() => setPendingDocType(null)}
        />
      )}
      {flagOpen && (
        <FlagModal
          documentId={document.documentId}
          organizationId={document.organizationId}
          onCancel={() => setFlagOpen(false)}
          onSubmitted={() => setFlagOpen(false)}
        />
      )}
    </div>
  );
}
