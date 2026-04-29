import { Outlet, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { listOrganizations } from "../api/organizations";
import { getDocument } from "../api/documents";
import { Topbar } from "./Topbar";

export function AppShell() {
  const navigate = useNavigate();
  const { orgId, documentId } = useParams<{ orgId?: string; documentId?: string }>();

  const { data: organizations } = useQuery({
    queryKey: ["organizations"],
    queryFn: listOrganizations,
  });

  const { data: document } = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId ?? ""),
    enabled: typeof documentId === "string" && documentId.length > 0,
  });

  const resolvedOrgId = orgId ?? document?.organizationId;
  const org = organizations?.find((candidate) => candidate.id === resolvedOrgId);

  const topbarProps: { orgName?: string; orgIcon?: string; onSwitchOrg: () => void } = {
    onSwitchOrg: () => navigate("/"),
  };
  if (org?.name) {
    topbarProps.orgName = org.name;
  }
  if (org?.icon) {
    topbarProps.orgIcon = org.icon;
  }

  return (
    <>
      <Topbar {...topbarProps} />
      <Outlet />
    </>
  );
}
