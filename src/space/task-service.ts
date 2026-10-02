import {
  assertTransition,
  BUG_FIX_POLICY,
  evaluateCompletion,
  type CompletionCondition,
  type CompletionPolicy,
} from "./task-machine.js";
import { TaskVersionConflictError, type NewTask, type TaskStore } from "./task-store.js";
import type {
  Evidence,
  HandoffKind,
  MaintenanceTask,
  TaskEvent,
  TaskStatus,
  ValidationResult,
} from "./task-types.js";

export type Clock = () => string;

type EventInput = { type: string; message: string; data?: Record<string, unknown> };

/**
 * The single writer of task state. Everything that changes a task (runner steps, webhooks,
 * human commands) goes through here so transitions, guards and the timeline stay consistent.
 */
export class TaskService {
  constructor(
    private readonly store: TaskStore,
    private readonly now: Clock,
    private readonly policy: CompletionPolicy = BUG_FIX_POLICY,
  ) {}

  create(input: NewTask): MaintenanceTask {
    return this.store.create(input, this.now());
  }

  get(id: string): MaintenanceTask {
    const t = this.store.get(id);
    if (!t) {
      throw new Error(`unknown task ${id}`);
    }
    return t;
  }

  /** Applies a mutation, retrying once on a concurrent write. Optionally moves to a new status. */
  apply(
    id: string,
    mutate: (t: MaintenanceTask) => void,
    event: EventInput,
    to?: TaskStatus,
  ): MaintenanceTask {
    for (let attempt = 0; ; attempt++) {
      const current = this.get(id);
      try {
        const events: EventInput[] = [event];
        return this.store.update(
          id,
          current.version,
          (t) => {
            mutate(t);
            if (to && to !== t.status) {
              assertTransition(t, to, this.policy);
              events.push({
                type: "status.changed",
                message: `${t.status} → ${to}`,
                data: { from: t.status, to },
              });
              t.status = to;
              t.stepAttempts = 0;
            }
          },
          // `events` is appended to by mutate (status.changed) before the store writes it.
          events,
          this.now(),
        );
      } catch (err) {
        if (err instanceof TaskVersionConflictError && attempt < 1) {
          continue;
        }
        throw err;
      }
    }
  }

  transition(
    id: string,
    to: TaskStatus,
    message: string,
    data?: Record<string, unknown>,
  ): MaintenanceTask {
    return this.apply(
      id,
      () => {},
      { type: "status.requested", message, ...(data ? { data } : {}) },
      to,
    );
  }

  addEvidence(id: string, e: Omit<Evidence, "id" | "at">): MaintenanceTask {
    const evidence: Evidence = {
      ...e,
      id: `e${this.get(id).investigation.evidence.length + 1}`,
      at: this.now(),
    };
    return this.apply(id, (t) => void t.investigation.evidence.push(evidence), {
      type: "evidence.added",
      message: `Evidence (${e.kind}): ${e.summary}`,
      data: { evidenceId: evidence.id, ref: e.ref },
    });
  }

  recordValidation(id: string, v: Omit<ValidationResult, "id" | "at">): MaintenanceTask {
    const result: ValidationResult = {
      ...v,
      id: `v${this.get(id).validation.length + 1}`,
      at: this.now(),
    };
    return this.apply(id, (t) => void t.validation.push(result), {
      type: "validation.recorded",
      message: `${v.kind} ${v.passed ? "passed" : "FAILED"}: ${v.command}`,
      data: { validationId: result.id, exitCode: v.exitCode },
    });
  }

  recordError(id: string, message: string, retryable: boolean): MaintenanceTask {
    return this.apply(
      id,
      (t) => {
        t.errors.push({ at: this.now(), stage: t.status, message, retryable });
        t.stepAttempts += 1;
      },
      { type: "error", message: `Error${retryable ? " (retryable)" : ""}: ${message}` },
    );
  }

  handoff(id: string, kind: HandoffKind, message: string, needed?: string): MaintenanceTask {
    const to: TaskStatus =
      kind === "needs_information"
        ? "NEEDS_INFORMATION"
        : kind === "needs_approval"
          ? "NEEDS_APPROVAL"
          : kind === "blocked"
            ? "BLOCKED"
            : kind === "failed"
              ? "FAILED"
              : "READY_FOR_HUMAN";
    return this.apply(
      id,
      (t) => void (t.handoff = { kind, message, ...(needed ? { needed } : {}), at: this.now() }),
      { type: `handoff.${kind}`, message },
      to,
    );
  }

  approve(id: string, capability: string, approvedBy: string): MaintenanceTask {
    return this.apply(
      id,
      (t) => void t.approvals.push({ capability, approvedBy, at: this.now() }),
      {
        type: "approval.granted",
        message: `${approvedBy} approved ${capability}`,
      },
    );
  }

  completion(id: string): CompletionCondition[] {
    return evaluateCompletion(this.get(id), this.policy);
  }

  timeline(id: string): TaskEvent[] {
    return this.store.events(id);
  }
}
