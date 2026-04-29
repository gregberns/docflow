interface ReextractionFailedBannerProps {
  message?: string | null | undefined;
}

export function ReextractionFailedBanner({ message }: ReextractionFailedBannerProps) {
  return (
    <div
      data-testid="reextraction-failed-banner"
      role="alert"
      className="mb-5 rounded-lg border border-[#fecaca] bg-stage-rejected-bg px-4 py-3 text-13 text-stage-rejected-fg"
    >
      <strong className="mr-1.5 font-semibold">Re-extraction failed.</strong>
      <span data-testid="reextraction-failed-message" className="font-normal text-brand-navy">
        {message && message.trim().length > 0
          ? message
          : "The new document type could not be applied. Prior values are preserved below."}
      </span>
    </div>
  );
}
