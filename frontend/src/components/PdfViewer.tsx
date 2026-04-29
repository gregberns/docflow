import { useMemo, useState } from "react";
import { Document, Page } from "react-pdf";

interface PdfViewerProps {
  fileUrl: string;
}

function fileNameFromUrl(url: string): string {
  const cleaned = url.split("?")[0]?.split("#")[0] ?? url;
  const segments = cleaned.split("/").filter(Boolean);
  return segments[segments.length - 1] ?? cleaned;
}

export function PdfViewer({ fileUrl }: PdfViewerProps) {
  const [loadError, setLoadError] = useState<string | null>(null);
  const [numPages, setNumPages] = useState(0);

  const file = useMemo(() => ({ url: fileUrl }), [fileUrl]);
  const fileName = fileNameFromUrl(fileUrl);

  if (loadError) {
    return (
      <div
        data-testid="pdf-viewer"
        data-pdf-state="error"
        className="flex flex-1 items-center justify-center bg-pdf-panel p-6"
      >
        <p
          data-testid="pdf-load-error"
          role="alert"
          className="max-w-md rounded-lg border border-danger-soft bg-card px-6 py-4 text-center text-13 text-danger"
        >
          Failed to load PDF: {loadError}
        </p>
      </div>
    );
  }

  return (
    <div
      data-testid="pdf-viewer"
      data-pdf-state="ok"
      className="flex h-full min-h-0 flex-1 flex-col bg-pdf-panel"
    >
      <div className="flex h-10 flex-shrink-0 items-center gap-2 border-b border-pdf-toolbar-border bg-pdf-toolbar-bg px-3">
        <button
          type="button"
          aria-label="Zoom out"
          className="flex h-7 w-7 items-center justify-center rounded-sm bg-white/[0.08] text-14 text-neutral-300 transition-colors hover:bg-white/[0.15]"
        >
          {"−"}
        </button>
        <button
          type="button"
          aria-label="Zoom in"
          className="flex h-7 w-7 items-center justify-center rounded-sm bg-white/[0.08] text-14 text-neutral-300 transition-colors hover:bg-white/[0.15]"
        >
          {"+"}
        </button>
        <span className="ml-2 flex-1 truncate text-12 text-neutral-400">{fileName}</span>
        {numPages > 0 ? (
          <span className="text-11 text-neutral-400">Page 1 of {numPages}</span>
        ) : null}
      </div>
      <div className="flex flex-1 items-start justify-center overflow-auto px-10 py-12">
        <Document
          file={file}
          onLoadSuccess={(pdf) => setNumPages(pdf.numPages)}
          onLoadError={(error) => setLoadError(error.message)}
          loading={
            <p
              data-testid="pdf-loading"
              className="mx-auto mt-8 max-w-md rounded-lg border border-neutral-200 bg-card px-6 py-4 text-center text-13 text-neutral-500"
            >
              Loading PDF…
            </p>
          }
        >
          {Array.from({ length: numPages }, (_, i) => (
            <Page key={i} pageNumber={i + 1} />
          ))}
        </Document>
      </div>
    </div>
  );
}
