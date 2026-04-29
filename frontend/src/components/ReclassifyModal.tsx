import { useDocumentActions } from "../hooks/useDocumentActions";

interface ReclassifyModalProps {
  documentId: string;
  organizationId: string;
  newDocumentType: string;
  previousDocumentType: string;
  onCancel: () => void;
  onConfirmed: (newDocumentType: string) => void;
}

export function ReclassifyModal({
  documentId,
  organizationId,
  newDocumentType,
  previousDocumentType,
  onCancel,
  onConfirmed,
}: ReclassifyModalProps) {
  const { retype } = useDocumentActions({ documentId, organizationId });

  const onConfirm = (): void => {
    retype.mutate(
      { newDocumentType },
      {
        onSuccess: () => onConfirmed(newDocumentType),
      },
    );
  };

  const submitting = retype.isPending;

  return (
    <div role="dialog" aria-modal="true" data-testid="reclassify-modal">
      <h2 data-testid="reclassify-modal-heading">Reclassify document?</h2>
      <p data-testid="reclassify-modal-body">
        Re-extract this document as <strong>{newDocumentType}</strong> instead of{" "}
        <strong>{previousDocumentType}</strong>?
      </p>
      <div data-testid="reclassify-modal-actions">
        <button
          type="button"
          data-testid="reclassify-cancel"
          onClick={onCancel}
          disabled={submitting}
        >
          Cancel
        </button>
        <button
          type="button"
          data-testid="reclassify-confirm"
          onClick={onConfirm}
          disabled={submitting}
        >
          Confirm
        </button>
      </div>
    </div>
  );
}
