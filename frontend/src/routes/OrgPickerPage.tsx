import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { listOrganizations } from "../api/organizations";
import { OrgPickerCard } from "../components/OrgPickerCard";

export function OrgPickerPage() {
  const navigate = useNavigate();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["organizations"],
    queryFn: listOrganizations,
  });

  return (
    <main data-testid="org-picker-page">
      <header>
        <h1>
          Doc<span>Flow</span>
        </h1>
        <p>Select an organization to continue</p>
      </header>
      {isLoading && <p data-testid="org-picker-loading">Loading organizations…</p>}
      {isError && (
        <p data-testid="org-picker-error" role="alert">
          {error instanceof Error ? error.message : "Unable to load organizations."}
        </p>
      )}
      {data && (
        <section data-testid="org-grid">
          {data.map((organization) => (
            <OrgPickerCard
              key={organization.id}
              organization={organization}
              onSelect={(orgId) => navigate(`/org/${orgId}/dashboard`)}
            />
          ))}
        </section>
      )}
    </main>
  );
}
