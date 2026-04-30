import type { WorkflowSummary } from "./workflow";
import type { FieldSchema } from "./schema";

export type WorkflowStatus =
  | "AWAITING_REVIEW"
  | "FLAGGED"
  | "AWAITING_APPROVAL"
  | "FILED"
  | "REJECTED";

export type ReextractionStatus = "NONE" | "IN_PROGRESS" | "FAILED";

export interface OrganizationListItem {
  id: string;
  name: string;
  icon: string;
  docTypes: string[];
  inProgressCount: number;
  filedCount: number;
}

export interface OrganizationDetail {
  id: string;
  name: string;
  icon: string;
  docTypes: string[];
  workflows: WorkflowSummary[];
  fieldSchemas: Record<string, FieldSchema[]>;
}

export interface ProcessingItem {
  processingDocumentId: string;
  storedDocumentId: string;
  sourceFilename: string;
  currentStep: string;
  lastError: string | null;
  createdAt: string;
}

export interface DashboardStats {
  inProgress: number;
  awaitingReview: number;
  flagged: number;
  filedThisMonth: number;
}

export interface DocumentView {
  documentId: string;
  organizationId: string;
  sourceFilename: string;
  mimeType: string;
  uploadedAt: string;
  processedAt: string | null;
  rawText: string | null;
  currentStageId: string | null;
  currentStageDisplayName: string | null;
  currentStatus: WorkflowStatus | null;
  workflowOriginStage: string | null;
  flagComment: string | null;
  detectedDocumentType: string | null;
  extractedFields: Record<string, unknown>;
  reextractionStatus: ReextractionStatus;
}

export interface DocumentCursor {
  updatedAt: string;
  id: string;
}

export interface DashboardResponse {
  processing: ProcessingItem[];
  documents: DocumentView[];
  stats: DashboardStats;
  nextCursor: DocumentCursor | null;
}

export interface UploadAccepted {
  storedDocumentId: string;
  processingDocumentId: string;
}

export interface RetypeAccepted {
  reextractionStatus: string;
}
