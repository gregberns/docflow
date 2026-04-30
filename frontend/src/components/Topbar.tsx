import { resolveOrgIcon } from "../util/orgIcon";

type TopbarProps = {
  orgName?: string;
  orgIcon?: string;
  onSwitchOrg?: () => void;
};

export function Topbar({ orgName, orgIcon, onSwitchOrg }: TopbarProps) {
  return (
    <div className="flex h-[52px] w-full items-center justify-between bg-[#1a1a2e] px-6 text-white">
      <div className="text-18 font-bold tracking-[-0.5px]">
        Doc<span className="text-[#6c9bff]">Flow</span>
      </div>
      {orgName ? (
        <button
          type="button"
          onClick={onSwitchOrg}
          className="flex items-center gap-2 rounded-md bg-white/[0.08] px-3 py-1 text-13 transition-colors hover:bg-white/[0.12]"
        >
          {orgIcon ? <span className="text-16 leading-none">{resolveOrgIcon(orgIcon)}</span> : null}
          <span className="font-semibold text-white">{orgName}</span>
          <span className="ml-1 text-[11px] text-[#6c9bff]">Switch</span>
        </button>
      ) : null}
    </div>
  );
}
