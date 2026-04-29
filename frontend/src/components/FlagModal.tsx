import { useState } from "react";
import { useDocumentActions } from "../hooks/useDocumentActions";

interface FlagModalProps {
  documentId: string;
  organizationId: string;
  onCancel: () => void;
  onSubmitted: () => void;
}

export function FlagModal({ documentId, organizationId, onCancel, onSubmitted }: FlagModalProps) {
  const [comment, setComment] = useState("");
  const { flag } = useDocumentActions({ documentId, organizationId });

  const trimmed = comment.trim();
  const submitDisabled = trimmed.length === 0 || flag.isPending;

  const onSubmit = (event: React.FormEvent): void => {
    event.preventDefault();
    if (submitDisabled) return;
    flag.mutate(
      { comment },
      {
        onSuccess: () => onSubmitted(),
      },
    );
  };

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40"
      role="dialog"
      aria-modal="true"
      data-testid="flag-modal"
    >
      <form
        data-testid="flag-modal-form"
        onSubmit={onSubmit}
        className="w-[440px] overflow-hidden rounded-xl bg-card shadow-[0_20px_60px_rgba(0,0,0,0.3)]"
      >
        <div className="flex items-center gap-2.5 border-b border-neutral-100 px-6 pb-4 pt-5">
          <div
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md"
            style={{ background: "#fef3c7", color: "#f59e0b" }}
            aria-hidden="true"
          />
          <div>
            <div className="text-16 font-bold text-brand-navy">Flag for Review</div>
            <div className="mt-0.5 text-12 text-neutral-500">
              Document will be sent back to the Review stage.
            </div>
          </div>
        </div>
        <div className="px-6 py-5">
          <label data-testid="flag-modal-comment-field" className="block">
            <span className="mb-1.5 block text-12 font-semibold text-neutral-700">
              What needs to be addressed?
            </span>
            <textarea
              data-testid="flag-modal-comment"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              required
              aria-required="true"
              placeholder="Describe the issue..."
              className="block min-h-[100px] w-full resize-y rounded-md border border-neutral-300 bg-card px-3 py-2.5 font-sans text-13 leading-normal text-brand-navy outline-none placeholder:text-neutral-400 focus:border-warn focus:ring-2 focus:ring-warn/15"
            />
          </label>
          <div className="mt-1.5 text-11 text-neutral-400">
            A comment is required. The reviewer will see this when the document returns to Review.
          </div>
        </div>
        <div
          data-testid="flag-modal-actions"
          className="flex justify-end gap-2 border-t border-neutral-100 px-6 py-4"
        >
          <button
            type="button"
            data-testid="flag-modal-cancel"
            onClick={onCancel}
            disabled={flag.isPending}
            className="flex h-9 items-center gap-1.5 rounded-md border border-neutral-300 bg-card px-[18px] text-13 font-semibold text-neutral-500 transition-colors duration-150 hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="submit"
            data-testid="flag-modal-submit"
            disabled={submitDisabled}
            className="flex h-9 items-center gap-1.5 rounded-md bg-warn px-[18px] text-13 font-semibold text-white transition-colors duration-150 hover:bg-[#d97706] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-warn"
          >
            Submit
          </button>
        </div>
      </form>
    </div>
  );
}
