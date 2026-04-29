import type { StageSummary } from "../types/workflow";
import type { WorkflowStatus } from "../types/readModels";

export type ProcessingStep = "TEXT_EXTRACTING" | "CLASSIFYING" | "EXTRACTING" | "FAILED";

const PRE_WORKFLOW_STEPS: ReadonlyArray<{ id: ProcessingStep; label: string }> = [
  { id: "TEXT_EXTRACTING", label: "Text Extracting" },
  { id: "CLASSIFYING", label: "Classifying" },
  { id: "EXTRACTING", label: "Extracting" },
];

export type StageState =
  | "current"
  | "upcoming"
  | "done"
  | "failed"
  | "muted"
  | "muted-green"
  | "regressed-amber"
  | "rejected-edge"
  | "rejected-current"
  | "highlighted-pink";

interface StageProgressInFlightProps {
  mode: "in-flight";
  currentStep: ProcessingStep;
  stages: StageSummary[];
}

interface StageProgressProcessedProps {
  mode: "processed";
  stages: StageSummary[];
  currentStageId: string | null;
  currentStatus: WorkflowStatus | null;
  originStage: string | null;
}

export type StageProgressProps = StageProgressInFlightProps | StageProgressProcessedProps;

interface RenderedSegment {
  key: string;
  label: string;
  state: StageState;
  segment: "pre-workflow" | "workflow";
  stageId?: string;
}

function preWorkflowSegmentsForInFlight(currentStep: ProcessingStep): RenderedSegment[] {
  if (currentStep === "FAILED") {
    return PRE_WORKFLOW_STEPS.map((step, idx) => ({
      key: `pre-${step.id}`,
      label: step.label,
      state: idx === 0 ? "failed" : "muted",
      segment: "pre-workflow",
    }));
  }
  const currentIdx = PRE_WORKFLOW_STEPS.findIndex((s) => s.id === currentStep);
  return PRE_WORKFLOW_STEPS.map((step, idx) => ({
    key: `pre-${step.id}`,
    label: step.label,
    state: idx === currentIdx ? "current" : idx < currentIdx ? "done" : "upcoming",
    segment: "pre-workflow",
  }));
}

function preWorkflowSegmentsDone(): RenderedSegment[] {
  return PRE_WORKFLOW_STEPS.map((step) => ({
    key: `pre-${step.id}`,
    label: step.label,
    state: "done",
    segment: "pre-workflow",
  }));
}

function isApprovalStage(stage: StageSummary): boolean {
  return (
    stage.kind === "APPROVAL" ||
    stage.canonicalStatus === "AWAITING_APPROVAL" ||
    stage.canonicalStatus === "APPROVED"
  );
}

function isReviewStage(stage: StageSummary): boolean {
  return stage.kind === "REVIEW" || stage.canonicalStatus === "AWAITING_REVIEW";
}

function isTerminalFiledStage(stage: StageSummary): boolean {
  return stage.kind === "TERMINAL" || stage.canonicalStatus === "FILED";
}

function workflowSegmentsInFlight(stages: StageSummary[]): RenderedSegment[] {
  return stages.map((stage) => ({
    key: `wf-${stage.id}`,
    label: stage.displayName,
    state: "upcoming",
    segment: "workflow",
    stageId: stage.id,
  }));
}

function workflowSegmentsForProcessed(
  stages: StageSummary[],
  currentStageId: string | null,
  currentStatus: WorkflowStatus | null,
  originStage: string | null,
): RenderedSegment[] {
  if (currentStatus === "REJECTED") {
    return workflowSegmentsForRejected(stages, currentStageId);
  }
  if (currentStatus === "FILED") {
    return stages.map((stage) => ({
      key: `wf-${stage.id}`,
      label: stage.displayName,
      state: "done",
      segment: "workflow",
      stageId: stage.id,
    }));
  }

  const currentIdx = stages.findIndex((s) => s.id === currentStageId);
  const flagged =
    Boolean(originStage) && currentStageId !== null && isReview(stages, currentStageId);
  if (flagged) {
    return workflowSegmentsForFlagged(stages, currentStageId, originStage);
  }
  return stages.map((stage, idx) => {
    if (currentIdx === -1) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "upcoming" as StageState,
        segment: "workflow" as const,
        stageId: stage.id,
      };
    }
    if (idx < currentIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "done" as StageState,
        segment: "workflow" as const,
        stageId: stage.id,
      };
    }
    if (idx === currentIdx) {
      const isApproval = isApprovalStage(stage);
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: isApproval ? ("highlighted-pink" as StageState) : ("current" as StageState),
        segment: "workflow" as const,
        stageId: stage.id,
      };
    }
    return {
      key: `wf-${stage.id}`,
      label: stage.displayName,
      state: "upcoming" as StageState,
      segment: "workflow" as const,
      stageId: stage.id,
    };
  });
}

function isReview(stages: StageSummary[], stageId: string): boolean {
  const stage = stages.find((s) => s.id === stageId);
  return stage ? isReviewStage(stage) : false;
}

function workflowSegmentsForFlagged(
  stages: StageSummary[],
  currentStageId: string,
  originStage: string | null,
): RenderedSegment[] {
  const reviewIdx = stages.findIndex((s) => s.id === currentStageId);
  const originIdx = originStage ? stages.findIndex((s) => s.id === originStage) : -1;
  return stages.map((stage, idx) => {
    if (idx === reviewIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "current",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    if (originIdx !== -1 && idx > reviewIdx && idx <= originIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "regressed-amber",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    if (originIdx !== -1 && idx <= originIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "muted-green",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    return {
      key: `wf-${stage.id}`,
      label: stage.displayName,
      state: "upcoming",
      segment: "workflow",
      stageId: stage.id,
    };
  });
}

function workflowSegmentsForRejected(
  stages: StageSummary[],
  currentStageId: string | null,
): RenderedSegment[] {
  const rejectedIdx = stages.findIndex((s) => s.id === currentStageId);
  const segments: RenderedSegment[] = stages.map((stage, idx) => {
    if (rejectedIdx !== -1 && idx === rejectedIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "rejected-current",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    if (rejectedIdx !== -1 && idx === rejectedIdx - 1) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "rejected-edge",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    if (rejectedIdx !== -1 && idx < rejectedIdx) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "done",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    if (isApprovalStage(stage) || isTerminalFiledStage(stage)) {
      return {
        key: `wf-${stage.id}`,
        label: stage.displayName,
        state: "muted",
        segment: "workflow",
        stageId: stage.id,
      };
    }
    return {
      key: `wf-${stage.id}`,
      label: stage.displayName,
      state: "upcoming",
      segment: "workflow",
      stageId: stage.id,
    };
  });

  const hasRejectedNode = stages.some(
    (s) => s.id === currentStageId && s.canonicalStatus === "REJECTED",
  );
  if (!hasRejectedNode) {
    const reachedIdx = lastReachedIdxBeforeRejection(stages, currentStageId);
    for (let i = 0; i < segments.length; i += 1) {
      if (i <= reachedIdx) {
        segments[i] = { ...segments[i]!, state: "done" };
      } else if (isApprovalStage(stages[i]!) || isTerminalFiledStage(stages[i]!)) {
        segments[i] = { ...segments[i]!, state: "muted" };
      }
    }
    if (reachedIdx >= 0 && reachedIdx < segments.length) {
      segments[reachedIdx] = { ...segments[reachedIdx]!, state: "rejected-edge" };
    }
    segments.push({
      key: "wf-rejected-terminal",
      label: "Rejected",
      state: "rejected-current",
      segment: "workflow",
      stageId: "rejected",
    });
  }
  return segments;
}

function lastReachedIdxBeforeRejection(
  stages: StageSummary[],
  currentStageId: string | null,
): number {
  if (!currentStageId) return -1;
  const idx = stages.findIndex((s) => s.id === currentStageId);
  if (idx === -1) return stages.length - 1;
  return idx;
}

function colorClass(state: StageState): string {
  switch (state) {
    case "failed":
    case "rejected-edge":
    case "rejected-current":
      return "stage-segment-red";
    case "regressed-amber":
      return "stage-segment-amber";
    case "highlighted-pink":
      return "stage-segment-pink";
    case "muted":
      return "stage-segment-muted";
    case "muted-green":
      return "stage-segment-muted-green";
    case "done":
      return "stage-segment-done";
    case "current":
      return "stage-segment-current";
    case "upcoming":
    default:
      return "stage-segment-upcoming";
  }
}

export function StageProgress(props: StageProgressProps) {
  const segments: RenderedSegment[] =
    props.mode === "in-flight"
      ? [
          ...preWorkflowSegmentsForInFlight(props.currentStep),
          ...workflowSegmentsInFlight(props.stages),
        ]
      : [
          ...preWorkflowSegmentsDone(),
          ...workflowSegmentsForProcessed(
            props.stages,
            props.currentStageId,
            props.currentStatus,
            props.originStage,
          ),
        ];

  return (
    <ol data-testid="stage-progress">
      {segments.map((seg) => (
        <li
          key={seg.key}
          data-testid={`stage-${seg.segment}-${seg.stageId ?? seg.key}`}
          data-segment={seg.segment}
          data-state={seg.state}
          data-stage-id={seg.stageId ?? null}
          className={colorClass(seg.state)}
        >
          {seg.label}
        </li>
      ))}
    </ol>
  );
}
