import { execFile } from "node:child_process";
import { mkdir, realpath } from "node:fs/promises";
import { basename, isAbsolute, join, relative, resolve } from "node:path";
import { promisify } from "node:util";
import type { GitPort, GitState } from "./ports.js";

const exec = promisify(execFile);

const GIT_ENV = { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_OPTIONAL_LOCKS: "0" };

async function git(cwd: string, args: string[], input?: { maxBuffer?: number }): Promise<string> {
  const { stdout } = await exec("git", args, {
    cwd,
    env: GIT_ENV,
    maxBuffer: input?.maxBuffer ?? 16 * 1024 * 1024,
  });
  return stdout;
}

export function agentBranchName(prefix: string, workspaceId: string, issueNumber: number): string {
  return `${prefix}/${workspaceId}-${issueNumber}`;
}

export class GitSafetyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GitSafetyError";
  }
}

function inside(parent: string, child: string): boolean {
  const rel = relative(parent, child);
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel);
}

/**
 * Real git adapter. Safety invariants: agents only write inside `worktreesDir`, never push the
 * default or a non-agent branch, never force-push, and never touch the developer's checkout.
 */
export function createGitAdapter(opts: {
  branchPrefix: string;
  defaultBranch: string;
  worktreesDir: string;
}): GitPort {
  const assertAgentWorktree = async (worktree: string) => {
    const root = await realpath(opts.worktreesDir).catch(() => resolve(opts.worktreesDir));
    const target = await realpath(worktree).catch(() => resolve(worktree));
    if (!inside(root, target)) {
      throw new GitSafetyError(`${worktree} is outside the agent worktrees directory`);
    }
  };
  const assertAgentBranch = (branch: string) => {
    if (branch === opts.defaultBranch || !branch.startsWith(`${opts.branchPrefix}/`)) {
      throw new GitSafetyError(`refusing to operate on non-agent branch ${branch}`);
    }
  };
  const log: GitPort["log"] = async (path, { paths, limit }) => {
    const out = await git(path, [
      "log",
      `-n${limit}`,
      "--pretty=format:%H%x1f%s%x1f%an%x1f%aI",
      ...(paths?.length ? ["--", ...paths] : []),
    ]);
    return out
      .split("\n")
      .filter(Boolean)
      .map((l) => {
        const [sha = "", subject = "", author = "", date = ""] = l.split("\x1f");
        return { sha, subject, author, date };
      });
  };

  return {
    integration: "real",
    name: "git",
    log,
    async inspect(path) {
      const head = (await git(path, ["rev-parse", "HEAD"])).trim();
      const branch = (await git(path, ["rev-parse", "--abbrev-ref", "HEAD"])).trim();
      const status = await git(path, ["status", "--porcelain=v1", "-z"]);
      const dirty: string[] = [];
      const untracked: string[] = [];
      for (const entry of status.split("\0").filter(Boolean)) {
        const file = entry.slice(3);
        (entry.startsWith("??") ? untracked : dirty).push(file);
      }
      let upstream: string | null = null;
      let ahead = 0;
      let behind = 0;
      try {
        upstream = (await git(path, ["rev-parse", "--abbrev-ref", "@{upstream}"])).trim();
        const [a = "0", b = "0"] = (
          await git(path, ["rev-list", "--left-right", "--count", `HEAD...${upstream}`])
        )
          .trim()
          .split(/\s+/);
        ahead = Number(a);
        behind = Number(b);
      } catch {
        // No upstream configured.
      }
      const state: GitState = {
        branch: branch === "HEAD" ? null : branch,
        head,
        dirty,
        untracked,
        upstream,
        ahead,
        behind,
        recentCommits: await log(path, { limit: 10 }),
      };
      return state;
    },
    async createWorktree({ repoRoot, worktreesDir, branch, base }) {
      assertAgentBranch(branch);
      const target = resolve(join(worktreesDir, basename(branch.replaceAll("/", "-"))));
      if (!inside(resolve(worktreesDir), target)) {
        throw new GitSafetyError("worktree path escapes the worktrees directory");
      }
      await mkdir(worktreesDir, { recursive: true });
      const baseSha = (await git(repoRoot, ["rev-parse", "--verify", `${base}^{commit}`])).trim();
      // `-b` fails if the branch exists, so an existing human branch is never reset or reused.
      await git(repoRoot, ["worktree", "add", "-b", branch, target, baseSha]);
      return { worktree: target, baseSha };
    },
    async diffStat(worktree, baseSha) {
      await assertAgentWorktree(worktree);
      await git(worktree, ["add", "-A", "-N", "."]);
      const files = (await git(worktree, ["diff", "--name-only", baseSha]))
        .split("\n")
        .filter(Boolean);
      const patch = await git(worktree, ["diff", baseSha]);
      return { files, patch };
    },
    async commit({ worktree, message }) {
      await assertAgentWorktree(worktree);
      await git(worktree, ["add", "-A"]);
      await git(worktree, ["commit", "-m", message]);
      return { sha: (await git(worktree, ["rev-parse", "HEAD"])).trim() };
    },
    async push({ worktree, branch, remote = "origin" }) {
      await assertAgentWorktree(worktree);
      assertAgentBranch(branch);
      await git(worktree, ["push", "--set-upstream", remote, `${branch}:${branch}`]);
    },
  };
}
