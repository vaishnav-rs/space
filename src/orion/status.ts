import { evaluateCompletion, type CompletionPolicy } from "./task-machine.js";
import {
  HUMAN_WAIT_STATUSES,
  type MaintenanceTask,
  type TaskEvent,
  type TaskStatus,
} from "./task-types.js";

const HEADLINE: Record<TaskStatus, string> = {
  REPORTED: "Received",
  TRIAGING: "Triaging",
  INVESTIGATING: "Investigating",
  REPRODUCING: "Reproducing",
  DIAGNOSING: "Diagnosing",
  FIXING: "Implementing the fix",
  TESTING: "Running validation",
  SELF_REVIEW: "Self-reviewing",
  PR_OPEN: "PR opened",
  CI_RUNNING: "Waiting for CI",
  COPILOT_REVIEW_PENDING: "Waiting for Copilot review",
  COPILOT_REVIEW_RECEIVED: "Evaluating review",
  ADDRESSING_REVIEW: "Addressing review findings",
  READY_FOR_HUMAN: "Awaiting human review",
  BLOCKED: "Blocked",
  NEEDS_INFORMATION: "Needs information",
  NEEDS_APPROVAL: "Needs approval",
  FAILED: "Failed",
  COMPLETED: "Completed",
};

const nextAction = (t: MaintenanceTask): string => {
  if (t.status === "READY_FOR_HUMAN") return "Human approval";
  if (HUMAN_WAIT_STATUSES.has(t.status))
    return t.handoff?.needed ?? t.handoff?.message ?? "Human input";
  if (t.status === "FAILED") return "Retry or take over";
  if (t.status === "COMPLETED") return "None";
  return "Agent continues";
};

/** The answer to "what's happening with issue 184?" */
export function describeTask(task: MaintenanceTask, policy: CompletionPolicy): string {
  const lines = [
    task.source.kind === "github-issue"
      ? `Issue #${task.source.issueNumber}`
      : `Task ${task.id.slice(0, 8)}`,
    `Status: ${HEADLINE[task.status]}`,
  ];
  const d = task.investigation.diagnosis;
  if (d) lines.push("", `Root cause (${d.confidence}):`, d.rootCause);
  if (task.changes.commits.length)
    lines.push(
      "",
      "Fix:",
      `${task.changes.commits.length} commit(s) on ${task.changes.branch ?? "an agent branch"}`,
    );
  if (task.pullRequest)
    lines.push("", "PR:", `#${task.pullRequest.number} ${task.pullRequest.url}`);
  if (task.ci)
    lines.push(
      "",
      "CI:",
      task.ci.state === "passing" ? "Passing" : task.ci.state === "failing" ? "Failing" : "Pending",
    );
  if (task.review.iteration > 0) {
    const open = task.review.findings.filter((f) => !f.resolved).length;
    lines.push("", "Review:", open ? `${open} finding(s) open` : "Reviewed, findings handled");
  }
  const unmet = evaluateCompletion(task, policy).filter((c) => c.required && !c.satisfied);
  if (task.status !== "COMPLETED" && task.status !== "READY_FOR_HUMAN" && unmet.length)
    lines.push("", "Remaining:", unmet.map((u) => u.label).join(", "));
  if (task.handoff && HUMAN_WAIT_STATUSES.has(task.status)) lines.push("", task.handoff.message);
  lines.push("", "Next action:", nextAction(task));
  return lines.join("\n");
}

/** Human-readable timeline with evidence references. */
export function renderTimeline(events: TaskEvent[]): string {
  return events
    .filter((e) => e.type !== "status.requested")
    .map((e) => `${e.at.slice(11, 16)} ${e.message}`)
    .join("\n");
}
