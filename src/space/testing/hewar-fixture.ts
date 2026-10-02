import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseWorkspaceManifest, type WorkspaceManifest } from "../workspace.js";

const git = (cwd: string, ...args: string[]) =>
  execFileSync("git", args, {
    cwd,
    stdio: "pipe",
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
  }).toString();

export type HewarFixture = {
  dir: string;
  checkout: string;
  origin: string;
  worktreesDir: string;
  manifest: WorkspaceManifest;
};

/** A real git repo with an origin, a developer checkout holding uncommitted work, and a Hewar manifest. */
export function createHewarFixture(): HewarFixture {
  const dir = mkdtempSync(join(tmpdir(), "space-hewar-"));
  const origin = join(dir, "origin.git");
  const checkout = join(dir, "hewar");
  const worktreesDir = join(dir, "worktrees");
  execFileSync("git", ["init", "--bare", "-b", "main", origin], { stdio: "pipe" });
  execFileSync("git", ["clone", origin, checkout], { stdio: "pipe" });
  git(checkout, "config", "user.email", "dev@example.test");
  git(checkout, "config", "user.name", "Dev");
  git(checkout, "checkout", "-b", "main");
  mkdirSync(join(checkout, "src"));
  mkdirSync(join(checkout, "test"));
  writeFileSync(join(checkout, "src/upload.js"), "exports.MAX_UPLOAD_BYTES = 100 * 1024;\n");
  writeFileSync(
    join(checkout, "test/run.js"),
    "const { MAX_UPLOAD_BYTES } = require('../src/upload.js');\nif (MAX_UPLOAD_BYTES < 1) process.exit(1);\n",
  );
  writeFileSync(join(checkout, "README.md"), "# Hewar\n");
  git(checkout, "add", "-A");
  git(checkout, "commit", "-m", "initial");
  git(checkout, "push", "-u", "origin", "main");
  // The developer's own in-progress work. The agent must never touch it.
  writeFileSync(join(checkout, "README.md"), "# Hewar\nDEV WIP\n");
  writeFileSync(join(checkout, "scratch.txt"), "dev notes\n");

  const manifest = parseWorkspaceManifest({
    id: "hewar",
    name: "Hewar",
    kind: "project",
    repository: {
      remote: origin,
      defaultBranch: "main",
      root: checkout,
      worktreesDir,
      branchPrefix: "agent",
    },
    packageManager: "npm",
    commands: { test: "node test/run.js", testTargeted: "node {files}" },
    knowledge: [
      { id: "graph", kind: "graphify" },
      { id: "wiki", kind: "wiki" },
      { id: "docs", kind: "product-docs" },
    ],
    github: { repo: "acme/hewar", agentHandle: "@hewar-agent", authorizedUsers: ["vaishnav"] },
    production: {
      sshHost: "hewar-prod",
      logSources: { app: { unit: "hewar-api" } },
      services: ["hewar-api"],
    },
    review: { copilot: true, maxReviewIterations: 3, requireCi: true },
    policy: {
      grant: [
        "filesystem.read",
        "filesystem.write",
        "shell.execute",
        "git.read",
        "git.write",
        "git.commit",
        "git.push",
        "github.issue.read",
        "github.issue.comment",
        "github.pr.read",
        "github.pr.create",
        "github.pr.comment",
        "github.pr.review.read",
        "github.ci.read",
        "knowledge.read",
        "prod.read.logs",
        "prod.read.processes",
        "prod.read.services",
      ],
      requireApproval: ["prod.restart", "prod.deploy", "prod.database.write", "prod.exec"],
    },
  });
  return { dir, checkout, origin, worktreesDir, manifest };
}
