interface ReextractionFailedBannerProps {
  message?: string | null | undefined;
}

export function ReextractionFailedBanner({ message }: ReextractionFailedBannerProps) {
  return (
    <div data-testid="reextraction-failed-banner" role="alert">
      <strong>Re-extraction failed.</strong>
      <span data-testid="reextraction-failed-message">
        {message && message.trim().length > 0
          ? message
          : "The new document type could not be applied. Prior values are preserved below."}
      </span>
    </div>
  );
}
