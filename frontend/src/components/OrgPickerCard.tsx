import type { OrganizationListItem } from "../types/readModels";
import { formatDocType } from "../util/formatters";
import { resolveOrgIcon } from "../util/orgIcon";

interface OrgPickerCardProps {
  organization: OrganizationListItem;
  onSelect: (orgId: string) => void;
}

const PASTEL_TILES = [
  "bg-stage-extract-bg",
  "bg-stage-review-bg",
  "bg-stage-approval-bg",
  "bg-stage-classify-bg",
  "bg-stage-filed-bg",
] as const;

const ICON_TILE: Record<string, string> = {
  "icon-bistro": "bg-[#fef3c7]",
  "icon-legal": "bg-[#ede9fe]",
  "icon-construction": "bg-[#fce7f3]",
};

const ICON_INDUSTRY: Record<string, string> = {
  "icon-bistro": "Restaurant chain",
  "icon-legal": "Law firm",
  "icon-construction": "General contractor",
};

function glyphFor(icon: string | null | undefined): string {
  return resolveOrgIcon(icon ?? undefined);
}

function tileClassFor(icon: string | null | undefined): string {
  const key = icon ?? "";
  if (ICON_TILE[key]) return ICON_TILE[key]!;
  let hash = 0;
  for (let i = 0; i < key.length; i += 1) {
    hash = (hash * 31 + key.charCodeAt(i)) | 0;
  }
  const index = Math.abs(hash) % PASTEL_TILES.length;
  return PASTEL_TILES[index] ?? PASTEL_TILES[0]!;
}

function subtitleFor(icon: string | null | undefined, docTypes: string[]): string {
  const industry = ICON_INDUSTRY[icon ?? ""];
  const formatted = docTypes.map(formatDocType).join(", ");
  return industry ? `${industry} — ${formatted}` : formatted;
}

export function OrgPickerCard({ organization, onSelect }: OrgPickerCardProps) {
  const { id, name, icon, docTypes, inProgressCount, filedCount } = organization;
  const tileClass = tileClassFor(icon);
  const glyph = glyphFor(icon);
  const subtitle = subtitleFor(icon, docTypes);

  return (
    <article data-testid="org-card" data-org-id={id}>
      <button
        type="button"
        onClick={() => onSelect(id)}
        className="group flex h-full w-full flex-col items-center rounded-xl border-2 border-neutral-200 bg-card px-6 py-7 text-center transition-all duration-150 hover:-translate-y-0.5 hover:border-brand-blue hover:shadow-[0_4px_12px_rgba(108,155,255,0.15)]"
      >
        <span
          aria-hidden="true"
          className={`mb-4 flex h-14 w-14 items-center justify-center rounded-xl text-24 leading-none ${tileClass}`}
        >
          {glyph}
        </span>
        <h2 className="mb-1 text-16 font-bold text-brand-navy">{name}</h2>
        <ul className="mb-4 list-none text-12 leading-snug text-neutral-500">
          <li>{subtitle}</li>
        </ul>
        <div className="flex w-full justify-center gap-6 border-t border-neutral-100 pt-3">
          <div data-testid="badge-in-progress" className="flex flex-col items-center">
            <span className="text-16 font-bold text-brand-navy">{inProgressCount}</span>
            <span className="text-10 font-medium uppercase tracking-[0.5px] text-neutral-400">
              In Progress
            </span>
          </div>
          <div data-testid="badge-filed" className="flex flex-col items-center">
            <span className="text-16 font-bold text-brand-navy">{filedCount}</span>
            <span className="text-10 font-medium uppercase tracking-[0.5px] text-neutral-400">
              Filed
            </span>
          </div>
        </div>
      </button>
    </article>
  );
}
