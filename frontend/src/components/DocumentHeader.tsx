import type { DocumentView } from "../types/readModels";

interface DocumentHeaderProps {
  document: DocumentView;
}

export function DocumentHeader({ document }: DocumentHeaderProps) {
  const {
    sourceFilename,
    detectedDocumentType,
    currentStageDisplayName,
    currentStatus,
    uploadedAt,
  } = document;
  return (
    <header data-testid="document-header">
      <h1 data-testid="document-filename">{sourceFilename}</h1>
      <dl>
        {detectedDocumentType && (
          <>
            <dt>Type</dt>
            <dd data-testid="document-doc-type">{detectedDocumentType}</dd>
          </>
        )}
        {currentStageDisplayName && (
          <>
            <dt>Stage</dt>
            <dd data-testid="document-stage">{currentStageDisplayName}</dd>
          </>
        )}
        {currentStatus && (
          <>
            <dt>Status</dt>
            <dd data-testid="document-status">{currentStatus}</dd>
          </>
        )}
        <dt>Uploaded</dt>
        <dd data-testid="document-uploaded-at">{uploadedAt}</dd>
      </dl>
    </header>
  );
}
