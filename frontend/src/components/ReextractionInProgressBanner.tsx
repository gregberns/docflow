interface ReextractionInProgressBannerProps {
  newDocumentType?: string | null | undefined;
}

export function ReextractionInProgressBanner({
  newDocumentType,
}: ReextractionInProgressBannerProps) {
  const label = newDocumentType ?? "the new type";
  return (
    <div data-testid="reextraction-in-progress-banner" role="status">
      <span data-testid="spinner" aria-hidden="true">
        ⟳
      </span>
      <span>Re-extracting as {label}…</span>
    </div>
  );
}
