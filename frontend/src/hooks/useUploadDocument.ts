import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { uploadDocument } from "../api/documents";
import type { DashboardResponse, ProcessingItem, UploadAccepted } from "../types/readModels";

export interface UploadDocumentVariables {
  file: File;
}

interface UploadContext {
  previousDashboard: DashboardResponse | undefined;
  optimisticId: string;
}

const OPTIMISTIC_PREFIX = "optimistic-";

function makeOptimisticId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return `${OPTIMISTIC_PREFIX}${crypto.randomUUID()}`;
  }
  return `${OPTIMISTIC_PREFIX}${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function buildOptimisticItem(file: File, optimisticId: string): ProcessingItem {
  return {
    processingDocumentId: optimisticId,
    storedDocumentId: optimisticId,
    sourceFilename: file.name,
    currentStep: "TEXT_EXTRACTING",
    lastError: null,
    createdAt: new Date().toISOString(),
  };
}

export function useUploadDocument(
  orgId: string | undefined,
): UseMutationResult<UploadAccepted, Error, UploadDocumentVariables, UploadContext> {
  const queryClient = useQueryClient();
  const dashboardKey = ["dashboard", orgId];

  return useMutation<UploadAccepted, Error, UploadDocumentVariables, UploadContext>({
    mutationFn: ({ file }) => {
      if (!orgId) {
        return Promise.reject(new Error("Cannot upload without an organization id"));
      }
      return uploadDocument(orgId, file);
    },
    onMutate: async ({ file }) => {
      await queryClient.cancelQueries({ queryKey: dashboardKey });
      const previousDashboard = queryClient.getQueryData<DashboardResponse>(dashboardKey);
      const optimisticId = makeOptimisticId();
      const optimisticItem = buildOptimisticItem(file, optimisticId);
      if (previousDashboard) {
        queryClient.setQueryData<DashboardResponse>(dashboardKey, {
          ...previousDashboard,
          processing: [optimisticItem, ...previousDashboard.processing],
        });
      } else {
        queryClient.setQueryData<DashboardResponse>(dashboardKey, {
          processing: [optimisticItem],
          documents: [],
          stats: { inProgress: 1, awaitingReview: 0, flagged: 0, filedThisMonth: 0 },
          nextCursor: null,
        });
      }
      return { previousDashboard, optimisticId };
    },
    onError: (_error, _variables, context) => {
      if (!context) {
        return;
      }
      if (context.previousDashboard) {
        queryClient.setQueryData<DashboardResponse>(dashboardKey, context.previousDashboard);
      } else {
        queryClient.removeQueries({ queryKey: dashboardKey, exact: true });
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: dashboardKey });
    },
  });
}
