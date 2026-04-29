import type { FieldSchema } from "../types/schema";
import type { WorkflowStatus } from "../types/readModels";
import { formatDisplay } from "../util/formatters";
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
    <section data-testid="terminal-summary" data-status={status}>
      <h2 data-testid="terminal-summary-heading">{stageDisplayName ?? status}</h2>
      <dl data-testid="terminal-summary-fields">
        {fields.map((field) => {
          const upper = field.type.toUpperCase();
          const value = values[field.name];
          if (upper === "ARRAY") {
            const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
            return (
              <div key={field.name} data-testid={`terminal-field-${field.name}`}>
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
            <div key={field.name} data-testid={`terminal-field-${field.name}`}>
              <dt>{field.name}</dt>
              <dd>{formatDisplay(value)}</dd>
            </div>
          );
        })}
      </dl>
      <div data-testid="terminal-action-bar">
        <button type="button" data-testid="back-to-documents-button" onClick={onBackToDocuments}>
          Back to Documents
        </button>
      </div>
    </section>
  );
}
