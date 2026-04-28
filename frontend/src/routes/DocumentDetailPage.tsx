import { useParams } from "react-router-dom";

export function DocumentDetailPage() {
  const { documentId } = useParams<{ documentId: string }>();
  return <main data-testid="document-detail-page" data-document-id={documentId} />;
}
