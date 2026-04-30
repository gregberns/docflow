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
        <div className="flex items-center gap-3">
          {orgIcon ? (
            <span className="flex h-7 w-7 items-center justify-center rounded-md bg-[#6c9bff] text-14 font-semibold text-white">
              {resolveOrgIcon(orgIcon)}
            </span>
          ) : null}
          <span className="text-13 text-[#c4c9d9]">{orgName}</span>
          {onSwitchOrg ? (
            <button
              type="button"
              onClick={onSwitchOrg}
              className="rounded-md bg-white/[0.08] px-3 py-1 text-12 text-white transition-colors hover:bg-white/[0.12]"
            >
              Switch org
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
