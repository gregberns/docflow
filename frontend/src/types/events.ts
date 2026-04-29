export interface ProcessingStepChanged {
  storedDocumentId: string;
  processingDocumentId: string;
  organizationId: string;
  currentStep: string;
  error: string | null;
  occurredAt: string;
}

export interface DocumentStateChanged {
  documentId: string;
  storedDocumentId: string;
  organizationId: string;
  currentStage: string | null;
  currentStatus: string | null;
  reextractionStatus: string | null;
  action: string | null;
  comment: string | null;
  occurredAt: string;
}

export type DocumentEvent =
  | { type: "processing-step-changed"; data: ProcessingStepChanged }
  | { type: "document-state-changed"; data: DocumentStateChanged };
