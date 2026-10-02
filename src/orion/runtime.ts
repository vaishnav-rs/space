import { mkdirSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createUnavailableAgent, type AgentPort } from "./agent-port.js";
import { verifyGithubSignature } from "./events.js";
import { createGitAdapter } from "./git-adapter.js";
import { createGitHubAdapter } from "./github-adapter.js";
import { createFileKnowledge, createGraphKnowledge } from "./knowledge-adapters.js";
import type { KnowledgePort } from "./ports.js";
import { createProductionAdapter } from "./production-adapter.js";
import { createUnavailableClassifier, type FindingClassifier } from "./review-loop.js";
import { EventRouter } from "./router.js";
import { DEFAULT_RUNNER_OPTIONS, MaintenanceRunner, type RunnerOptions } from "./runner.js";
import { createShellAdapter } from "./shell-adapter.js";
import { describeTask, renderTimeline } from "./status.js";
import { BUG_FIX_POLICY } from "./task-machine.js";
import { TaskService } from "./task-service.js";
import { createSqliteTaskStore, type TaskStore } from "./task-store.js";
import { HUMAN_WAIT_STATUSES, TERMINAL_STATUSES, type MaintenanceTask } from "./task-types.js";
import { handleGithubWebhook, type WebhookResult } from "./webhook.js";
import {
  createWorkspaceRegistry,
  parseWorkspaceManifest,
  type WorkspaceManifest,
  type WorkspaceRegistry,
} from "./workspace.js";

export type OrionRuntimeOptions = {
  stateDir: string;
  workspacesDir: string;
  env?: NodeJS.ProcessEnv;
  agent?: AgentPort;
  classifier?: FindingClassifier;
  now?: () => string;
  runner?: Partial<RunnerOptions>;
};

export type OrionRuntime = {
  registry: WorkspaceRegistry;
  tasks: TaskService;
  store: TaskStore;
  router: EventRouter;
  webhook(rawBody: string, headers: Record<string, string | undefined>): WebhookResult;
  /** Advances every task that is not finished or waiting on a person. Call on boot and on a schedule. */
  tick(): Promise<{ taskId: string; status: string }[]>;
  findTask(ref: string): MaintenanceTask | undefined;
  describe(ref: string): string;
  timeline(ref: string): string;
  close(): void;
};

export function loadWorkspaceManifests(dir: string): WorkspaceManifest[] {
  let files: string[];
  try {
    files = readdirSync(dir).filter((f) => f.endsWith(".json"));
  } catch {
    return [];
  }
  return files.map((f) => parseWorkspaceManifest(JSON.parse(readFileSync(join(dir, f), "utf8"))));
}

function knowledgeFor(ws: WorkspaceManifest): KnowledgePort[] {
  const root = ws.repository?.root ?? ".";
  return ws.knowledge.map((k) => {
    const path = k.path ? (k.path.startsWith("/") ? k.path : join(root, k.path)) : root;
    return k.kind === "graphify"
      ? createGraphKnowledge({ id: k.id, graphFile: path })
      : createFileKnowledge({
          id: k.id,
          kind: k.kind,
          root: path,
          extensions:
            k.kind === "code"
              ? [".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".rs", ".java"]
              : undefined,
        });
  });
}

export function createOrionRuntime(opts: OrionRuntimeOptions): OrionRuntime {
  const env = opts.env ?? process.env;
  const now = opts.now ?? (() => new Date().toISOString());
  mkdirSync(opts.stateDir, { recursive: true });
  const db = new DatabaseSync(join(opts.stateDir, "orion-tasks.sqlite"));
  const store = createSqliteTaskStore(db);
  const registry = createWorkspaceRegistry(loadWorkspaceManifests(opts.workspacesDir));
  const tasks = new TaskService(store, now);
  const router = new EventRouter(registry, tasks, store, now);
  const agent = opts.agent ?? createUnavailableAgent();
  const classifier = opts.classifier ?? createUnavailableClassifier();
  const github = createGitHubAdapter({
    ...(env.ORION_GITHUB_TOKEN ? { token: env.ORION_GITHUB_TOKEN } : {}),
  });

  const runnerFor = (ws: WorkspaceManifest): MaintenanceRunner => {
    const repo = ws.repository;
    if (!repo) throw new Error(`workspace ${ws.id} has no repository`);
    return new MaintenanceRunner(
      tasks,
      ws,
      {
        git: createGitAdapter({
          branchPrefix: repo.branchPrefix,
          defaultBranch: repo.defaultBranch,
          worktreesDir: repo.worktreesDir,
        }),
        github,
        shell: createShellAdapter(),
        agent,
        classifier,
        knowledge: knowledgeFor(ws),
        production: createProductionAdapter(ws),
      },
      { ...DEFAULT_RUNNER_OPTIONS, ...opts.runner },
    );
  };

  const findTask = (ref: string): MaintenanceTask | undefined => {
    const n = /^#?(\d+)$/.exec(ref.trim());
    const all = store.list();
    if (n)
      return all.find(
        (t) => t.source.kind === "github-issue" && t.source.issueNumber === Number(n[1]),
      );
    return all.find((t) => t.id === ref || t.id.startsWith(ref));
  };

  return {
    registry,
    tasks,
    store,
    router,
    webhook(rawBody, headers) {
      const secret = env.ORION_GITHUB_WEBHOOK_SECRET ?? "";
      if (!verifyGithubSignature(secret, rawBody, headers["x-hub-signature-256"])) {
        return { status: 401, error: "invalid signature" };
      }
      let repo: string | undefined;
      try {
        repo = (JSON.parse(rawBody) as { repository?: { full_name?: string } }).repository
          ?.full_name;
      } catch {
        return { status: 400, error: "invalid JSON" };
      }
      const ws = repo ? registry.findByRepo(repo) : undefined;
      if (!ws?.github) {
        return { status: 200, ignored: `no workspace for ${repo ?? "unknown repository"}` };
      }
      return handleGithubWebhook({
        secret,
        rawBody,
        headers,
        agentHandle: ws.github.agentHandle,
        router,
      });
    },
    async tick() {
      const out: { taskId: string; status: string }[] = [];
      for (const t of store.list()) {
        if (TERMINAL_STATUSES.has(t.status) || HUMAN_WAIT_STATUSES.has(t.status)) continue;
        const ws = registry.get(t.workspaceId);
        if (!ws) continue;
        const r = await runnerFor(ws).runUntilIdle(t.id);
        out.push({ taskId: t.id, status: r.status });
      }
      return out;
    },
    findTask,
    describe(ref) {
      const t = findTask(ref);
      return t ? describeTask(t, BUG_FIX_POLICY) : `No task found for ${ref}`;
    },
    timeline(ref) {
      const t = findTask(ref);
      return t ? renderTimeline(tasks.timeline(t.id)) : `No task found for ${ref}`;
    },
    close: () => db.close(),
  };
}
