import {
  createOpenClawAgent,
  createOpenClawClassifier,
  type AgentTurnRunner,
} from "./openclaw-agent.js";
import { createOrionRuntime, type OrionRuntime } from "./runtime.js";

let runtime: OrionRuntime | undefined;
let ticker: ReturnType<typeof setInterval> | undefined;

/** Loads the OpenClaw runtime binding on first use so importing Orion stays cheap. */
const lazyRunner: AgentTurnRunner = async (req) =>
  (await import("./agent-turn-runner.js")).createSystemTurnRunner()(req);

/** Process-wide runtime, created lazily and only when the operator has configured Orion. */
export function getOrionRuntime(env: NodeJS.ProcessEnv = process.env): OrionRuntime | undefined {
  if (runtime) return runtime;
  const stateDir = env.ORION_STATE_DIR?.trim();
  const workspacesDir = env.ORION_WORKSPACES_DIR?.trim();
  if (!stateDir || !workspacesDir) return undefined;
  const agentOpts = {
    run: lazyRunner,
    integration: "real" as const,
    ...(env.ORION_AGENT_ID ? { agentId: env.ORION_AGENT_ID } : {}),
  };
  runtime = createOrionRuntime({
    stateDir,
    workspacesDir,
    env,
    agent: createOpenClawAgent(agentOpts),
    classifier: createOpenClawClassifier(agentOpts),
  });
  return runtime;
}

export function orionConfigured(env: NodeJS.ProcessEnv = process.env): boolean {
  return Boolean(env.ORION_STATE_DIR?.trim() && env.ORION_WORKSPACES_DIR?.trim());
}

/**
 * Resumes unfinished tasks now (crash recovery) and then polls CI and review state on an interval.
 * Idempotent; one tick at a time. Returns a stop function.
 */
export function startOrionScheduler(
  opts: { intervalMs?: number; onError?: (err: unknown) => void } = {},
): () => void {
  const rt = getOrionRuntime();
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
