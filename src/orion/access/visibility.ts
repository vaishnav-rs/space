import type { MaintenanceTask } from "../task-types.js";
import type { Directory } from "./directory.js";
import { isAdminRole } from "./roles.js";

/**
 * Who may see a task. Private by default: a task started from chat belongs to its creator until
 * they explicitly share it. Team tasks that arrive from GitHub are visible to the members of that
 * workspace who are allowed to see team work. Admins see everything.
 */
export function canViewTask(directory: Directory, userId: string, task: MaintenanceTask): boolean {
  if (isAdminRole(directory.roleOf(userId))) return true;
  if (task.ownerProfileId === userId) return true;
  if (task.sharedWith.includes(userId)) return true;
  return (
    task.source.kind !== "chat" &&
    directory.can(userId, "tasks.view.workspace") &&
    directory.workspaceAllowed(userId, task.workspaceId)
  );
}

/** Only the task's owner or an admin decides who else sees it. */
export function canShareTask(directory: Directory, userId: string, task: MaintenanceTask): boolean {
  return task.ownerProfileId === userId || isAdminRole(directory.roleOf(userId));
}
