import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import {
  canTransition,
  IllegalTransitionError,
  unmetConditions,
  BUG_FIX_POLICY,
} from "./task-machine.js";
import { TaskService } from "./task-service.js";
import { createSqliteTaskStore, TaskVersionConflictError } from "./task-store.js";

const source = {
  kind: "github-issue",
  repo: "acme/hewar",
  issueNumber: 184,
  url: "https://github.com/acme/hewar/issues/184",
} as const;

function setup(db = new DatabaseSync(":memory:")) {
  let n = 0;
  const store = createSqliteTaskStore(db);
  const service = new TaskService(store, () => `2026-01-01T00:00:${String(n++).padStart(2, "0")}Z`);
  return { db, store, service };
}

describe("task state machine", () => {
  it("allows a cannot-reproduce path and forbids skipping to PR", () => {
    expect(canTransition("INVESTIGATING", "NEEDS_INFORMATION")).toBe(true);
    expect(canTransition("INVESTIGATING", "PR_OPEN")).toBe(false);
    expect(canTransition("COMPLETED", "INVESTIGATING")).toBe(false);
  });

  it("refuses READY_FOR_HUMAN until the completion policy is met", () => {
    const { service } = setup();
    const t = service.create({
      workspaceId: "hewar",
      source,
      reporter: { login: "dev" },
      report: "Client says profile upload is broken",
    });
    service.transition(t.id, "INVESTIGATING", "start");
    expect(() => service.transition(t.id, "READY_FOR_HUMAN", "x")).toThrow(IllegalTransitionError);
    expect(unmetConditions(service.get(t.id), BUG_FIX_POLICY).map((c) => c.id)).toContain("fixed");
  });

  it("persists across a reopened connection and keeps the timeline", () => {
    const { db, service } = setup();
    const t = service.create({ workspaceId: "hewar", source, reporter: {}, report: "x" });
    service.transition(t.id, "INVESTIGATING", "go");
    service.addEvidence(t.id, { kind: "log", summary: "413 from nginx" });
    const resumed = new TaskService(createSqliteTaskStore(db), () => "2026-01-02T00:00:00Z");
    expect(resumed.get(t.id).status).toBe("INVESTIGATING");
    expect(resumed.get(t.id).investigation.evidence).toHaveLength(1);
    expect(resumed.timeline(t.id).map((e) => e.type)).toEqual([
      "task.created",
      "status.requested",
      "status.changed",
      "evidence.added",
    ]);
  });

  it("rejects stale writes", () => {
    const { store, service } = setup();
    const t = service.create({ workspaceId: "hewar", source, reporter: {}, report: "x" });
    service.transition(t.id, "INVESTIGATING", "go");
    expect(() => store.update(t.id, t.version, () => {}, [], "now")).toThrow(
      TaskVersionConflictError,
    );
  });

  it("dedupes webhook deliveries and finds tasks by issue", () => {
    const { store, service } = setup();
    const t = service.create({ workspaceId: "hewar", source, reporter: {}, report: "x" });
    expect(store.recordDelivery("d1", "now")).toBe(true);
    expect(store.recordDelivery("d1", "now")).toBe(false);
    expect(store.findBySource("hewar", { repo: "ACME/hewar", issueNumber: 184 })?.id).toBe(t.id);
  });
});
