import type { StageSummary } from "../types/workflow";
import type { WorkflowStatus } from "../types/readModels";

export type ProcessingStep = "TEXT_EXTRACTING" | "CLASSIFYING" | "EXTRACTING" | "FAILED";

const PRE_WORKFLOW_STEPS: ReadonlyArray<{ id: ProcessingStep; label: string }> = [
  { id: "TEXT_EXTRACTING", label: "Upload" },
  { id: "CLASSIFYING", label: "Classify" },
  { id: "EXTRACTING", label: "Extract" },
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

function isRejectedTerminal(stage: StageSummary): boolean {
  return stage.kind === "TERMINAL" && stage.canonicalStatus === "REJECTED";
}

function workflowSegmentsInFlight(stages: StageSummary[]): RenderedSegment[] {
  return stages
    .filter((stage) => !isRejectedTerminal(stage))
    .map((stage) => ({
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
  const nonRejected = stages.filter((s) => !isRejectedTerminal(s));
  if (currentStatus === "FILED") {
    return nonRejected.map((stage) => ({
      key: `wf-${stage.id}`,
      label: stage.displayName,
      state: "done",
      segment: "workflow",
      stageId: stage.id,
    }));
  }

  const currentIdx = nonRejected.findIndex((s) => s.id === currentStageId);
  const flagged =
    Boolean(originStage) && currentStageId !== null && isReview(nonRejected, currentStageId);
  if (flagged) {
    return workflowSegmentsForFlagged(nonRejected, currentStageId, originStage);
  }
  return nonRejected.map((stage, idx) => {
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
  const itemBase = "relative flex flex-1 flex-col items-center min-w-0";
  switch (state) {
    case "failed":
      return `${itemBase} stage-state-failed text-red-600`;
    case "rejected-edge":
      return `${itemBase} stage-state-rejected-edge text-red-600`;
    case "rejected-current":
      return `${itemBase} stage-state-rejected-current text-red-600 font-semibold`;
    case "regressed-amber":
      return `${itemBase} stage-state-regressed-amber text-amber-500`;
    case "highlighted-pink":
      return `${itemBase} stage-state-highlighted-pink text-pink-700 font-semibold`;
    case "muted":
      return `${itemBase} stage-state-muted text-neutral-300`;
    case "muted-green":
      return `${itemBase} stage-state-muted-green text-emerald-500`;
    case "done":
      return `${itemBase} stage-state-done text-emerald-500`;
    case "current":
      return `${itemBase} stage-state-current text-violet-600 font-semibold`;
    case "upcoming":
    default:
      return `${itemBase} stage-state-upcoming text-neutral-400`;
  }
}

function dotClass(state: StageState): string {
  const base = "h-[10px] w-[10px] flex-shrink-0 rounded-full";
  switch (state) {
    case "failed":
      return `${base} bg-[#dc2626] ring-[3px] ring-[rgba(220,38,38,0.2)]`;
    case "rejected-edge":
      return `${base} bg-[#dc2626]`;
    case "rejected-current":
      return `${base} bg-[#dc2626] ring-[3px] ring-[rgba(220,38,38,0.2)]`;
    case "regressed-amber":
      return `${base} bg-[#e5e7eb] ring-2 ring-[#f59e0b]`;
    case "highlighted-pink":
      return `${base} bg-[#be185d] ring-[3px] ring-[rgba(190,24,93,0.2)]`;
    case "muted":
      return `${base} bg-[#e5e7eb]`;
    case "muted-green":
      return `${base} bg-[#10b981] opacity-50`;
    case "done":
      return `${base} bg-[#10b981]`;
    case "current":
      return `${base} bg-[#7c3aed] ring-[3px] ring-[rgba(124,58,237,0.2)]`;
    case "upcoming":
    default:
      return `${base} bg-[#e5e7eb]`;
  }
}

function isDoneLike(state: StageState): boolean {
  return state === "done" || state === "muted-green";
}

function isRejectedSide(state: StageState): boolean {
  return state === "rejected-edge" || state === "rejected-current";
}

function connectorClass(left: StageState, right: StageState): string {
  const base = "h-[2px] flex-1";
  if (isRejectedSide(left) || isRejectedSide(right)) {
    return `${base} bg-[#fecaca]`;
  }
  if (
    isDoneLike(left) &&
    (isDoneLike(right) || right === "current" || right === "highlighted-pink")
  ) {
    return `${base} bg-[#10b981]`;
  }
  return `${base} bg-[#e5e7eb]`;
}

function labelClass(state: StageState): string {
  const base = "mt-2 text-9 text-center leading-tight";
  switch (state) {
    case "failed":
    case "rejected-edge":
    case "rejected-current":
      return `${base} text-[#dc2626]`;
    case "regressed-amber":
      return `${base} text-[#f59e0b]`;
    case "highlighted-pink":
      return `${base} text-[#be185d] font-semibold`;
    case "muted":
      return `${base} text-[#d1d5db] line-through`;
    case "muted-green":
      return `${base} text-[#10b981]`;
    case "done":
      return `${base} text-[#10b981]`;
    case "current":
      return `${base} text-[#7c3aed] font-semibold`;
    case "upcoming":
    default:
      return `${base} text-neutral-400`;
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
    <ol
      data-testid="stage-progress"
      className="flex flex-shrink-0 items-start border-b border-neutral-100 px-6 py-4"
    >
      {segments.map((seg, idx) => {
        const next = segments[idx + 1];
        return (
          <li
            key={seg.key}
            data-testid={`stage-${seg.segment}-${seg.stageId ?? seg.key}`}
            data-segment={seg.segment}
            data-state={seg.state}
            data-stage-id={seg.stageId ?? null}
            className={colorClass(seg.state)}
          >
            <div className="flex w-full items-center">
              <span className={dotClass(seg.state)} />
              {next ? <span className={connectorClass(seg.state, next.state)} /> : null}
            </div>
            <span className={labelClass(seg.state)}>{seg.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
