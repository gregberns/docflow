import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "../../src/App";

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("App routing", () => {
  it("resolves /processing-documents/:id to NotFoundPage (AC6.1)", () => {
    renderAt("/processing-documents/abc-123");
    expect(screen.getByTestId("not-found-page")).toBeInTheDocument();
    expect(screen.queryByTestId("document-detail-page")).not.toBeInTheDocument();
  });

  it("resolves arbitrary unknown paths to NotFoundPage", () => {
    renderAt("/no-such-route/anything");
    expect(screen.getByTestId("not-found-page")).toBeInTheDocument();
  });

  it("mounts OrgPickerPage at /", () => {
    renderAt("/");
    expect(screen.getByTestId("org-picker-page")).toBeInTheDocument();
  });

  it("mounts DashboardPage at /org/:orgId/dashboard with the orgId param", () => {
    renderAt("/org/pinnacle-legal/dashboard");
    const page = screen.getByTestId("dashboard-page");
    expect(page).toBeInTheDocument();
    expect(page).toHaveAttribute("data-org-id", "pinnacle-legal");
  });

  it("mounts DocumentDetailPage at /documents/:documentId with the documentId param", () => {
    renderAt("/documents/doc-42");
    const page = screen.getByTestId("document-detail-page");
    expect(page).toBeInTheDocument();
    expect(page).toHaveAttribute("data-document-id", "doc-42");
  });
});
