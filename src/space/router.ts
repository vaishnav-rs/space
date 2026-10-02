import { type SpaceEvent } from "./events.js";
import { decide, isAuthorizedGithubUser } from "./policy.js";
import { canTransition } from "./task-machine.js";
import type { TaskService } from "./task-service.js";
import type { TaskStore } from "./task-store.js";
import { HUMAN_WAIT_STATUSES, TERMINAL_STATUSES, type MaintenanceTask } from "./task-types.js";
import type { WorkspaceManifest, WorkspaceRegistry } from "./workspace.js";

export type RouteOutcome =
  | { kind: "task-created"; task: MaintenanceTask }
  | { kind: "task-updated"; task: MaintenanceTask; note: string }
  | { kind: "status-requested"; task: MaintenanceTask }
  | { kind: "rejected"; reason: string }
  | { kind: "ignored"; reason: string };

export type SenderInfo = { login: string; isBot: boolean };

/**
 * Maps normalized events onto maintenance tasks. Authorization happens here, before any task
 * exists, so unauthorized users cannot create work for a production-connected agent.
 */
export class EventRouter {
  constructor(
    private readonly registry: WorkspaceRegistry,
    private readonly tasks: TaskService,
    private readonly store: TaskStore,
    private readonly now: () => string,
  ) {}

  handle(event: SpaceEvent, sender: SenderInfo, deliveryId?: string): RouteOutcome {
    if (deliveryId && !this.store.recordDelivery(deliveryId, this.now())) {
      return { kind: "ignored", reason: "duplicate delivery" };
    }
    const workspace = this.registry.findByRepo(event.repo);
    if (!workspace) {
      return { kind: "ignored", reason: `no workspace for ${event.repo}` };
    }
    if (sender.isBot) {
      return { kind: "ignored", reason: "bot sender" };
    }

    switch (event.type) {
      case "IssueMentioned":
        return this.onMention(workspace, event, deliveryId);
      case "IssueCommented": {
        const task = this.store.findBySource(workspace.id, event);
        if (!task || !isAuthorizedGithubUser(workspace, event.author)) {
          return { kind: "ignored", reason: "comment without task or authorization" };
        }
        // Authorized follow-up on a task waiting for information resumes it. Comment text is data.
        return task.status === "NEEDS_INFORMATION"
          ? this.resume(task, event.author, "new information provided")
          : { kind: "ignored", reason: "comment on active task" };
      }
      case "PullRequestReviewReceived":
      case "PullRequestUpdated":
      case "PullRequestOpened":
      case "CIFailed":
      case "CICompleted":
        // These feed the runner via polling/PR lookup; the router only records that something changed.
        return { kind: "ignored", reason: `${event.type} handled by the task runner` };
      case "IssueCreated":
        return { kind: "ignored", reason: "issue without mention" };
    }
  }

  private onMention(
    workspace: WorkspaceManifest,
    event: Extract<SpaceEvent, { type: "IssueMentioned" }>,
    deliveryId?: string,
  ): RouteOutcome {
    const actor = { kind: "github-user", login: event.author } as const;
    const gate = decide({ workspace, actor, capability: "github.issue.read" });
    if (gate.effect !== "allow") {
      return { kind: "rejected", reason: gate.reason };
    }
    const existing = this.store.findBySource(workspace.id, event);
    if (event.intent === "status" || event.intent === "explain") {
      return existing
        ? { kind: "status-requested", task: existing }
        : { kind: "ignored", reason: "no task for this issue" };
    }
    if (existing) {
      return this.onExisting(existing, event);
    }
    const task = this.tasks.create({
      workspaceId: workspace.id,
      source: {
        kind: "github-issue",
        repo: event.repo,
        issueNumber: event.issueNumber,
        url: event.url,
        ...(deliveryId ? { deliveryId } : {}),
      },
      reporter: { login: event.author },
      // Issue text is untrusted data; it is stored verbatim and wrapped before reaching a model.
      report: `${event.title}\n\n${event.body}`,
    });
    this.tasks.apply(task.id, () => {}, {
      type: "intent",
      message: `Requested: ${event.intent}`,
      data: { intent: event.intent },
    });
    return { kind: "task-created", task: this.tasks.get(task.id) };
  }

  private onExisting(
    task: MaintenanceTask,
    event: Extract<SpaceEvent, { type: "IssueMentioned" }>,
  ): RouteOutcome {
    if (event.intent === "stop") {
      if (TERMINAL_STATUSES.has(task.status)) {
        return { kind: "ignored", reason: "task already finished" };
      }
      return {
        kind: "task-updated",
        task: this.tasks.handoff(task.id, "blocked", `Stopped by ${event.author}`),
        note: "stopped",
      };
    }
    if (event.intent === "retry" && task.status === "FAILED") {
      return {
        kind: "task-updated",
        task: this.tasks.transition(task.id, "INVESTIGATING", `Retry requested by ${event.author}`),
        note: "retried",
      };
    }
    if (HUMAN_WAIT_STATUSES.has(task.status)) {
      return this.resume(task, event.author, `resume requested (${event.intent})`);
    }
    return { kind: "ignored", reason: "task already in progress" };
  }

  private resume(task: MaintenanceTask, by: string, note: string): RouteOutcome {
    const target =
      task.status === "READY_FOR_HUMAN"
        ? "ADDRESSING_REVIEW"
        : task.changes.commits.length > 0 && canTransition(task.status, "TESTING")
          ? "TESTING"
          : "INVESTIGATING";
    const updated = this.tasks.apply(
      task.id,
      (t) => void (t.handoff = undefined),
      { type: "resumed", message: `${by}: ${note}` },
      target,
    );
    return { kind: "task-updated", task: updated, note };
  }
}
