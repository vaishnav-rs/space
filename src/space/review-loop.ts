import type { RemoteReview } from "./ports.js";
import type { FindingClassification, MaintenanceTask, ReviewFinding } from "./task-types.js";

export type ClassifiedFinding = {
  findingId: string;
  classification: FindingClassification;
  rationale: string;
};

/**
 * The model-backed part of review handling. It sees finding text as untrusted data and returns a
 * classification with a rationale. The loop below owns bounds and state; the classifier owns judgement.
 */
export interface FindingClassifier {
  classify(input: {
    task: MaintenanceTask;
    findings: ReviewFinding[];
  }): Promise<ClassifiedFinding[]>;
}

export function findingsFromReviews(
  reviews: RemoteReview[],
  iteration: number,
  known: ReviewFinding[],
): ReviewFinding[] {
  const seen = new Set(known.map((f) => f.id));
  const out: ReviewFinding[] = [];
  for (const r of reviews) {
    for (const c of r.comments) {
      const id = `${r.id}:${c.id}`;
      if (!seen.has(id)) {
        out.push({
          id,
          body: c.body,
          ...(c.path ? { path: c.path } : {}),
          ...(c.line ? { line: c.line } : {}),
          iteration,
          resolved: false,
        });
      }
    }
    if (
      r.comments.length === 0 &&
      r.body.trim() &&
      r.state !== "APPROVED" &&
      !seen.has(`${r.id}:body`)
    ) {
      out.push({ id: `${r.id}:body`, body: r.body, iteration, resolved: false });
    }
  }
  return out;
}

export type ReviewDecision =
  | { action: "fix"; findings: ReviewFinding[] }
  | { action: "ready" }
  | { action: "escalate"; reason: string };

/** Stop conditions: iteration cap and any uncertain finding go to a human. Valid findings get fixed. */
export function decideAfterClassification(
  task: MaintenanceTask,
  maxIterations: number,
): ReviewDecision {
  const open = task.review.findings.filter((f) => !f.resolved);
  const uncertain = open.filter((f) => f.classification === "UNCERTAIN");
  if (uncertain.length > 0) {
    return {
      action: "escalate",
      reason: `${uncertain.length} review finding(s) need a human judgement`,
    };
  }
  const valid = open.filter((f) => f.classification === "VALID");
  if (valid.length === 0) {
    return { action: "ready" };
  }
  if (task.review.iteration >= maxIterations) {
    return {
      action: "escalate",
      reason: `review iteration limit (${maxIterations}) reached with ${valid.length} valid finding(s) still open`,
    };
  }
  return { action: "fix", findings: valid };
}
