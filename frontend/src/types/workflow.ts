export interface StageSummary {
  id: string;
  displayName: string;
  kind: string;
  canonicalStatus: string;
  role: string;
}

export interface GuardSummary {
  field: string;
  op: string;
  value: string;
}

export interface TransitionSummary {
  fromStage: string;
  toStage: string;
  action: string;
  guard: GuardSummary | null;
}

export interface WorkflowSummary {
  documentTypeId: string;
  stages: StageSummary[];
  transitions: TransitionSummary[];
}

export type WorkflowAction =
  | { action: "Approve" }
  | { action: "Reject" }
  | { action: "Flag"; comment: string }
  | { action: "Resolve" };

export interface ReviewFieldsPatch {
  extractedFields: Record<string, unknown>;
}

export interface RetypeRequest {
  newDocumentType: string;
}
