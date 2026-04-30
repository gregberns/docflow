import { formatDocType } from "../util/formatters";
import { UploadIcon } from "./icons/Icons";

interface DashboardFilterBarProps {
  stage: string | "ALL";
  docType: string | "ALL";
  stageOptions: ReadonlyArray<string>;
  docTypeOptions: ReadonlyArray<string>;
  onStageChange: (stage: string | "ALL") => void;
  onDocTypeChange: (docType: string | "ALL") => void;
  onUploadClick?: () => void;
  uploadDisabled?: boolean;
}

const SELECT_CLASSES =
  "h-[34px] cursor-pointer rounded-md border border-neutral-300 bg-card px-2.5 text-13 text-brand-navy outline-none transition-colors focus:border-brand-blue focus:shadow-[0_0_0_2px_rgba(108,155,255,0.15)]";

const FILTER_LABEL_CLASSES = "text-12 font-semibold uppercase tracking-[0.5px] text-neutral-500";

export function DashboardFilterBar({
  stage,
  docType,
  stageOptions,
  docTypeOptions,
  onStageChange,
  onDocTypeChange,
  onUploadClick,
  uploadDisabled,
}: DashboardFilterBarProps) {
  return (
    <section data-testid="dashboard-filters" className="mb-5 flex items-center gap-2.5">
      <label className="flex items-center gap-1.5">
        <span className={FILTER_LABEL_CLASSES}>Stage</span>
        <select
          data-testid="filter-stage"
          value={stage}
          onChange={(event) => onStageChange(event.target.value)}
          className={SELECT_CLASSES}
        >
          <option value="ALL">All Stages</option>
          {stageOptions.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </label>
      <span aria-hidden="true" className="mx-1 h-5 w-px bg-neutral-200" />
      <label className="flex items-center gap-1.5">
        <span className={FILTER_LABEL_CLASSES}>Type</span>
        <select
          data-testid="filter-doctype"
          value={docType}
          onChange={(event) => onDocTypeChange(event.target.value)}
          className={SELECT_CLASSES}
        >
          <option value="ALL">All Types</option>
          {docTypeOptions.map((value) => (
            <option key={value} value={value}>
              {formatDocType(value)}
            </option>
          ))}
        </select>
      </label>
      <div className="flex-1" />
      {onUploadClick && (
        <button
          type="button"
          data-testid="upload-button"
          onClick={onUploadClick}
          disabled={uploadDisabled}
          className="inline-flex h-[34px] items-center gap-1.5 rounded-md bg-brand-blue px-4 text-13 font-semibold text-white transition-colors hover:bg-brand-blue-hover disabled:cursor-not-allowed disabled:opacity-60"
        >
          <UploadIcon />
          Upload Document
        </button>
      )}
    </section>
  );
}
