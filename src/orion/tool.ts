import { Type } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import { personalTool, optStr } from "../personal/tool-kit.js";
import type { AccessContext } from "./access/context.js";
import { AccessDeniedError } from "./access/directory.js";
import type { Permission } from "./access/roles.js";
import { canShareTask, canViewTask } from "./access/visibility.js";
import type { OrionRuntime } from "./runtime.js";
import { describeTask, renderTimeline } from "./status.js";
import { BUG_FIX_POLICY } from "./task-machine.js";
import type { MaintenanceTask } from "./task-types.js";
import { routeRequest } from "./workspace-routing.js";

const ACTIONS = [
  "create",
  "list",
  "status",
  "timeline",
  "stop",
  "retry",
  "approve",
  "share",
] as const;

export type OrionTaskToolDeps = {
  getRuntime: () => OrionRuntime | undefined;
  ownerInitiated: () => boolean;
  /** Present in multi-user mode: roles, workspace membership and task visibility apply. */
  getAccess?: () => AccessContext | undefined;
  getRequesterId?: () => string | undefined;
};

/**
 * Lets the assistant answer "what's happening with issue 184?" and steer tasks in plain language.
 * Mutations need an owner-initiated turn; in multi-user mode they also need the person's role,
 * and tasks are visible only to their owner, people they were shared with, and (for GitHub work)
 * members of the workspace.
 */
export function createOrionTaskTool(deps: OrionTaskToolDeps): AnyAgentTool {
  return personalTool({
    name: "orion_task",
    label: "Maintenance tasks",
    description:
      "Inspect and steer engineering maintenance tasks (bug fixes started from client reports or GitHub issues). Actions: create from a complaint; list; status/timeline for a task or issue number; stop; retry; approve a capability a task is waiting on; share a task with another person.",
    parameters: Type.Object({
      action: Type.String({ enum: [...ACTIONS] }),
      task: optStr("Task id or issue number, e.g. 184."),
      capability: optStr("For approve: the capability the task asked for."),
      report: optStr(
        "For create: the complaint exactly as the developer relayed it, e.g. 'Client says profile upload is broken'.",
      ),
      workspace: optStr("For create: workspace id; inferred from the report when omitted."),
      with: optStr("For share: the profile id of the person to share the task with."),
    }),
    run: async (p) => {
      const rt = deps.getRuntime();
      if (!rt)
        throw new Error(
          "Orion maintenance engine is not configured (set ORION_STATE_DIR and ORION_WORKSPACES_DIR).",
        );
      const access = deps.getAccess?.();
      const uid = access ? deps.getRequesterId?.() : undefined;
      if (access && !uid) throw new AccessDeniedError("No signed-in person for this turn.");
      const need = (perm: Permission) => {
        if (access && uid && !access.directory.can(uid, perm))
          throw new AccessDeniedError(`Your role does not allow this (${perm}).`);
      };
      const visible = (t: MaintenanceTask | undefined): MaintenanceTask | undefined =>
        t && (!access || !uid || canViewTask(access.directory, uid, t)) ? t : undefined;
      const find = (ref: string) => {
        const t = visible(rt.findTask(ref));
        if (!t) throw new Error(`No task found for ${ref}`); // same answer whether it does not exist or is not yours
        return t;
      };

      const action = typeof p.action === "string" ? p.action : "";
      const ref = typeof p.task === "string" ? p.task : "";
      if (action === "list") {
        return rt.store
          .list()
          .filter((t) => visible(t))
          .map((t) => ({
            id: t.id,
            workspace: t.workspaceId,
            status: t.status,
            source: t.source.kind === "github-issue" ? `#${t.source.issueNumber}` : t.source.kind,
          }));
      }
      if (action === "status") return { text: describeTask(find(ref), BUG_FIX_POLICY) };
      if (action === "timeline") return { text: renderTimeline(rt.tasks.timeline(find(ref).id)) };
      if (!deps.ownerInitiated())
        throw new Error(`${action} changes a task and needs you to ask for it directly.`);

      if (action === "create") {
        need("tasks.create");
        const report = typeof p.report === "string" ? p.report.trim() : "";
        if (!report) throw new Error("create needs the report text");
        const route = routeRequest(
          report,
          rt.registry,
          typeof p.workspace === "string" ? p.workspace : undefined,
        );
        const ws = rt.registry.get(route.workspaceId);
        if (!ws || ws.kind !== "project") {
          const known =
            rt.registry
              .list()
              .filter((w) => w.kind === "project")
              .map((w) => w.id)
              .join(", ") || "none";
          throw new Error(
            `No project workspace matches this report (${route.reason}). Say which project it is for. Known: ${known}`,
          );
        }
        if (access && uid && !access.directory.workspaceAllowed(uid, ws.id))
          throw new AccessDeniedError(`You do not have access to the ${ws.id} workspace.`);
        const created = rt.tasks.create({
          workspaceId: ws.id,
          source: { kind: "chat", channel: "assistant" },
          reporter: { name: uid ?? "owner" },
          report,
          ...(uid ? { ownerProfileId: uid } : {}),
        });
        // Start now; the scheduler keeps polling if this turn ends first.
        void rt.tick().catch(() => {});
        return {
          taskId: created.id,
          workspace: ws.id,
          status: created.status,
          note: "Investigation started.",
        };
      }

      const task = find(ref);
      const by = uid ?? "owner";
      if (action === "share") {
        const target = typeof p.with === "string" ? p.with.trim() : "";
        if (!target) throw new Error("share needs the person's profile id (with)");
        if (access && uid && !canShareTask(access.directory, uid, task))
          throw new AccessDeniedError("Only the task's owner or an admin can share it.");
        rt.tasks.apply(
          task.id,
          (t) => void (t.sharedWith.includes(target) || t.sharedWith.push(target)),
          { type: "task.shared", message: `Shared with ${target} by ${by}` },
        );
        return { sharedWith: rt.tasks.get(task.id).sharedWith };
      }
      if (action === "stop") {
        need("tasks.steer");
        return { status: rt.tasks.handoff(task.id, "blocked", `Stopped by ${by}`).status };
      }
      if (action === "retry") {
        need("tasks.steer");
        return {
          status: rt.tasks.transition(task.id, "INVESTIGATING", `Retry requested by ${by}`).status,
        };
      }
      if (action === "approve") {
        need("tasks.approve");
        const cap = typeof p.capability === "string" ? p.capability : "";
        if (!cap) throw new Error("approve needs the capability to approve");
        rt.tasks.approve(task.id, cap, by);
        return {
          status: rt.tasks.transition(
            task.id,
            task.changes.commits.length ? "TESTING" : "INVESTIGATING",
            `Approved ${cap}`,
          ).status,
        };
      }
      throw new Error(`unknown action ${action}`);
    },
  });
}
