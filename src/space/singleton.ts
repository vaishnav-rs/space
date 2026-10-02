import { createSpaceRuntime, type SpaceRuntime } from "./runtime.js";

let runtime: SpaceRuntime | undefined;

/** Process-wide runtime, created lazily and only when the operator has configured Space. */
export function getSpaceRuntime(env: NodeJS.ProcessEnv = process.env): SpaceRuntime | undefined {
  if (runtime) return runtime;
  const stateDir = env.SPACE_STATE_DIR?.trim();
  const workspacesDir = env.SPACE_WORKSPACES_DIR?.trim();
  if (!stateDir || !workspacesDir) return undefined;
  runtime = createSpaceRuntime({ stateDir, workspacesDir, env });
  return runtime;
}

export function spaceConfigured(env: NodeJS.ProcessEnv = process.env): boolean {
  return Boolean(env.SPACE_STATE_DIR?.trim() && env.SPACE_WORKSPACES_DIR?.trim());
}
