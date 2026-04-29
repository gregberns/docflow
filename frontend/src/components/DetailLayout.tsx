import type { ReactNode } from "react";

interface DetailLayoutProps {
  left: ReactNode;
  right: ReactNode;
}

export function DetailLayout({ left, right }: DetailLayoutProps) {
  return (
    <div data-testid="detail-layout" className="flex">
      <section data-testid="detail-pane-left" aria-label="Document preview">
        {left}
      </section>
      <section data-testid="detail-pane-right" aria-label="Document form">
        {right}
      </section>
    </div>
  );
}
