interface ReextractionInProgressBannerProps {
  newDocumentType?: string | null | undefined;
}

export function ReextractionInProgressBanner({
  newDocumentType,
}: ReextractionInProgressBannerProps) {
  const label = newDocumentType ?? "the new type";
  return (
    <div
      data-testid="reextraction-in-progress-banner"
      role="status"
      className="mb-5 flex items-center gap-2 rounded-lg border border-stage-classify-fg/20 bg-stage-classify-bg px-4 py-3 text-13 font-medium text-stage-classify-fg"
    >
      <span data-testid="spinner" aria-hidden="true" className="text-16 leading-none">
        ⟳
      </span>
      <span>Re-extracting as {label}…</span>
    </div>
  );
}
