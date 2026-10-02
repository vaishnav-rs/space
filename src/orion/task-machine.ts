import { TERMINAL_STATUSES, type MaintenanceTask, type TaskStatus } from "./task-types.js";

const ANY_WAIT: TaskStatus[] = ["BLOCKED", "NEEDS_INFORMATION", "NEEDS_APPROVAL", "FAILED"];

/**
 * Legal transitions. Not every task visits every state: a task may go from INVESTIGATING
 * straight to NEEDS_INFORMATION, or skip REPRODUCING when production evidence suffices.
 */
const EDGES: Record<TaskStatus, TaskStatus[]> = {
  REPORTED: ["TRIAGING", "INVESTIGATING", ...ANY_WAIT],
  TRIAGING: ["INVESTIGATING", ...ANY_WAIT],
  INVESTIGATING: ["REPRODUCING", "DIAGNOSING", ...ANY_WAIT],
  REPRODUCING: ["DIAGNOSING", "INVESTIGATING", ...ANY_WAIT],
  DIAGNOSING: ["FIXING", "INVESTIGATING", "REPRODUCING", ...ANY_WAIT],
  FIXING: ["TESTING", "DIAGNOSING", ...ANY_WAIT],
  TESTING: ["SELF_REVIEW", "FIXING", ...ANY_WAIT],
  SELF_REVIEW: ["PR_OPEN", "CI_RUNNING", "FIXING", ...ANY_WAIT],
  PR_OPEN: ["CI_RUNNING", "COPILOT_REVIEW_PENDING", "READY_FOR_HUMAN", ...ANY_WAIT],
  CI_RUNNING: [
    "COPILOT_REVIEW_PENDING",
    "ADDRESSING_REVIEW",
    "READY_FOR_HUMAN",
    "FIXING",
    ...ANY_WAIT,
  ],
  COPILOT_REVIEW_PENDING: ["COPILOT_REVIEW_RECEIVED", "CI_RUNNING", "READY_FOR_HUMAN", ...ANY_WAIT],
  COPILOT_REVIEW_RECEIVED: ["ADDRESSING_REVIEW", "CI_RUNNING", "READY_FOR_HUMAN", ...ANY_WAIT],
  ADDRESSING_REVIEW: [
    "TESTING",
    "CI_RUNNING",
    "COPILOT_REVIEW_PENDING",
    "READY_FOR_HUMAN",
    ...ANY_WAIT,
  ],
  READY_FOR_HUMAN: ["COMPLETED", "ADDRESSING_REVIEW", "FIXING", "FAILED"],
  // Waiting states resume where the human unblocked them.
  BLOCKED: ["INVESTIGATING", "DIAGNOSING", "FIXING", "TESTING", "CI_RUNNING", "FAILED"],
  NEEDS_INFORMATION: ["INVESTIGATING", "REPRODUCING", "DIAGNOSING", "FAILED"],
  NEEDS_APPROVAL: ["INVESTIGATING", "DIAGNOSING", "FIXING", "TESTING", "CI_RUNNING", "FAILED"],
  FAILED: ["INVESTIGATING"], // explicit retry only
  COMPLETED: [],
};

export function canTransition(from: TaskStatus, to: TaskStatus): boolean {
  return EDGES[from].includes(to);
}

export class IllegalTransitionError extends Error {
  constructor(
    readonly from: TaskStatus,
    readonly to: TaskStatus,
    detail?: string,
  ) {
    super(`illegal task transition ${from} -> ${to}${detail ? `: ${detail}` : ""}`);
    this.name = "IllegalTransitionError";
  }
}

export type CompletionCondition = {
  id: string;
  label: string;
  satisfied: boolean;
  required: boolean;
  detail?: string;
};

export type CompletionPolicy = {
  requireReproductionOrDiagnosis: boolean;
  requireRegression: boolean;
  requireCi: boolean;
  requireExternalReview: boolean;
};

export const BUG_FIX_POLICY: CompletionPolicy = {
  requireReproductionOrDiagnosis: true,
  requireRegression: true,
  requireCi: true,
  requireExternalReview: true,
};

/** Explicit, inspectable definition of "done" for a bug-fix task. */
export function evaluateCompletion(
  task: MaintenanceTask,
  policy: CompletionPolicy,
): CompletionCondition[] {
  const passed = (kind: string) => task.validation.some((v) => v.kind === kind && v.passed);
  const latestFailed = task.validation.some(
    (v, i, all) => !v.passed && !all.slice(i + 1).some((n) => n.kind === v.kind && n.passed),
  );
  const openFindings = task.review.findings.filter((f) => !f.resolved);
  const diag = task.investigation.diagnosis;
  return [
    {
      id: "reported",
      label: "Report recorded",
      satisfied: task.report.trim().length > 0,
      required: true,
    },
    {
      id: "understood",
      label: "Problem understood",
      satisfied: Boolean(task.investigation.summary),
      required: true,
    },
    {
      id: "diagnosed",
      label: "Reproduced or sufficiently diagnosed",
      satisfied:
        task.investigation.reproduction === "confirmed" ||
        diag?.confidence === "confirmed" ||
        diag?.confidence === "probable",
      required: policy.requireReproductionOrDiagnosis,
      detail: diag ? `${diag.confidence}: ${diag.rootCause}` : undefined,
    },
    {
      id: "fixed",
      label: "Fix committed",
      satisfied: task.changes.commits.length > 0,
      required: true,
    },
    { id: "targeted", label: "Targeted tests pass", satisfied: passed("targeted"), required: true },
    {
      id: "regression",
      label: "Regression tests pass",
      satisfied: passed("regression"),
      required: policy.requireRegression,
    },
    {
      id: "no-failing-validation",
      label: "No unresolved failing validation",
      satisfied: !latestFailed,
      required: true,
    },
    {
      id: "self-review",
      label: "Self-review passed",
      satisfied: Boolean(
        task.selfReview?.passed &&
        task.selfReview.answeredSolved &&
        !task.selfReview.unrelatedChanges,
      ),
      required: true,
    },
    {
      id: "pr",
      label: "Pull request created",
      satisfied: Boolean(task.pullRequest),
      required: true,
    },
    {
      id: "ci",
      label: "CI passing",
      satisfied: task.ci?.state === "passing",
      required: policy.requireCi,
    },
    {
      id: "external-review",
      label: "External review evaluated, no open findings",
      satisfied: Boolean(task.review.receivedAt) && openFindings.length === 0,
      required: policy.requireExternalReview,
      detail: openFindings.length ? `${openFindings.length} open` : undefined,
    },
  ];
}

export function unmetConditions(
  task: MaintenanceTask,
  policy: CompletionPolicy,
): CompletionCondition[] {
  return evaluateCompletion(task, policy).filter((c) => c.required && !c.satisfied);
}

/** Guards beyond the edge table: READY_FOR_HUMAN is only reachable when the policy is met. */
export function assertTransition(
  task: MaintenanceTask,
  to: TaskStatus,
  policy: CompletionPolicy,
): void {
  if (!canTransition(task.status, to)) {
    throw new IllegalTransitionError(task.status, to);
  }
  if (to === "READY_FOR_HUMAN") {
    const unmet = unmetConditions(task, policy);
    if (unmet.length > 0) {
      throw new IllegalTransitionError(
        task.status,
        to,
        `unmet: ${unmet.map((u) => u.id).join(", ")}`,
      );
    }
  }
  if (TERMINAL_STATUSES.has(task.status) && task.status !== "FAILED") {
    throw new IllegalTransitionError(task.status, to, "task is terminal");
  }
}
