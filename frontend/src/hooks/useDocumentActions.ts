import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { applyAction, retypeDocument } from "../api/workflows";
import { DocflowApiError } from "../api/client";
import type { DocumentView, RetypeAccepted } from "../types/readModels";
import { notify } from "../util/notify";

type FieldErrorSetter = (path: string, message: string) => void;

export interface UseDocumentActionsOptions {
  documentId: string;
  organizationId: string;
  setFieldError?: FieldErrorSetter;
}

export interface FlagVariables {
  comment: string;
}

export interface RetypeVariables {
  newDocumentType: string;
}

export interface UseDocumentActionsResult {
  approve: UseMutationResult<DocumentView, Error, void>;
  reject: UseMutationResult<DocumentView, Error, void>;
  flag: UseMutationResult<DocumentView, Error, FlagVariables>;
  resolve: UseMutationResult<DocumentView, Error, void>;
  retype: UseMutationResult<RetypeAccepted, Error, RetypeVariables>;
}

const SAVE_FAILED_MESSAGE = "Save failed; refresh and retry";

function applyValidationDetails(error: unknown, setFieldError?: FieldErrorSetter): boolean {
  if (!setFieldError || !(error instanceof DocflowApiError) || error.code !== "VALIDATION_FAILED") {
    return false;
  }
  let mapped = false;
  for (const detail of error.details) {
    if (detail.path && detail.message) {
      setFieldError(detail.path, detail.message);
      mapped = true;
    }
  }
  return mapped;
}

export function useDocumentActions(options: UseDocumentActionsOptions): UseDocumentActionsResult {
  const { documentId, organizationId, setFieldError } = options;
  const queryClient = useQueryClient();
  const documentKey = ["document", documentId];
  const dashboardKey = ["dashboard", organizationId];

  const onSuccess = () => {
    void queryClient.invalidateQueries({ queryKey: documentKey });
    void queryClient.invalidateQueries({ queryKey: dashboardKey });
  };

  const onError = (error: unknown) => {
    const mapped = applyValidationDetails(error, setFieldError);
    if (!mapped) {
      notify(SAVE_FAILED_MESSAGE);
    }
    void queryClient.invalidateQueries({ queryKey: documentKey });
  };

  const approve = useMutation<DocumentView, Error, void>({
    mutationFn: () => applyAction(documentId, { action: "Approve" }),
    onSuccess,
    onError,
  });

  const reject = useMutation<DocumentView, Error, void>({
    mutationFn: () => applyAction(documentId, { action: "Reject" }),
    onSuccess,
    onError,
  });

  const flag = useMutation<DocumentView, Error, FlagVariables>({
    mutationFn: ({ comment }) => applyAction(documentId, { action: "Flag", comment }),
    onSuccess,
    onError,
  });

  const resolve = useMutation<DocumentView, Error, void>({
    mutationFn: () => applyAction(documentId, { action: "Resolve" }),
    onSuccess,
    onError,
  });

  const retype = useMutation<RetypeAccepted, Error, RetypeVariables>({
    mutationFn: ({ newDocumentType }) => retypeDocument(documentId, { newDocumentType }),
    onSuccess,
    onError,
  });

  return { approve, reject, flag, resolve, retype };
}
