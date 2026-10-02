import type { Capability } from "./capabilities.js";

/**
 * Every external dependency sits behind a port that says what it really is. Production wiring
 * must never hand a `mock` or `stub` to a task; the runner refuses non-real ports unless the
 * caller opts in (tests).
 */
export type IntegrationKind = "real" | "mock" | "stub" | "unavailable";

export type PortBase = { readonly integration: IntegrationKind; readonly name: string };

export class IntegrationUnavailableError extends Error {
  constructor(
    readonly port: string,
    reason: string,
  ) {
    super(`${port} unavailable: ${reason}`);
    this.name = "IntegrationUnavailableError";
  }
}

export type GitState = {
  branch: string | null;
  head: string;
  /** Modified or staged tracked files: the developer's work. */
  dirty: string[];
  untracked: string[];
  upstream: string | null;
  ahead: number;
  behind: number;
  recentCommits: { sha: string; subject: string; author: string; date: string }[];
};

export interface GitPort extends PortBase {
  inspect(path: string): Promise<GitState>;
  createWorktree(input: {
    repoRoot: string;
    worktreesDir: string;
    branch: string;
    base: string;
  }): Promise<{ worktree: string; baseSha: string }>;
  /** Files changed relative to the worktree's base. */
  diffStat(worktree: string, baseSha: string): Promise<{ files: string[]; patch: string }>;
  commit(input: { worktree: string; message: string }): Promise<{ sha: string }>;
  push(input: { worktree: string; branch: string; remote?: string }): Promise<void>;
  log(path: string, opts: { paths?: string[]; limit: number }): Promise<GitState["recentCommits"]>;
}

export type CiState = "pending" | "passing" | "failing" | "unknown";

export type RemoteReview = {
  id: string;
  reviewer: string;
  state: string;
  body: string;
  comments: { id: string; path?: string; line?: number; body: string }[];
};

export interface GitHubPort extends PortBase {
  commentOnIssue(repo: string, issueNumber: number, body: string): Promise<void>;
  createPullRequest(input: {
    repo: string;
    head: string;
    base: string;
    title: string;
    body: string;
  }): Promise<{ number: number; url: string; headSha: string }>;
  requestCopilotReview(repo: string, prNumber: number): Promise<void>;
  listReviews(repo: string, prNumber: number): Promise<RemoteReview[]>;
  getCiState(repo: string, sha: string): Promise<CiState>;
  commentOnPullRequest(repo: string, prNumber: number, body: string): Promise<void>;
}

export type ProductionRead = Extract<
  Capability,
  "prod.read.logs" | "prod.read.processes" | "prod.read.services" | "prod.read.metrics"
>;

export interface ProductionPort extends PortBase {
  readLogs(input: {
    source: string;
    lines: number;
    since?: string;
    grep?: string;
  }): Promise<string>;
  readProcesses(): Promise<string>;
  readServiceStatus(service: string): Promise<string>;
}

export type KnowledgeSourceKind = "graphify" | "wiki" | "product-docs" | "code" | "git" | "other";

export type KnowledgeHit = {
  source: string;
  kind: KnowledgeSourceKind;
  ref: string;
  text: string;
  score: number;
  /** Atomic statements this hit makes, used for contradiction detection. */
  claims?: { subject: string; predicate: string; value: string }[];
};

export interface KnowledgePort extends PortBase {
  readonly kind: KnowledgeSourceKind;
  readonly id: string;
  query(q: { text: string; terms: string[]; limit: number }): Promise<KnowledgeHit[]>;
}

export type ShellResult = { exitCode: number; output: string };

export interface ShellPort extends PortBase {
  /** Runs a project command inside an agent worktree. The command comes from the manifest, never the model. */
  run(input: { cwd: string; command: string; timeoutMs: number }): Promise<ShellResult>;
}
