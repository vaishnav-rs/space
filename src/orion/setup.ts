import { randomBytes } from "node:crypto";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import { createAccessContext } from "./access/context.js";

export type SetupInput = {
  /** Where Orion keeps everything (state, workspaces, env, config). */
  home: string;
  /** Checkout that holds `personal/` templates. */
  repoRoot: string;
  /** Profile id of the owner (used for the access directory). */
  owner: string;
  ownerEmail?: string;
  /** Owner's WhatsApp number, E.164. */
  ownerWhatsapp?: string;
  hewar?: { remote: string; root: string; githubRepo: string; githubUser?: string; sshHost?: string };
  devRepos?: string[];
  gatewayPort?: number;
};

export type SetupReport = {
  envFile: string;
  configFile: string;
  startScript: string;
  created: string[];
  kept: string[];
  notes: string[];
};

/** Parses KEY=VALUE lines; unknown lines are ignored. */
export function parseEnvFile(text: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)=(.*)$/.exec(line);
    if (m) out.set(m[1] as string, (m[2] as string).replace(/^"(.*)"$/, "$1"));
  }
  return out;
}

const secret = (bytes: number, enc: "base64" | "hex") => randomBytes(bytes).toString(enc);

/**
 * Idempotent: values and files that already exist are kept, so re-running after an update or a
 * partial failure never rotates the vault key (which would orphan stored credentials) or the
 * gateway token (which would unpair devices).
 */
export function provisionOrion(input: SetupInput): SetupReport {
  const { home } = input;
  const created: string[] = [];
  const kept: string[] = [];
  const notes: string[] = [];
  const stateDir = join(home, "state");
  const workspacesDir = join(home, "workspaces");
  const agentWorkspace = join(home, "agent-workspace");
  for (const d of [home, stateDir, workspacesDir, agentWorkspace]) mkdirSync(d, { recursive: true });

  const envFile = join(home, "orion.env");
  const existing = existsSync(envFile) ? parseEnvFile(readFileSync(envFile, "utf8")) : new Map();
  const configFile = join(home, "openclaw.json");
  const port = input.gatewayPort ?? 18789;
  const wanted: Array<[string, string | undefined, boolean]> = [
    // [key, value, generated]. Existing values always win.
    ["ORION_STATE_DIR", stateDir, false],
    ["ORION_WORKSPACES_DIR", workspacesDir, false],
    ["ORION_VAULT_KEY", secret(32, "base64"), true],
    ["ORION_GITHUB_WEBHOOK_SECRET", secret(24, "hex"), true],
    ["ORION_DEFAULT_REQUESTER", input.owner, false],
    ["OPENCLAW_CONFIG_PATH", configFile, false],
    ["OPENCLAW_STATE_DIR", join(home, "openclaw"), false],
    ["OPENCLAW_GATEWAY_TOKEN", secret(24, "hex"), true],
    ["PERSONAL_OWNER_EMAILS", input.ownerEmail, false],
    ["PERSONAL_OWNER_TARGETS", input.ownerWhatsapp, false],
    ["PERSONAL_DEV_REPOS", input.devRepos?.join(","), false],
  ];
  const lines: string[] = [];
  for (const [key, value, generated] of wanted) {
    const prior = existing.get(key);
    const final = prior ?? value;
    if (final === undefined || final === "") continue;
    if (prior === undefined) created.push(generated ? `${key} (generated)` : key);
    else kept.push(key);
    lines.push(`${key}=${final}`);
    existing.set(key, final);
  }
  // Keep credentials the user added by hand (GOOGLE_*, RESEND_*, ORION_GITHUB_TOKEN, ...).
  for (const [k, v] of existing) if (!wanted.some(([w]) => w === k)) lines.push(`${k}=${v}`);
  writeFileSync(envFile, `${lines.join("\n")}\n`, { mode: 0o600 });
  chmodSync(envFile, 0o600);

  // Owner directory entry: a no-op if an owner already exists.
  const access = createAccessContext({
    stateDir,
    vaultKey: existing.get("ORION_VAULT_KEY"),
  });
  try {
    if (access.directory.hasOwner()) kept.push("owner");
    else {
      access.directory.bootstrapOwner(input.owner);
      created.push(`owner ${input.owner}`);
    }
  } finally {
    access.close();
  }

  // Workspace manifests.
  const personalManifest = join(workspacesDir, "personal.json");
  if (!existsSync(personalManifest)) {
    copyFileSync(join(input.repoRoot, "personal/workspaces/personal.json"), personalManifest);
    created.push("workspaces/personal.json");
  } else kept.push("workspaces/personal.json");
  const hewarManifest = join(workspacesDir, "hewar.json");
  if (input.hewar && !existsSync(hewarManifest)) {
    const m = JSON.parse(
      readFileSync(join(input.repoRoot, "personal/workspaces/hewar.example.json"), "utf8"),
    );
    m.repository = {
      ...m.repository,
      remote: input.hewar.remote,
      root: input.hewar.root,
      worktreesDir: `${input.hewar.root}-agent-worktrees`,
    };
    m.github = {
      ...m.github,
      repo: input.hewar.githubRepo,
      authorizedUsers: input.hewar.githubUser ? [input.hewar.githubUser] : [],
    };
    if (input.hewar.sshHost) m.production = { ...m.production, sshHost: input.hewar.sshHost };
    else delete m.production;
    writeFileSync(hewarManifest, `${JSON.stringify(m, null, 2)}\n`);
    created.push("workspaces/hewar.json");
  } else if (!input.hewar && !existsSync(hewarManifest)) {
    notes.push("Hewar workspace skipped (no repository given); re-run with --hewar-root to add it.");
  }

  // Agent persona files (never overwrite the user's edits).
  const tpl = join(input.repoRoot, "personal/workspace");
  for (const f of existsSync(tpl) ? readdirSync(tpl) : []) {
    const dest = join(agentWorkspace, f);
    if (existsSync(dest)) continue;
    copyFileSync(join(tpl, f), dest);
    created.push(`agent-workspace/${f}`);
  }

  // Gateway config: the personal layer plus the owner and workspace. Kept once written so edits stick.
  if (!existsSync(configFile)) {
    const owner = input.ownerWhatsapp ? [`whatsapp:${input.ownerWhatsapp}`] : [];
    const cfg = {
      gateway: { mode: "local", port, bind: "lan", auth: { mode: "token" } },
      commands: { ownerAllowFrom: owner },
      agents: {
        defaults: {
          workspace: agentWorkspace,
          heartbeat: {
            every: "10m",
            target: "owner",
            directPolicy: "allow",
            lightContext: false,
            activeHours: { start: "07:30", end: "23:30" },
            prompt:
              "Proactive sweep. Check unread/important Gmail, today's calendar (next 3h), open PRs/reviews/CI on my repos, " +
              "due Google Tasks, and my own plan progress. Message me on WhatsApp only when something needs me, " +
              "is about to be late, or has an obvious next step you can propose. Otherwise reply NO_REPLY.",
          },
        },
      },
    };
    writeFileSync(configFile, `${JSON.stringify(cfg, null, 2)}\n`);
    created.push("openclaw.json");
  } else kept.push("openclaw.json");

  const startScript = join(home, process.platform === "win32" ? "start.cmd" : "start.sh");
  const unix = `#!/usr/bin/env bash
set -euo pipefail
set -a; . "${envFile}"; set +a
cd "${input.repoRoot}"
exec pnpm openclaw gateway run --port ${port}
`;
  const win = `@echo off\r\nfor /f "usebackq tokens=1,* delims==" %%a in ("${envFile}") do set "%%a=%%b"\r\ncd /d "${input.repoRoot}"\r\npnpm openclaw gateway run --port ${port}\r\n`;
  writeFileSync(startScript, process.platform === "win32" ? win : unix, { mode: 0o755 });

  if (!existing.get("GOOGLE_REFRESH_TOKEN")) {
    notes.push("Google (Gmail/Calendar/Tasks) is not connected: add GOOGLE_* to orion.env or connect from the app.");
  }
  if (!existing.get("RESEND_API_KEY")) notes.push("Resend is not configured: add RESEND_API_KEY and RESEND_FROM to orion.env.");
  if (!existing.get("ORION_GITHUB_TOKEN")) {
    notes.push("GitHub is not connected: add ORION_GITHUB_TOKEN or authorize from the app (Orion admin).");
  }
  return { envFile, configFile, startScript, created, kept, notes };
}
