import type { WorkspaceRegistry } from "./workspace.js";

export type WorkspaceRoute = { workspaceId: string; reason: string };

export const PERSONAL = "personal";

/**
 * Chooses which workspace a request belongs to. Personal is the default so ordinary assistant
 * conversations are never contaminated by developer behavior. A project activates only on an
 * explicit selection, a configured GitHub repo reference, or the project's own name.
 */
export function routeRequest(
  text: string,
  registry: WorkspaceRegistry,
  explicit?: string,
): WorkspaceRoute {
  if (explicit && registry.get(explicit)) {
    return { workspaceId: explicit, reason: "selected explicitly" };
  }
  const lower = text.toLowerCase();
  for (const ws of registry.list()) {
    if (ws.kind !== "project") continue;
    const repo = ws.github?.repo.toLowerCase();
    if (repo && lower.includes(repo)) return { workspaceId: ws.id, reason: `mentions ${repo}` };
    const names = [ws.id, ws.name.toLowerCase()];
    if (
      names.some((n) => new RegExp(`\\b${n.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`).test(lower))
    ) {
      return { workspaceId: ws.id, reason: `mentions ${ws.name}` };
    }
  }
  return { workspaceId: PERSONAL, reason: "default assistant behavior" };
}
