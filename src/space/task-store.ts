import { randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import type { MaintenanceTask, TaskEvent, TaskStatus } from "./task-types.js";

/**
 * Durable task state. The task row is the snapshot, task_events is the append-only timeline.
 * Writes are synchronous transactions guarded by an optimistic version check so two runners
 * (or a runner and a webhook) can never silently overwrite each other.
 */
export class TaskVersionConflictError extends Error {
  constructor(taskId: string) {
    super(`task ${taskId} was modified concurrently`);
    this.name = "TaskVersionConflictError";
  }
}

export type NewTask = Pick<MaintenanceTask, "workspaceId" | "source" | "reporter" | "report">;

export interface TaskStore {
  create(input: NewTask, now: string): MaintenanceTask;
  get(id: string): MaintenanceTask | undefined;
  list(filter?: { workspaceId?: string; statuses?: readonly TaskStatus[] }): MaintenanceTask[];
  /** Applies `mutate` to a copy, bumps version, writes snapshot + events atomically. */
  update(
    id: string,
    expectedVersion: number,
    mutate: (task: MaintenanceTask) => void,
    events: { type: string; message: string; data?: Record<string, unknown> }[],
    now: string,
  ): MaintenanceTask;
  appendEvent(
    taskId: string,
    event: { type: string; message: string; data?: Record<string, unknown> },
    now: string,
  ): TaskEvent;
  events(taskId: string): TaskEvent[];
  /** Idempotency for webhook deliveries. Returns false when already recorded. */
  recordDelivery(deliveryId: string, now: string): boolean;
  findBySource(
    workspaceId: string,
    source: { repo: string; issueNumber: number },
  ): MaintenanceTask | undefined;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS space_tasks (
  id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL,
  status TEXT NOT NULL,
  version INTEGER NOT NULL,
  source_key TEXT,
  snapshot TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS space_tasks_status ON space_tasks(workspace_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS space_tasks_source ON space_tasks(workspace_id, source_key) WHERE source_key IS NOT NULL;
CREATE TABLE IF NOT EXISTS space_task_events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  at TEXT NOT NULL,
  type TEXT NOT NULL,
  message TEXT NOT NULL,
  data TEXT
);
CREATE INDEX IF NOT EXISTS space_task_events_task ON space_task_events(task_id, seq);
CREATE TABLE IF NOT EXISTS space_deliveries (delivery_id TEXT PRIMARY KEY, at TEXT NOT NULL);
`;

function sourceKey(source: MaintenanceTask["source"]): string | null {
  return source.kind === "github-issue"
    ? `${source.repo.toLowerCase()}#${source.issueNumber}`
    : null;
}

export function createSqliteTaskStore(db: DatabaseSync): TaskStore {
  db.exec(SCHEMA);

  const insertEvent = (
    taskId: string,
    e: { type: string; message: string; data?: Record<string, unknown> },
    at: string,
  ): TaskEvent => {
    const r = db
      .prepare("INSERT INTO space_task_events(task_id, at, type, message, data) VALUES (?,?,?,?,?)")
      .run(taskId, at, e.type, e.message, e.data ? JSON.stringify(e.data) : null);
    return {
      seq: Number(r.lastInsertRowid),
      taskId,
      at,
      type: e.type,
      message: e.message,
      ...(e.data ? { data: e.data } : {}),
    };
  };
  const read = (id: string): MaintenanceTask | undefined => {
    const row = db.prepare("SELECT snapshot FROM space_tasks WHERE id = ?").get(id) as
      | { snapshot: string }
      | undefined;
    return row ? (JSON.parse(row.snapshot) as MaintenanceTask) : undefined;
  };
  const transaction = <T>(fn: () => T): T => {
    db.exec("BEGIN IMMEDIATE");
    try {
      const out = fn();
      db.exec("COMMIT");
      return out;
    } catch (err) {
      db.exec("ROLLBACK");
      throw err;
    }
  };

  return {
    create(input, now) {
      const task: MaintenanceTask = {
        id: randomUUID(),
        ...input,
        status: "REPORTED",
        version: 1,
        createdAt: now,
        updatedAt: now,
        investigation: { evidence: [], hypotheses: [], reproduction: "unknown" },
        changes: { commits: [] },
        validation: [],
        review: { iteration: 0, seenReviewIds: [], findings: [] },
        approvals: [],
        errors: [],
        stepAttempts: 0,
      };
      return transaction(() => {
        db.prepare(
          "INSERT INTO space_tasks(id, workspace_id, status, version, source_key, snapshot, updated_at) VALUES (?,?,?,?,?,?,?)",
        ).run(
          task.id,
          task.workspaceId,
          task.status,
          task.version,
          sourceKey(task.source),
          JSON.stringify(task),
          now,
        );
        insertEvent(
          task.id,
          { type: "task.created", message: `Task created from ${task.source.kind}` },
          now,
        );
        return task;
      });
    },
    get: read,
    list(filter) {
      const rows = db.prepare("SELECT snapshot FROM space_tasks ORDER BY updated_at").all() as {
        snapshot: string;
      }[];
      return rows
        .map((r) => JSON.parse(r.snapshot) as MaintenanceTask)
        .filter(
          (t) =>
            (!filter?.workspaceId || t.workspaceId === filter.workspaceId) &&
            (!filter?.statuses || filter.statuses.includes(t.status)),
        );
    },
    update(id, expectedVersion, mutate, events, now) {
      return transaction(() => {
        const current = read(id);
        if (!current) {
          throw new Error(`unknown task ${id}`);
        }
        if (current.version !== expectedVersion) {
          throw new TaskVersionConflictError(id);
        }
        const next = structuredClone(current);
        mutate(next);
        next.version = current.version + 1;
        next.updatedAt = now;
        db.prepare(
          "UPDATE space_tasks SET status = ?, version = ?, snapshot = ?, updated_at = ? WHERE id = ?",
        ).run(next.status, next.version, JSON.stringify(next), now, id);
        for (const e of events) {
          insertEvent(id, e, now);
        }
        return next;
      });
    },
    appendEvent: (taskId, event, now) => insertEvent(taskId, event, now),
    events(taskId) {
      const rows = db
        .prepare(
          "SELECT seq, task_id, at, type, message, data FROM space_task_events WHERE task_id = ? ORDER BY seq",
        )
        .all(taskId) as {
        seq: number;
        task_id: string;
        at: string;
        type: string;
        message: string;
        data: string | null;
      }[];
      return rows.map((r) => ({
        seq: r.seq,
        taskId: r.task_id,
        at: r.at,
        type: r.type,
        message: r.message,
        ...(r.data ? { data: JSON.parse(r.data) as Record<string, unknown> } : {}),
      }));
    },
    recordDelivery(deliveryId, now) {
      return (
        db
          .prepare("INSERT OR IGNORE INTO space_deliveries(delivery_id, at) VALUES (?,?)")
          .run(deliveryId, now).changes > 0
      );
    },
    findBySource(workspaceId, source) {
      const row = db
        .prepare("SELECT snapshot FROM space_tasks WHERE workspace_id = ? AND source_key = ?")
        .get(workspaceId, `${source.repo.toLowerCase()}#${source.issueNumber}`) as
        | { snapshot: string }
        | undefined;
      return row ? (JSON.parse(row.snapshot) as MaintenanceTask) : undefined;
    },
  };
}
