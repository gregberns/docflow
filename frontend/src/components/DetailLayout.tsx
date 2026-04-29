import type { ReactNode } from "react";

interface DetailLayoutProps {
  left: ReactNode;
  right: ReactNode;
}

export function DetailLayout({ left, right }: DetailLayoutProps) {
  return (
    <div data-testid="detail-layout" className="flex h-[calc(100vh-52px)] w-full">
      <section
        data-testid="detail-pane-left"
        aria-label="Document preview"
        className="flex min-w-0 flex-1 flex-col bg-pdf-panel"
      >
        {left}
      </section>
      <section
        data-testid="detail-pane-right"
        aria-label="Document form"
        className="flex w-[420px] flex-shrink-0 flex-col border-l border-neutral-200 bg-card"
      >
        {right}
      </section>
    </div>
  );
}
