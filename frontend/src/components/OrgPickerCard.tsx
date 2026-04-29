import type { OrganizationListItem } from "../types/readModels";

interface OrgPickerCardProps {
  organization: OrganizationListItem;
  onSelect: (orgId: string) => void;
}

export function OrgPickerCard({ organization, onSelect }: OrgPickerCardProps) {
  const { id, name, icon, docTypes, inProgressCount, filedCount } = organization;
  return (
    <article data-testid="org-card" data-org-id={id}>
      <button type="button" onClick={() => onSelect(id)}>
        <img src={icon} alt={name} />
        <h2>{name}</h2>
        <ul>
          {docTypes.map((docType) => (
            <li key={docType}>{docType}</li>
          ))}
        </ul>
        <div>
          <span data-testid="badge-in-progress">In Progress {inProgressCount}</span>
          <span data-testid="badge-filed">Filed {filedCount}</span>
        </div>
      </button>
    </article>
  );
}
