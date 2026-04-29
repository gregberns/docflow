import type { WorkflowStatus } from "../types/readModels";

const STATUS_LABELS: Record<WorkflowStatus, string> = {
  AWAITING_REVIEW: "Awaiting Review",
  FLAGGED: "Flagged",
  AWAITING_APPROVAL: "Awaiting Approval",
  FILED: "Filed",
  REJECTED: "Rejected",
};

interface DashboardFilterBarProps {
  status: WorkflowStatus | "ALL";
  docType: string | "ALL";
  statusOptions: ReadonlyArray<WorkflowStatus>;
  docTypeOptions: ReadonlyArray<string>;
  onStatusChange: (status: WorkflowStatus | "ALL") => void;
  onDocTypeChange: (docType: string | "ALL") => void;
  onUploadClick?: () => void;
  uploadDisabled?: boolean;
}

export function DashboardFilterBar({
  status,
  docType,
  statusOptions,
  docTypeOptions,
  onStatusChange,
  onDocTypeChange,
  onUploadClick,
  uploadDisabled,
}: DashboardFilterBarProps) {
  return (
    <section data-testid="dashboard-filters">
      <label>
        <span>Status</span>
        <select
          data-testid="filter-status"
          value={status}
          onChange={(event) => onStatusChange(event.target.value as WorkflowStatus | "ALL")}
        >
          <option value="ALL">All Statuses</option>
          {statusOptions.map((value) => (
            <option key={value} value={value}>
              {STATUS_LABELS[value]}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Type</span>
        <select
          data-testid="filter-doctype"
          value={docType}
          onChange={(event) => onDocTypeChange(event.target.value)}
        >
          <option value="ALL">All Types</option>
          {docTypeOptions.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </label>
      {onUploadClick && (
        <button
          type="button"
          data-testid="upload-button"
          onClick={onUploadClick}
          disabled={uploadDisabled}
        >
          Upload Document
        </button>
      )}
    </section>
  );
}
