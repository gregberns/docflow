import type { DashboardStats } from "../types/readModels";

const STAT_KEYS: ReadonlyArray<{
  key: keyof DashboardStats;
  label: string;
  valueClass: string;
}> = [
  { key: "inProgress", label: "In Progress", valueClass: "text-brand-blue" },
  { key: "awaitingReview", label: "Awaiting Review", valueClass: "text-warn" },
  { key: "flagged", label: "Flagged", valueClass: "text-danger-soft" },
  { key: "filedThisMonth", label: "Filed This Month", valueClass: "text-success" },
];

interface DashboardStatsBarProps {
  stats: DashboardStats;
}

export function DashboardStatsBar({ stats }: DashboardStatsBarProps) {
  return (
    <section data-testid="dashboard-stats" className="mb-5 flex gap-3">
      {STAT_KEYS.map(({ key, label, valueClass }) => (
        <div
          key={key}
          data-testid={`stat-${key}`}
          className="flex-1 rounded-lg border border-neutral-200 bg-card px-[18px] py-[14px]"
        >
          <div data-testid={`stat-${key}-value`} className={`text-24 font-bold ${valueClass}`}>
            {stats[key]}
          </div>
          <div className="mt-0.5 text-12 text-neutral-500">{label}</div>
        </div>
      ))}
    </section>
  );
}
