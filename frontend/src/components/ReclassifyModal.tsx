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
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40"
      role="dialog"
      aria-modal="true"
      data-testid="reclassify-modal"
    >
      <div className="w-[440px] overflow-hidden rounded-xl bg-card shadow-[0_20px_60px_rgba(0,0,0,0.3)]">
        <div className="flex items-center gap-2.5 border-b border-neutral-100 px-6 pb-4 pt-5">
          <div
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md"
            style={{ background: "#fef3c7", color: "#f59e0b" }}
            aria-hidden="true"
          />
          <div>
            <h2
              data-testid="reclassify-modal-heading"
              className="text-16 font-bold text-brand-navy"
            >
              Reclassify document?
            </h2>
            <div className="mt-0.5 text-12 text-neutral-500">
              This will re-process the document.
            </div>
          </div>
        </div>
        <div className="px-6 py-5">
          <p
            data-testid="reclassify-modal-body"
            className="text-13 leading-relaxed text-neutral-700"
          >
            Re-extract this document as{" "}
            <strong className="text-brand-navy">{newDocumentType}</strong> instead of{" "}
            <strong className="text-brand-navy">{previousDocumentType}</strong>?
          </p>
        </div>
        <div
          data-testid="reclassify-modal-actions"
          className="flex justify-end gap-2 border-t border-neutral-100 px-6 py-4"
        >
          <button
            type="button"
            data-testid="reclassify-cancel"
            onClick={onCancel}
            disabled={submitting}
            className="flex h-9 items-center gap-1.5 rounded-md border border-neutral-300 bg-card px-[18px] text-13 font-semibold text-neutral-500 transition-colors duration-150 hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            data-testid="reclassify-confirm"
            onClick={onConfirm}
            disabled={submitting}
            className="flex h-9 items-center gap-1.5 rounded-md bg-warn px-[18px] text-13 font-semibold text-white transition-colors duration-150 hover:bg-[#d97706] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-warn"
          >
            Confirm
          </button>
        </div>
      </div>
    </div>
  );
}
