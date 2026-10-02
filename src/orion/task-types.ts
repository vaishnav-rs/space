export const TASK_STATUSES = [
  "REPORTED",
  "TRIAGING",
  "INVESTIGATING",
  "REPRODUCING",
  "DIAGNOSING",
  "FIXING",
  "TESTING",
  "SELF_REVIEW",
  "PR_OPEN",
  "CI_RUNNING",
  "COPILOT_REVIEW_PENDING",
  "COPILOT_REVIEW_RECEIVED",
  "ADDRESSING_REVIEW",
  "READY_FOR_HUMAN",
  "BLOCKED",
  "NEEDS_INFORMATION",
  "NEEDS_APPROVAL",
  "FAILED",
  "COMPLETED",
] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

export const TERMINAL_STATUSES: ReadonlySet<TaskStatus> = new Set(["COMPLETED", "FAILED"]);
/** Waiting on a person; the runner must not advance these on its own. */
export const HUMAN_WAIT_STATUSES: ReadonlySet<TaskStatus> = new Set([
  "NEEDS_INFORMATION",
  "NEEDS_APPROVAL",
  "BLOCKED",
  "READY_FOR_HUMAN",
]);

export type TaskSource =
  | { kind: "github-issue"; repo: string; issueNumber: number; url: string; deliveryId?: string }
  | { kind: "github-pr"; repo: string; prNumber: number; url: string; deliveryId?: string }
  | { kind: "chat"; channel: string; conversationId?: string }
  | { kind: "manual" };

export type Confidence = "confirmed" | "probable" | "insufficient";
export type ReproductionStatus = "unknown" | "confirmed" | "not_reproduced" | "unavailable";

export type Evidence = {
  id: string;
  kind: "log" | "code" | "git" | "doc" | "wiki" | "graph" | "test" | "production" | "other";
  summary: string;
  /** Where it came from: file:line, commit, log source. Never raw secrets. */
  ref?: string;
  at: string;
};

export type Hypothesis = {
  id: string;
  statement: string;
  status: "open" | "supported" | "refuted";
  evidenceIds: string[];
};

export type ValidationResult = {
  id: string;
  kind: "targeted" | "regression" | "lint" | "typecheck" | "build" | "format" | "e2e";
  command: string;
  passed: boolean;
  exitCode: number;
  /** Tail of output, redacted by the runner. */
  outputTail: string;
  at: string;
};

export type FindingClassification =
  | "VALID"
  | "FALSE_POSITIVE"
  | "ALREADY_ADDRESSED"
  | "OUT_OF_SCOPE"
  | "UNCERTAIN";

export type ReviewFinding = {
  id: string;
  /** Reviewer-supplied text. Untrusted data. */
  body: string;
  path?: string;
  line?: number;
  iteration: number;
  classification?: FindingClassification;
  rationale?: string;
  /** Commit that fixed it, for VALID findings. */
  fixedIn?: string;
  resolved: boolean;
};

export type HandoffKind = "needs_information" | "needs_approval" | "blocked" | "ready" | "failed";

export type TaskError = {
  at: string;
  stage: TaskStatus;
  message: string;
  retryable: boolean;
};

export type MaintenanceTask = {
  id: string;
  workspaceId: string;
  source: TaskSource;
  reporter: { login?: string; name?: string };
  /** Verbatim report. Untrusted; always wrap before model context. */
  report: string;
  /** The person who started the task from chat. Chat tasks are private to them until shared. */
  ownerProfileId?: string;
  /** People the owner explicitly shared this task with. */
  sharedWith: string[];
  status: TaskStatus;
  /** Optimistic-concurrency counter, bumped on every write. */
  version: number;
  createdAt: string;
  updatedAt: string;
  investigation: {
    summary?: string;
    area?: string;
    evidence: Evidence[];
    hypotheses: Hypothesis[];
    reproduction: ReproductionStatus;
    diagnosis?: { rootCause: string; confidence: Confidence };
  };
  changes: {
    baseSha?: string;
    branch?: string;
    worktree?: string;
    commits: string[];
    pendingCommitMessage?: string;
  };
  validation: ValidationResult[];
  selfReview?: {
    passed: boolean;
    notes: string[];
    answeredSolved: boolean;
    unrelatedChanges: boolean;
  };
  pullRequest?: { number: number; url: string; headSha?: string };
  ci?: { state: "pending" | "passing" | "failing" | "unknown"; checkedAt: string };
  review: {
    iteration: number;
    requestedAt?: string;
    receivedAt?: string;
    /** Remote review ids already ingested, so later polls only see new reviews. */
    seenReviewIds: string[];
    findings: ReviewFinding[];
  };
  approvals: { capability: string; approvedBy: string; at: string }[];
  handoff?: { kind: HandoffKind; message: string; needed?: string; at: string };
  errors: TaskError[];
  /** Retries of the current step, reset on progress. */
  stepAttempts: number;
};

export type TaskEvent = {
  seq: number;
  taskId: string;
  at: string;
  type: string;
  /** Short human-readable line for the timeline. */
  message: string;
  data?: Record<string, unknown>;
};
