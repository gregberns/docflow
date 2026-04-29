interface FlagBannerProps {
  originStage: string;
  comment: string | null;
}

export function FlagBanner({ originStage, comment }: FlagBannerProps) {
  return (
    <div data-testid="flag-banner" role="alert">
      <p>
        <strong>Flagged from</strong> <span data-testid="flag-banner-origin">{originStage}</span>
      </p>
      {comment && comment.trim().length > 0 && <p data-testid="flag-banner-comment">{comment}</p>}
    </div>
  );
}
