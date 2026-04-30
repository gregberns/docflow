import type { FieldSchema } from "../types/schema";
import { formatFieldName, formatFieldValue } from "../util/formatters";
import { ReadOnlyArrayTable } from "./ReadOnlyArrayTable";

interface ApprovalSummaryProps {
  fields: FieldSchema[];
  values: Record<string, unknown>;
  stageDisplayName: string | null;
  role?: string | null;
  onApprove: () => void;
  onFlag: () => void;
  isSubmitting?: boolean;
}

export function ApprovalSummary({
  fields,
  values,
  stageDisplayName,
  role,
  onApprove,
  onFlag,
  isSubmitting,
}: ApprovalSummaryProps) {
  const heading =
    stageDisplayName && role && role.trim().length > 0
      ? `${stageDisplayName} — role: ${role}`
      : (stageDisplayName ?? "Approval");

  return (
    <section data-testid="approval-summary" className="flex min-h-0 flex-1 flex-col">
      <div className="flex-1 overflow-y-auto px-6 py-5">
        <h2
          data-testid="approval-summary-heading"
          className="mb-3 text-11 font-bold uppercase tracking-[0.5px] text-neutral-500"
        >
          {heading}
        </h2>
        <dl data-testid="approval-summary-fields" className="m-0">
          {fields.map((field) => {
            const upper = field.type.toUpperCase();
            const value = values[field.name];
            if (upper === "ARRAY") {
              const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
              return (
                <div key={field.name} data-testid={`approval-field-${field.name}`} className="mb-4">
                  <dt className="mb-2 text-11 font-bold uppercase tracking-[0.5px] text-neutral-500">
                    {formatFieldName(field.name)}
                  </dt>
                  <dd className="m-0">
                    <ReadOnlyArrayTable
                      name={field.name}
                      itemFields={field.itemFields ?? []}
                      rows={rows}
                    />
                  </dd>
                </div>
              );
            }
            return (
              <div
                key={field.name}
                data-testid={`approval-field-${field.name}`}
                className="mb-2.5 flex"
              >
                <dt className="w-[130px] flex-shrink-0 pt-px text-12 text-neutral-500">
                  {formatFieldName(field.name)}
                </dt>
                <dd className="m-0 flex-1 text-13 font-medium text-brand-navy">
                  {formatFieldValue(field.type, field.format, value)}
                </dd>
              </div>
            );
          })}
        </dl>
      </div>
      <div
        data-testid="approval-action-bar"
        className="flex flex-shrink-0 gap-2.5 border-t border-neutral-200 bg-card px-6 py-4"
      >
        <button
          type="button"
          data-testid="flag-button"
          onClick={onFlag}
          disabled={isSubmitting}
          className="inline-flex h-[38px] items-center justify-center gap-1.5 rounded-md border border-[#fde68a] bg-card px-5 text-13 font-semibold text-warn transition-colors hover:bg-[#fffbeb] disabled:cursor-not-allowed disabled:opacity-50"
        >
          Flag
        </button>
        <button
          type="button"
          data-testid="approve-button"
          onClick={onApprove}
          disabled={isSubmitting}
          className="inline-flex h-[38px] flex-1 items-center justify-center gap-1.5 rounded-md border-0 bg-success px-5 text-13 font-semibold text-white transition-colors hover:bg-success-strong disabled:cursor-not-allowed disabled:opacity-60"
        >
          Approve
        </button>
      </div>
    </section>
  );
}
