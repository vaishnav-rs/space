#!/usr/bin/env node
/**
 * One-command Orion setup: generates secrets, creates state and workspaces, bootstraps the owner,
 * writes the gateway config and start script. Safe to re-run; existing values are kept.
 *
 *   node --import ./scripts/tsx.mjs scripts/orion-setup.mts --owner you --email you@x.com --whatsapp +15550001111 \
 *     [--home ~/.orion] [--hewar-root ~/code/hewar --hewar-remote git@github.com:org/hewar.git --hewar-repo org/hewar \
 *      --github-user login --ssh-host hewar-prod] [--dev-repo path]... [--port 18789]
 */
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { provisionOrion } from "../src/orion/setup.ts";

const flags = new Map<string, string[]>();
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  const a = args[i] as string;
  if (!a.startsWith("--")) continue;
  flags.set(a.slice(2), [...(flags.get(a.slice(2)) ?? []), args[++i] ?? ""]);
}
const f = (n: string) => flags.get(n)?.[0];
const owner = f("owner") ?? process.env.ORION_DEFAULT_REQUESTER ?? "owner";
const hewarRoot = f("hewar-root");
const report = provisionOrion({
  home: resolve(f("home") ?? process.env.ORION_HOME ?? join(homedir(), ".orion")),
  repoRoot: resolve(import.meta.dirname, ".."),
  owner,
  ownerEmail: f("email"),
  ownerWhatsapp: f("whatsapp"),
  devRepos: flags.get("dev-repo"),
  ...(f("port") ? { gatewayPort: Number(f("port")) } : {}),
  ...(hewarRoot
    ? {
        hewar: {
          root: resolve(hewarRoot),
          remote: f("hewar-remote") ?? "",
          githubRepo: f("hewar-repo") ?? "",
          githubUser: f("github-user"),
          sshHost: f("ssh-host"),
        },
      }
    : {}),
});
console.log(JSON.stringify(report, null, 2));
