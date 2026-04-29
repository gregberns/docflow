import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "../msw/server";
import { OrgPickerPage } from "../../src/routes/OrgPickerPage";
import type { OrganizationListItem } from "../../src/types/readModels";

const ORG_A: OrganizationListItem = {
  id: "ironworks-construction",
  name: "Ironworks Construction",
  icon: "hardhat",
  docTypes: ["invoice", "change-order"],
  inProgressCount: 5,
  filedCount: 38,
};

const ORG_B: OrganizationListItem = {
  id: "riverside-bistro",
  name: "Riverside Bistro",
  icon: "knife-fork",
  docTypes: ["invoice", "receipt"],
  inProgressCount: 8,
  filedCount: 47,
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<OrgPickerPage />} />
          <Route path="/org/:orgId/dashboard" element={<div data-testid="dashboard-page-stub" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("OrgPickerPage", () => {
  it("renders one card per organization and navigates on click (AC1.1, AC1.3)", async () => {
    server.use(http.get("/api/organizations", () => HttpResponse.json([ORG_A, ORG_B])));

    renderPage();

    const cards = await screen.findAllByTestId("org-card");
    expect(cards).toHaveLength(2);
    expect(screen.getByText("Ironworks Construction")).toBeInTheDocument();
    expect(screen.getByText("Riverside Bistro")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Ironworks Construction/ }));
    await waitFor(() => {
      expect(screen.getByTestId("dashboard-page-stub")).toBeInTheDocument();
    });
  });
});
