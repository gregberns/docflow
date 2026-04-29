import type { FieldSchema } from "../types/schema";
import { formatDisplay } from "../util/formatters";
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
    <section data-testid="approval-summary">
      <h2 data-testid="approval-summary-heading">{heading}</h2>
      <dl data-testid="approval-summary-fields">
        {fields.map((field) => {
          const upper = field.type.toUpperCase();
          const value = values[field.name];
          if (upper === "ARRAY") {
            const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
            return (
              <div key={field.name} data-testid={`approval-field-${field.name}`}>
                <dt>{field.name}</dt>
                <dd>
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
            <div key={field.name} data-testid={`approval-field-${field.name}`}>
              <dt>{field.name}</dt>
              <dd>{formatDisplay(value)}</dd>
            </div>
          );
        })}
      </dl>
      <div data-testid="approval-action-bar">
        <button
          type="button"
          data-testid="approve-button"
          onClick={onApprove}
          disabled={isSubmitting}
        >
          Approve
        </button>
        <button type="button" data-testid="flag-button" onClick={onFlag} disabled={isSubmitting}>
          Flag
        </button>
      </div>
    </section>
  );
}
