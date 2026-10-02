/**
 * Memory is partitioned so project knowledge does not become personal memory and vice versa.
 *   personal:global        owner's assistant memory
 *   project:<workspace>    durable facts about one project
 *   task:<id>              working state of one maintenance task
 *   conversation:<key>     one chat's transcript context
 */
export type MemoryScope =
  | `personal:global`
  | `project:${string}`
  | `task:${string}`
  | `conversation:${string}`;

export type ReaderContext = {
  workspaceId: string;
  taskId?: string;
  conversationKey?: string;
  /** Explicit, owner-granted bridge to personal memory (e.g. "use my calendar for scheduling"). */
  allowPersonal?: boolean;
};

export function canRead(reader: ReaderContext, scope: MemoryScope): boolean {
  if (scope === "personal:global") {
    return reader.workspaceId === "personal" || reader.allowPersonal === true;
  }
  if (scope.startsWith("project:")) {
    return scope === `project:${reader.workspaceId}`;
  }
  if (scope.startsWith("task:")) {
    return reader.taskId !== undefined && scope === `task:${reader.taskId}`;
  }
  return reader.conversationKey !== undefined && scope === `conversation:${reader.conversationKey}`;
}

export const canWrite = canRead;
