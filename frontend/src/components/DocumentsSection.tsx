import { useNavigate } from "react-router-dom";
import type { DocumentView } from "../types/readModels";

interface DocumentsSectionProps {
  documents: DocumentView[];
}

export function DocumentsSection({ documents }: DocumentsSectionProps) {
  const navigate = useNavigate();
  return (
    <section data-testid="documents-section">
      <h2>Documents</h2>
      {documents.length === 0 ? (
        <p data-testid="documents-empty">No documents match the current filters.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Document</th>
              <th>Type</th>
              <th>Stage</th>
              <th>Status</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {documents.map((doc) => (
              <tr
                key={doc.documentId}
                data-testid="document-row"
                data-document-id={doc.documentId}
                data-status={doc.currentStatus ?? ""}
                data-doc-type={doc.detectedDocumentType ?? ""}
                onClick={() => navigate(`/documents/${doc.documentId}`)}
              >
                <td>{doc.sourceFilename}</td>
                <td>{doc.detectedDocumentType ?? "—"}</td>
                <td>{doc.currentStageDisplayName ?? "—"}</td>
                <td>{doc.currentStatus ?? "—"}</td>
                <td>{doc.processedAt ?? doc.uploadedAt}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
