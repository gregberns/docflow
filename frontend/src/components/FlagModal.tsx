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
    <div role="dialog" aria-modal="true" data-testid="flag-modal">
      <form data-testid="flag-modal-form" onSubmit={onSubmit}>
        <label data-testid="flag-modal-comment-field">
          <span>Flag comment</span>
          <textarea
            data-testid="flag-modal-comment"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            required
            aria-required="true"
          />
        </label>
        <div data-testid="flag-modal-actions">
          <button
            type="button"
            data-testid="flag-modal-cancel"
            onClick={onCancel}
            disabled={flag.isPending}
          >
            Cancel
          </button>
          <button type="submit" data-testid="flag-modal-submit" disabled={submitDisabled}>
            Submit
          </button>
        </div>
      </form>
    </div>
  );
}
