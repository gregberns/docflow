import type { DashboardStats } from "../types/readModels";

interface DashboardStatsBarProps {
  stats: DashboardStats;
}

const STAT_KEYS: ReadonlyArray<{ key: keyof DashboardStats; label: string }> = [
  { key: "inProgress", label: "In Progress" },
  { key: "awaitingReview", label: "Awaiting Review" },
  { key: "flagged", label: "Flagged" },
  { key: "filedThisMonth", label: "Filed This Month" },
];

export function DashboardStatsBar({ stats }: DashboardStatsBarProps) {
  return (
    <section data-testid="dashboard-stats">
      {STAT_KEYS.map(({ key, label }) => (
        <div key={key} data-testid={`stat-${key}`}>
          <div data-testid={`stat-${key}-value`}>{stats[key]}</div>
          <div>{label}</div>
        </div>
      ))}
    </section>
  );
}
