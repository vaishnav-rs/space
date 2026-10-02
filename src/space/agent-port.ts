import type { ContextResult } from "./context-engine.js";
import { IntegrationUnavailableError, type GitState, type PortBase } from "./ports.js";
import type {
  Confidence,
  Evidence,
  Hypothesis,
  MaintenanceTask,
  ReproductionStatus,
  ReviewFinding,
  ValidationResult,
} from "./task-types.js";

export type InvestigationInput = {
  task: MaintenanceTask;
  /** Wrapped, budgeted retrieval. Treat as data. */
  context: ContextResult;
  git: GitState | undefined;
  /** Production observations already collected under policy; undefined when not permitted or not configured. */
  production: { logs?: string; processes?: string } | undefined;
};

export type InvestigationResult = {
  summary: string;
  area?: string;
  evidence: Omit<Evidence, "id" | "at">[];
  hypotheses: Omit<Hypothesis, "id">[];
  /** The agent wants to attempt reproduction next. */
  tryReproduce: boolean;
  /** Set when the report is too thin to continue safely. */
  needsInformation?: { message: string; needed: string };
};

export type DiagnosisResult = {
  rootCause: string;
  confidence: Confidence;
  evidence?: Omit<Evidence, "id" | "at">[];
};

export type FixInput = {
  task: MaintenanceTask;
  worktree: string;
  context: ContextResult | undefined;
  /** Why we are here again: failed validation, failed CI, or review findings to address. */
  reason:
    | { kind: "initial" }
    | { kind: "validation"; failures: ValidationResult[] }
    | { kind: "ci"; detail: string }
    | { kind: "review"; findings: ReviewFinding[] };
};

export type SelfReviewInput = { task: MaintenanceTask; files: string[]; patch: string };
export type SelfReviewResult = {
  passed: boolean;
  notes: string[];
  answeredSolved: boolean;
  unrelatedChanges: boolean;
};

/**
 * The model-driven half of the engineering loop. The runner owns state, policy and side effects;
 * the agent only reasons and edits files inside the worktree it is handed.
 */
export interface AgentPort extends PortBase {
  investigate(input: InvestigationInput): Promise<InvestigationResult>;
  reproduce(
    input: InvestigationInput,
  ): Promise<{ status: ReproductionStatus; evidence: Omit<Evidence, "id" | "at">[] }>;
  diagnose(input: InvestigationInput): Promise<DiagnosisResult>;
  fix(input: FixInput): Promise<{ summary: string; commitMessage: string }>;
  selfReview(input: SelfReviewInput): Promise<SelfReviewResult>;
  describePullRequest(input: {
    task: MaintenanceTask;
    files: string[];
  }): Promise<{ title: string; body: string }>;
}

/**
 * Placeholder until the OpenClaw agent runtime is bound. It fails loudly so a task blocks with a
 * precise reason instead of "succeeding" with fabricated work.
 */
export function createUnavailableAgent(
  reason = "the OpenClaw agent runtime binding is not configured",
): AgentPort {
  const no = (): Promise<never> => Promise.reject(new IntegrationUnavailableError("agent", reason));
  return {
    integration: "unavailable",
    name: "agent",
    investigate: no,
    reproduce: no,
    diagnose: no,
    fix: no,
    selfReview: no,
    describePullRequest: no,
  };
}
