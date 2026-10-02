import { Type } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import { personalTool, optStr } from "../personal/tool-kit.js";
import type { SpaceRuntime } from "./runtime.js";

const ACTIONS = ["list", "status", "timeline", "stop", "retry", "approve"] as const;

/**
 * Lets the assistant answer "what's happening with issue 184?" and steer tasks in plain language.
 * Mutations are owner-initiated turns only, matching the outbound guard's rule.
 */
export function createSpaceTaskTool(
  getRuntime: () => SpaceRuntime | undefined,
  ownerInitiated: () => boolean,
): AnyAgentTool {
  return personalTool({
    name: "space_task",
    label: "Maintenance tasks",
    description:
      "Inspect and steer engineering maintenance tasks (bug fixes started from client reports or GitHub issues). Actions: list; status/timeline for a task or issue number; stop; retry; approve a capability a task is waiting on.",
    parameters: Type.Object({
      action: Type.String({ enum: [...ACTIONS] }),
      task: optStr("Task id or issue number, e.g. 184."),
      capability: optStr("For approve: the capability the task asked for."),
    }),
    run: async (p) => {
      const rt = getRuntime();
      if (!rt)
        throw new Error(
          "Space maintenance engine is not configured (set SPACE_STATE_DIR and SPACE_WORKSPACES_DIR).",
        );
      const action = typeof p.action === "string" ? p.action : "";
      const ref = typeof p.task === "string" ? p.task : "";
      if (action === "list") {
        return rt.store.list().map((t) => ({
          id: t.id,
          workspace: t.workspaceId,
          status: t.status,
          source: t.source.kind === "github-issue" ? `#${t.source.issueNumber}` : t.source.kind,
        }));
      }
      if (action === "status") return { text: rt.describe(ref) };
      if (action === "timeline") return { text: rt.timeline(ref) };
      if (!ownerInitiated())
        throw new Error(`${action} changes a task and needs you to ask for it directly.`);
      const task = rt.findTask(ref);
      if (!task) throw new Error(`No task found for ${ref}`);
      if (action === "stop")
        return { status: rt.tasks.handoff(task.id, "blocked", "Stopped by the owner").status };
      if (action === "retry")
        return {
          status: rt.tasks.transition(task.id, "INVESTIGATING", "Retry requested by the owner")
            .status,
        };
      if (action === "approve") {
        const cap = typeof p.capability === "string" ? p.capability : "";
        if (!cap) throw new Error("approve needs the capability to approve");
        rt.tasks.approve(task.id, cap, "owner");
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
