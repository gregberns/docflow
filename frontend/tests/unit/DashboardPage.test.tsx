import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "../msw/server";
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

  it("renders an error banner when the dashboard query fails", async () => {
    server.use(
      http.get("/api/organizations/:orgId/documents", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Internal Server Error",
            status: 500,
            code: "INTERNAL",
            message: "boom",
            details: [],
          },
          { status: 500, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    renderDashboard();
    await screen.findByTestId("dashboard-error");
    expect(screen.getByTestId("dashboard-error")).toHaveTextContent(/boom|Unable to load/);
  });

  it("triggers the upload flow and shows an error when upload fails", async () => {
    server.use(
      http.post("/api/organizations/:orgId/documents", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Unsupported Media Type",
            status: 415,
            code: "UNSUPPORTED_MEDIA_TYPE",
            message: "Only PDF and image files are supported",
            details: [],
          },
          { status: 415, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    renderDashboard();
    await screen.findByTestId("dashboard-stats");

    fireEvent.click(screen.getByTestId("upload-button"));

    const input = screen.getByTestId("upload-file-input") as HTMLInputElement;
    const file = new File([new Uint8Array([1, 2, 3])], "bad.txt", { type: "text/plain" });
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByTestId("upload-error")).toBeInTheDocument();
    });
  });
});
