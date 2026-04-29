import { useMemo, useState } from "react";
import { Document, Page } from "react-pdf";

interface PdfViewerProps {
  fileUrl: string;
}

export function PdfViewer({ fileUrl }: PdfViewerProps) {
  const [loadError, setLoadError] = useState<string | null>(null);
  const [numPages, setNumPages] = useState(0);

  const file = useMemo(() => ({ url: fileUrl }), [fileUrl]);

  if (loadError) {
    return (
      <div data-testid="pdf-viewer" data-pdf-state="error">
        <p data-testid="pdf-load-error" role="alert">
          Failed to load PDF: {loadError}
        </p>
      </div>
    );
  }

  return (
    <div data-testid="pdf-viewer" data-pdf-state="ok">
      <Document
        file={file}
        onLoadSuccess={(pdf) => setNumPages(pdf.numPages)}
        onLoadError={(error) => setLoadError(error.message)}
        loading={<p data-testid="pdf-loading">Loading PDF…</p>}
      >
        {Array.from({ length: numPages }, (_, i) => (
          <Page key={i} pageNumber={i + 1} />
        ))}
      </Document>
    </div>
  );
}
