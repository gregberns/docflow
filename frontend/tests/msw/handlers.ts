import { http, HttpResponse } from "msw";
import type {
  DashboardResponse,
  DocumentView,
  OrganizationDetail,
  OrganizationListItem,
  RetypeAccepted,
  UploadAccepted,
} from "../../src/types/readModels";

const ORG_PINNACLE: OrganizationListItem = {
  id: "pinnacle-legal",
  name: "Pinnacle Legal",
  icon: "briefcase",
  docTypes: ["court-filing", "client-intake"],
  inProgressCount: 3,
  filedCount: 12,
};

const ORG_HARBOR: OrganizationListItem = {
  id: "harbor-clinic",
  name: "Harbor Clinic",
  icon: "stethoscope",
  docTypes: ["medical-claim"],
  inProgressCount: 1,
  filedCount: 5,
};

const ORG_DETAIL: OrganizationDetail = {
  id: "pinnacle-legal",
  name: "Pinnacle Legal",
  icon: "briefcase",
  docTypes: ["court-filing", "client-intake"],
  workflows: [
    {
      documentTypeId: "court-filing",
      stages: [
        {
          id: "review",
          displayName: "Review",
          kind: "REVIEW",
          canonicalStatus: "AWAITING_REVIEW",
          role: "PARALEGAL",
        },
        {
          id: "approval",
          displayName: "Approval",
          kind: "APPROVAL",
          canonicalStatus: "AWAITING_APPROVAL",
          role: "ATTORNEY",
        },
        {
          id: "filed",
          displayName: "Filed",
          kind: "TERMINAL",
          canonicalStatus: "FILED",
          role: "SYSTEM",
        },
      ],
      transitions: [
        { fromStage: "review", toStage: "approval", action: "Approve", guard: null },
        { fromStage: "approval", toStage: "filed", action: "Approve", guard: null },
      ],
    },
  ],
  fieldSchemas: {
    "court-filing": [
      { name: "caseNumber", type: "STRING", required: true, enumValues: null, itemFields: null },
      { name: "filingDate", type: "DATE", required: true, enumValues: null, itemFields: null },
    ],
    "client-intake": [
      { name: "clientName", type: "STRING", required: true, enumValues: null, itemFields: null },
    ],
  },
};

const DOCUMENT_VIEW: DocumentView = {
  documentId: "11111111-1111-1111-1111-111111111111",
  organizationId: "pinnacle-legal",
  sourceFilename: "complaint.pdf",
  mimeType: "application/pdf",
  uploadedAt: "2026-04-01T12:00:00Z",
  processedAt: "2026-04-01T12:00:30Z",
  rawText: "Case No. 2026-CV-00042 ...",
  currentStageId: "review",
  currentStageDisplayName: "Review",
  currentStatus: "AWAITING_REVIEW",
  workflowOriginStage: "review",
  flagComment: null,
  detectedDocumentType: "court-filing",
  extractedFields: { caseNumber: "2026-CV-00042", filingDate: "2026-04-01" },
  reextractionStatus: "NONE",
};

const DOCUMENT_INTAKE_FLAGGED: DocumentView = {
  documentId: "aaaaaaaa-1111-1111-1111-111111111111",
  organizationId: "pinnacle-legal",
  sourceFilename: "intake-smith.pdf",
  mimeType: "application/pdf",
  uploadedAt: "2026-04-02T10:00:00Z",
  processedAt: "2026-04-02T10:00:25Z",
  rawText: "Client intake — Smith ...",
  currentStageId: "review",
  currentStageDisplayName: "Review",
  currentStatus: "FLAGGED",
  workflowOriginStage: "approval",
  flagComment: "Missing signature page",
  detectedDocumentType: "client-intake",
  extractedFields: { clientName: "Smith" },
  reextractionStatus: "NONE",
};

const DOCUMENT_FILING_FILED: DocumentView = {
  documentId: "bbbbbbbb-2222-2222-2222-222222222222",
  organizationId: "pinnacle-legal",
  sourceFilename: "motion-to-dismiss.pdf",
  mimeType: "application/pdf",
  uploadedAt: "2026-03-12T09:00:00Z",
  processedAt: "2026-03-12T09:01:00Z",
  rawText: "Motion to dismiss ...",
  currentStageId: "filed",
  currentStageDisplayName: "Filed",
  currentStatus: "FILED",
  workflowOriginStage: null,
  flagComment: null,
  detectedDocumentType: "court-filing",
  extractedFields: { caseNumber: "2026-CV-00012", filingDate: "2026-03-12" },
  reextractionStatus: "NONE",
};

const DOCUMENT_INTAKE_APPROVAL: DocumentView = {
  documentId: "cccccccc-3333-3333-3333-333333333333",
  organizationId: "pinnacle-legal",
  sourceFilename: "intake-jones.pdf",
  mimeType: "application/pdf",
  uploadedAt: "2026-04-15T14:00:00Z",
  processedAt: "2026-04-15T14:00:18Z",
  rawText: "Client intake — Jones ...",
  currentStageId: "approval",
  currentStageDisplayName: "Approval",
  currentStatus: "AWAITING_APPROVAL",
  workflowOriginStage: null,
  flagComment: null,
  detectedDocumentType: "client-intake",
  extractedFields: { clientName: "Jones" },
  reextractionStatus: "NONE",
};

const DASHBOARD_RESPONSE: DashboardResponse = {
  processing: [
    {
      processingDocumentId: "22222222-2222-2222-2222-222222222222",
      storedDocumentId: "33333333-3333-3333-3333-333333333333",
      sourceFilename: "intake-form.pdf",
      currentStep: "EXTRACTING",
      lastError: null,
      createdAt: "2026-04-29T08:00:00Z",
    },
  ],
  documents: [
    DOCUMENT_VIEW,
    DOCUMENT_INTAKE_FLAGGED,
    DOCUMENT_FILING_FILED,
    DOCUMENT_INTAKE_APPROVAL,
  ],
  stats: {
    inProgress: 1,
    awaitingReview: 1,
    flagged: 1,
    filedThisMonth: 12,
  },
  nextCursor: null,
};

const UPLOAD_ACCEPTED: UploadAccepted = {
  storedDocumentId: "44444444-4444-4444-4444-444444444444",
  processingDocumentId: "55555555-5555-5555-5555-555555555555",
};

const RETYPE_ACCEPTED: RetypeAccepted = { reextractionStatus: "IN_PROGRESS" };

export const fixtures = {
  organizations: [ORG_PINNACLE, ORG_HARBOR],
  organizationDetail: ORG_DETAIL,
  document: DOCUMENT_VIEW,
  dashboard: DASHBOARD_RESPONSE,
  upload: UPLOAD_ACCEPTED,
  retype: RETYPE_ACCEPTED,
};

export const handlers = [
  http.get("/api/organizations", () => HttpResponse.json([ORG_PINNACLE, ORG_HARBOR])),
  http.get("/api/organizations/:orgId", () => HttpResponse.json(ORG_DETAIL)),
  http.get("/api/organizations/:orgId/documents", () => HttpResponse.json(DASHBOARD_RESPONSE)),
  http.post("/api/organizations/:orgId/documents", () =>
    HttpResponse.json(UPLOAD_ACCEPTED, { status: 201 }),
  ),
  http.get("/api/documents/:documentId", () => HttpResponse.json(DOCUMENT_VIEW)),
  http.get("/api/documents/:documentId/file", () =>
    HttpResponse.arrayBuffer(new ArrayBuffer(16), {
      status: 200,
      headers: { "Content-Type": "application/pdf" },
    }),
  ),
  http.post("/api/documents/:documentId/actions", () => HttpResponse.json(DOCUMENT_VIEW)),
  http.patch("/api/documents/:documentId/review/fields", () => HttpResponse.json(DOCUMENT_VIEW)),
  http.post("/api/documents/:documentId/review/retype", () =>
    HttpResponse.json(RETYPE_ACCEPTED, { status: 202 }),
  ),
];
