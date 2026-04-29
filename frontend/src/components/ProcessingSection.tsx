import type { ProcessingItem } from "../types/readModels";

interface ProcessingSectionProps {
  items: ProcessingItem[];
}

export function ProcessingSection({ items }: ProcessingSectionProps) {
  if (items.length === 0) {
    return null;
  }
  return (
    <section data-testid="processing-section">
      <h2>Processing</h2>
      <table>
        <thead>
          <tr>
            <th>Document</th>
            <th>Step</th>
            <th>Started</th>
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
                style={{ opacity: 0.55 }}
              >
                <td>{item.sourceFilename}</td>
                <td>
                  {failed ? (
                    <span data-testid="processing-failure">{item.lastError ?? "Failed"}</span>
                  ) : (
                    <>
                      <span data-testid="spinner" aria-hidden="true">
                        ⟳
                      </span>
                      <span>{item.currentStep}</span>
                    </>
                  )}
                </td>
                <td>{item.createdAt}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
