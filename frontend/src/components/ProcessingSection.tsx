import type { ProcessingItem } from "../types/readModels";

interface ProcessingSectionProps {
  items: ProcessingItem[];
}

export function ProcessingSection({ items }: ProcessingSectionProps) {
  if (items.length === 0) {
    return null;
  }
  return (
    <section
      data-testid="processing-section"
      className="mb-5 overflow-hidden rounded-lg border border-neutral-200 bg-card"
    >
      <h2 className="border-b border-neutral-200 bg-table-head px-4 py-2.5 text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
        Processing
      </h2>
      <table className="w-full border-collapse">
        <thead className="bg-table-head">
          <tr>
            <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
              Document
            </th>
            <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
              Step
            </th>
            <th className="border-b border-neutral-200 px-4 py-2.5 text-left text-11 font-semibold uppercase tracking-[0.5px] text-neutral-500">
              Started
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const failed = item.currentStep === "FAILED";
            return (
              <tr
                key={item.processingDocumentId}
                data-testid="processing-row"
                data-step={item.currentStep}
                className="opacity-[0.55] [&>td]:border-b [&>td]:border-neutral-100 last:[&>td]:border-b-0"
              >
                <td className="px-4 py-3 text-13 font-semibold text-neutral-500 align-middle">
                  {item.sourceFilename}
                </td>
                <td className="px-4 py-3 text-13 align-middle">
                  {failed ? (
                    <span
                      data-testid="processing-failure"
                      className="inline-flex items-center rounded-sm bg-stage-rejected-bg px-2 py-0.5 text-11 font-semibold tracking-[0.3px] text-danger"
                    >
                      {item.lastError ?? "Failed"}
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1.5 rounded-sm bg-stage-processing-bg px-2 py-0.5 text-11 font-semibold tracking-[0.3px] text-stage-processing-fg">
                      <span
                        data-testid="spinner"
                        aria-hidden="true"
                        className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-400"
                      />
                      <span>{item.currentStep}</span>
                    </span>
                  )}
                </td>
                <td className="px-4 py-3 text-12 text-neutral-400 align-middle">
                  {item.createdAt}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div aria-hidden="true" className="border-b-2 border-dashed border-neutral-200" />
    </section>
  );
}
