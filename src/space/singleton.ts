import {
  createOpenClawAgent,
  createOpenClawClassifier,
  type AgentTurnRunner,
} from "./openclaw-agent.js";
import { createSpaceRuntime, type SpaceRuntime } from "./runtime.js";

let runtime: SpaceRuntime | undefined;
let ticker: ReturnType<typeof setInterval> | undefined;

/** Loads the OpenClaw runtime binding on first use so importing Space stays cheap. */
const lazyRunner: AgentTurnRunner = async (req) =>
  (await import("./agent-turn-runner.js")).createSystemTurnRunner()(req);

/** Process-wide runtime, created lazily and only when the operator has configured Space. */
export function getSpaceRuntime(env: NodeJS.ProcessEnv = process.env): SpaceRuntime | undefined {
  if (runtime) return runtime;
  const stateDir = env.SPACE_STATE_DIR?.trim();
  const workspacesDir = env.SPACE_WORKSPACES_DIR?.trim();
  if (!stateDir || !workspacesDir) return undefined;
  const agentOpts = {
    run: lazyRunner,
    integration: "real" as const,
    ...(env.SPACE_AGENT_ID ? { agentId: env.SPACE_AGENT_ID } : {}),
  };
  runtime = createSpaceRuntime({
    stateDir,
    workspacesDir,
    env,
    agent: createOpenClawAgent(agentOpts),
    classifier: createOpenClawClassifier(agentOpts),
  });
  return runtime;
}

export function spaceConfigured(env: NodeJS.ProcessEnv = process.env): boolean {
  return Boolean(env.SPACE_STATE_DIR?.trim() && env.SPACE_WORKSPACES_DIR?.trim());
}

/**
 * Resumes unfinished tasks now (crash recovery) and then polls CI and review state on an interval.
 * Idempotent; one tick at a time. Returns a stop function.
 */
export function startSpaceScheduler(
  opts: { intervalMs?: number; onError?: (err: unknown) => void } = {},
): () => void {
  const rt = getSpaceRuntime();
  if (!rt || ticker) return () => {};
  let running = false;
  const tick = async () => {
    if (running) return;
    running = true;
    try {
      await rt.tick();
    } catch (err) {
      opts.onError?.(err);
    } finally {
      running = false;
    }
  };
  void tick();
  ticker = setInterval(() => void tick(), opts.intervalMs ?? 60_000);
  ticker.unref();
  return () => {
    if (ticker) clearInterval(ticker);
    ticker = undefined;
  };
}
