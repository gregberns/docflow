import type { FieldSchema } from "../types/schema";
import type { WorkflowStatus } from "../types/readModels";
import { formatDisplay, formatFieldName } from "../util/formatters";
import { ReadOnlyArrayTable } from "./ReadOnlyArrayTable";

interface TerminalSummaryProps {
  fields: FieldSchema[];
  values: Record<string, unknown>;
  status: WorkflowStatus;
  stageDisplayName: string | null;
  onBackToDocuments: () => void;
}

export function TerminalSummary({
  fields,
  values,
  status,
  stageDisplayName,
  onBackToDocuments,
}: TerminalSummaryProps) {
  return (
    <section
      data-testid="terminal-summary"
      data-status={status}
      className="flex min-h-0 flex-1 flex-col"
    >
      <div className="flex-1 overflow-y-auto px-6 py-5">
        <h2
          data-testid="terminal-summary-heading"
          className="mb-3 text-11 font-bold uppercase tracking-[0.5px] text-neutral-500"
        >
          {stageDisplayName ?? status}
        </h2>
        <dl data-testid="terminal-summary-fields" className="m-0">
          {fields.map((field) => {
            const upper = field.type.toUpperCase();
            const value = values[field.name];
            if (upper === "ARRAY") {
              const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
              return (
                <div key={field.name} data-testid={`terminal-field-${field.name}`} className="mb-4">
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
                data-testid={`terminal-field-${field.name}`}
                className="mb-2.5 flex"
              >
                <dt className="w-[130px] flex-shrink-0 pt-px text-12 text-neutral-500">
                  {formatFieldName(field.name)}
                </dt>
                <dd className="m-0 flex-1 text-13 font-medium text-brand-navy">
                  {formatDisplay(value)}
                </dd>
              </div>
            );
          })}
        </dl>
      </div>
      <div
        data-testid="terminal-action-bar"
        className="flex flex-shrink-0 gap-2.5 border-t border-neutral-200 bg-card px-6 py-4"
      >
        <button
          type="button"
          data-testid="back-to-documents-button"
          onClick={onBackToDocuments}
          className="inline-flex h-[38px] flex-1 items-center justify-center gap-1.5 rounded-md border border-neutral-200 bg-card px-5 text-13 font-semibold text-neutral-700 transition-colors hover:border-brand-blue hover:text-brand-blue"
        >
          Back to Documents
        </button>
      </div>
    </section>
  );
}
