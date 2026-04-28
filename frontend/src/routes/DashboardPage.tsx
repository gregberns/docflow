import { useParams } from "react-router-dom";

export function DashboardPage() {
  const { orgId } = useParams<{ orgId: string }>();
  return <main data-testid="dashboard-page" data-org-id={orgId} />;
}
