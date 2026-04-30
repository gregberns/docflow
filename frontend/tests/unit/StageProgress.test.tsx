import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { StageProgress } from "../../src/components/StageProgress";
import type { StageSummary } from "../../src/types/workflow";

const PINNACLE_STAGES: StageSummary[] = [
  {
    id: "review",
    displayName: "Review",
    kind: "REVIEW",
    canonicalStatus: "AWAITING_REVIEW",
    role: "PARALEGAL",
  },
  {
    id: "attorney_approval",
    displayName: "Attorney Approval",
    kind: "APPROVAL",
    canonicalStatus: "AWAITING_APPROVAL",
    role: "ATTORNEY",
  },
  {
    id: "billing_approval",
    displayName: "Billing Approval",
    kind: "APPROVAL",
    canonicalStatus: "AWAITING_APPROVAL",
    role: "BILLING",
  },
  {
    id: "filed",
    displayName: "Filed",
    kind: "TERMINAL",
    canonicalStatus: "FILED",
    role: "SYSTEM",
  },
];

function preWorkflowItems(list: HTMLElement) {
  return within(list)
    .getAllByRole("listitem")
    .filter((el) => el.getAttribute("data-segment") === "pre-workflow");
}

function workflowItems(list: HTMLElement) {
  return within(list)
    .getAllByRole("listitem")
    .filter((el) => el.getAttribute("data-segment") === "workflow");
}

describe("StageProgress — in-flight branch (currentStep)", () => {
  it("AC7.1: CLASSIFYING marks Classifying current and later segments upcoming", () => {
    render(<StageProgress mode="in-flight" currentStep="CLASSIFYING" stages={PINNACLE_STAGES} />);

    const list = screen.getByTestId("stage-progress");
    expect(list.tagName.toLowerCase()).toBe("ol");

    const pre = preWorkflowItems(list);
    expect(pre.map((el) => el.textContent)).toEqual(["Upload", "Classify", "Extract"]);
    expect(pre[0]!.getAttribute("data-state")).toBe("done");
    expect(pre[1]!.getAttribute("data-state")).toBe("current");
    expect(pre[2]!.getAttribute("data-state")).toBe("upcoming");

    const wf = workflowItems(list);
    expect(wf).toHaveLength(PINNACLE_STAGES.length);
    for (const item of wf) {
      expect(item.getAttribute("data-state")).toBe("upcoming");
    }
  });

  it("AC7.2: FAILED marks the failed step with data-state=failed and a red class", () => {
    render(<StageProgress mode="in-flight" currentStep="FAILED" stages={PINNACLE_STAGES} />);

    const list = screen.getByTestId("stage-progress");
    const pre = preWorkflowItems(list);
    const failed = pre.find((el) => el.getAttribute("data-state") === "failed");
    expect(failed).toBeDefined();
    expect(failed!.className).toMatch(/red/);
    const muted = pre.filter((el) => el.getAttribute("data-state") === "muted");
    expect(muted.length).toBeGreaterThan(0);

    const wf = workflowItems(list);
    for (const item of wf) {
      expect(item.getAttribute("data-state")).toBe("upcoming");
    }
  });

  it("AC7.4: pre-workflow and workflow segments render contiguously inside one <ol>", () => {
    render(
      <StageProgress mode="in-flight" currentStep="TEXT_EXTRACTING" stages={PINNACLE_STAGES} />,
    );
    const lists = screen.getAllByRole("list");
    expect(lists).toHaveLength(1);
    const items = within(lists[0]!).getAllByRole("listitem");
    expect(items.length).toBe(3 + PINNACLE_STAGES.length);
    const segments = items.map((el) => el.getAttribute("data-segment"));
    expect(segments.slice(0, 3)).toEqual(["pre-workflow", "pre-workflow", "pre-workflow"]);
    expect(segments.slice(3)).toEqual(Array(PINNACLE_STAGES.length).fill("workflow"));
  });
});

describe("StageProgress — processed branch (currentStageId + currentStatus + originStage)", () => {
  it("Processed Review (not flagged): pre-workflow done; review current; later upcoming", () => {
    render(
      <StageProgress
        mode="processed"
        stages={PINNACLE_STAGES}
        currentStageId="review"
        currentStatus="AWAITING_REVIEW"
        originStage={null}
      />,
    );
    const list = screen.getByTestId("stage-progress");
    for (const el of preWorkflowItems(list)) {
      expect(el.getAttribute("data-state")).toBe("done");
    }
    const wf = workflowItems(list);
    expect(wf[0]!.getAttribute("data-state")).toBe("current");
    for (let i = 1; i < wf.length; i += 1) {
      expect(wf[i]!.getAttribute("data-state")).toBe("upcoming");
    }
  });

  it("Processed Review-flagged: review current; regressed-amber between review and origin; muted-green up through origin", () => {
    render(
      <StageProgress
        mode="processed"
        stages={PINNACLE_STAGES}
        currentStageId="review"
        currentStatus="AWAITING_REVIEW"
        originStage="attorney_approval"
      />,
    );
    const list = screen.getByTestId("stage-progress");
    const wf = workflowItems(list);
    const byId = (id: string) => wf.find((el) => el.getAttribute("data-stage-id") === id)!;
    expect(byId("review").getAttribute("data-state")).toBe("current");
    expect(byId("attorney_approval").getAttribute("data-state")).toBe("regressed-amber");
    expect(byId("attorney_approval").className).toMatch(/amber/);
    expect(byId("billing_approval").getAttribute("data-state")).toBe("upcoming");
    expect(byId("filed").getAttribute("data-state")).toBe("upcoming");
  });

  it("Processed Approval: highlighted-pink at the current approval; later upcoming; earlier done", () => {
    render(
      <StageProgress
        mode="processed"
        stages={PINNACLE_STAGES}
        currentStageId="attorney_approval"
        currentStatus="AWAITING_APPROVAL"
        originStage={null}
      />,
    );
    const list = screen.getByTestId("stage-progress");
    const wf = workflowItems(list);
    const byId = (id: string) => wf.find((el) => el.getAttribute("data-stage-id") === id)!;
    expect(byId("review").getAttribute("data-state")).toBe("done");
    expect(byId("attorney_approval").getAttribute("data-state")).toBe("highlighted-pink");
    expect(byId("attorney_approval").className).toMatch(/pink/);
    expect(byId("billing_approval").getAttribute("data-state")).toBe("upcoming");
    expect(byId("filed").getAttribute("data-state")).toBe("upcoming");
  });

  it("Processed Filed: every segment data-state=done", () => {
    render(
      <StageProgress
        mode="processed"
        stages={PINNACLE_STAGES}
        currentStageId="filed"
        currentStatus="FILED"
        originStage={null}
      />,
    );
    const list = screen.getByTestId("stage-progress");
    for (const el of preWorkflowItems(list)) {
      expect(el.getAttribute("data-state")).toBe("done");
    }
    for (const el of workflowItems(list)) {
      expect(el.getAttribute("data-state")).toBe("done");
    }
  });

  it("AC7.3: Processed Rejected — rejected-edge before Rejected; rejected-current on Rejected; never-reached approvals muted", () => {
    render(
      <StageProgress
        mode="processed"
        stages={PINNACLE_STAGES}
        currentStageId="attorney_approval"
        currentStatus="REJECTED"
        originStage={null}
      />,
    );
    const list = screen.getByTestId("stage-progress");
    const wf = workflowItems(list);
    const byId = (id: string) => wf.find((el) => el.getAttribute("data-stage-id") === id)!;

    expect(byId("review").getAttribute("data-state")).toBe("done");
    expect(byId("attorney_approval").getAttribute("data-state")).toBe("rejected-edge");
    expect(byId("attorney_approval").className).toMatch(/red/);

    const rejected = byId("rejected");
    expect(rejected).toBeDefined();
    expect(rejected.getAttribute("data-state")).toBe("rejected-current");
    expect(rejected.className).toMatch(/red/);
    expect(rejected.textContent).toBe("Rejected");

    expect(byId("billing_approval").getAttribute("data-state")).toBe("muted");
    expect(byId("filed").getAttribute("data-state")).toBe("muted");
  });
});
