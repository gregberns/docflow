interface FlagBannerProps {
  originStage: string;
  comment: string | null;
}

export function FlagBanner({ originStage, comment }: FlagBannerProps) {
  return (
    <div
      data-testid="flag-banner"
      role="alert"
      className="mb-5 rounded-lg border border-[#fed7aa] bg-[#fff7ed] px-4 py-3.5"
    >
      <p className="mb-2 flex items-center gap-1.5 text-12 font-bold text-[#c2410c]">
        <span>Flagged from</span>{" "}
        <span data-testid="flag-banner-origin" className="font-normal text-[#9a3412]">
          {originStage}
        </span>
      </p>
      {comment && comment.trim().length > 0 && (
        <p
          data-testid="flag-banner-comment"
          className="rounded-md border border-[#fed7aa] bg-card px-3 py-2.5 text-13 leading-snug text-brand-navy"
        >
          {comment}
        </p>
      )}
    </div>
  );
}
