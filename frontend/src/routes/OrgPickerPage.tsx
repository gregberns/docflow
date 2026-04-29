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
    <main
      data-testid="org-picker-page"
      className="flex min-h-screen flex-col items-center justify-center bg-surface px-6 py-12"
    >
      <header className="mb-10 flex flex-col items-center">
        <h1 className="mb-2 text-32 font-bold tracking-[-1px] text-brand-navy">
          Doc<span className="text-brand-blue">Flow</span>
        </h1>
        <p className="text-14 text-neutral-500">Select an organization to continue</p>
      </header>
      {isLoading && (
        <p
          data-testid="org-picker-loading"
          className="mx-auto mt-8 max-w-md rounded-lg border border-neutral-200 bg-card px-6 py-4 text-center text-13 text-neutral-500"
        >
          Loading organizations…
        </p>
      )}
      {isError && (
        <p
          data-testid="org-picker-error"
          role="alert"
          className="mx-auto mt-8 max-w-md rounded-lg border border-danger-soft bg-stage-rejected-bg px-6 py-4 text-center text-13 text-danger"
        >
          {error instanceof Error ? error.message : "Unable to load organizations."}
        </p>
      )}
      {data && (
        <section
          data-testid="org-grid"
          className="grid w-full max-w-[780px] grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
        >
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
