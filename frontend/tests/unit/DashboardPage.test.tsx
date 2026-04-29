import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { DashboardPage } from "../../src/routes/DashboardPage";

function renderDashboard(orgId = "pinnacle-legal") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/org/${orgId}/dashboard`]}>
        <Routes>
          <Route path="/org/:orgId/dashboard" element={<DashboardPage />} />
          <Route
            path="/documents/:documentId"
            element={<div data-testid="document-detail-stub" />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("DashboardPage", () => {
  it("renders stats, processing/document sections, refilters reactively, and navigates on row click", async () => {
    renderDashboard();

    await screen.findByTestId("dashboard-stats");

    expect(screen.getByTestId("stat-inProgress-value")).toHaveTextContent("1");
    expect(screen.getByTestId("stat-awaitingReview-value")).toHaveTextContent("1");
    expect(screen.getByTestId("stat-flagged-value")).toHaveTextContent("1");
    expect(screen.getByTestId("stat-filedThisMonth-value")).toHaveTextContent("12");

    expect(screen.getAllByTestId("processing-row")).toHaveLength(1);
    expect(screen.getAllByTestId("document-row")).toHaveLength(4);

    fireEvent.change(screen.getByTestId("filter-status"), {
      target: { value: "AWAITING_REVIEW" },
    });
    await waitFor(() => {
      expect(screen.getAllByTestId("document-row")).toHaveLength(1);
    });
    expect(screen.getByTestId("document-row")).toHaveAttribute("data-status", "AWAITING_REVIEW");

    fireEvent.change(screen.getByTestId("filter-status"), { target: { value: "ALL" } });
    fireEvent.change(screen.getByTestId("filter-doctype"), {
      target: { value: "client-intake" },
    });
    await waitFor(() => {
      expect(screen.getAllByTestId("document-row")).toHaveLength(2);
    });
    for (const row of screen.getAllByTestId("document-row")) {
      expect(row).toHaveAttribute("data-doc-type", "client-intake");
    }

    expect(screen.getByTestId("stat-flagged-value")).toHaveTextContent("1");

    fireEvent.click(screen.getAllByTestId("document-row")[0]);
    await waitFor(() => {
      expect(screen.getByTestId("document-detail-stub")).toBeInTheDocument();
    });
  });
});
