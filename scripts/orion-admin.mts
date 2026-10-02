#!/usr/bin/env node
/**
 * Orion admin: members, roles, and connectors (org-wide or personal).
 *
 *   node --import ./scripts/tsx.mjs scripts/orion-admin.mts <command> [options]
 *
 * Needs ORION_STATE_DIR and ORION_VAULT_KEY. Secrets are read from a hidden prompt (or one line
 * per field on stdin when piped), never from command-line arguments.
 */
import { createInterface } from "node:readline";
import { CONNECTORS, type ConnectorKind } from "../src/orion/access/connectors.ts";
import { createAccessContext } from "../src/orion/access/context.ts";
import { isRole } from "../src/orion/access/roles.ts";

const [command, ...rest] = process.argv.slice(2);

function opts(args: string[]): { positional: string[]; flags: Map<string, string[]> } {
  const flags = new Map<string, string[]>();
  const positional: string[] = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i] as string;
    if (a.startsWith("--")) {
      const key = a.slice(2);
      flags.set(key, [...(flags.get(key) ?? []), args[++i] ?? ""]);
    } else positional.push(a);
  }
  return { positional, flags };
}

async function readSecret(prompt: string): Promise<string> {
  if (!process.stdin.isTTY) {
    const rl = createInterface({ input: process.stdin });
    for await (const line of rl) {
      rl.close();
      return line.trim();
    }
    return "";
  }
  process.stdout.write(prompt);
  return await new Promise((resolve) => {
    let value = "";
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.on("data", function onData(buf: Buffer) {
      for (const ch of buf.toString("utf8")) {
        if (ch === "\r" || ch === "\n") {
          process.stdin.setRawMode(false);
          process.stdin.pause();
          process.stdin.off("data", onData);
          process.stdout.write("\n");
          resolve(value.trim());
          return;
        }
        if (ch === "\u0003") process.exit(130);
        value = ch === "\u007f" ? value.slice(0, -1) : value + ch;
      }
    });
  });
}

const stateDir = process.env.ORION_STATE_DIR;
if (!stateDir || !command) {
  console.error(
    "usage: orion-admin <bootstrap-owner|members|connectors|connect|disconnect|policy|audit> ... (needs ORION_STATE_DIR, ORION_VAULT_KEY)",
  );
  process.exit(2);
}
const access = createAccessContext({ stateDir, vaultKey: process.env.ORION_VAULT_KEY });
const { positional, flags } = opts(rest);
const flag = (n: string) => flags.get(n)?.[0];
const as = flag("as") ?? "";
const need = (v: string | undefined, what: string): string => {
  if (!v) {
    console.error(`missing ${what}`);
    process.exit(2);
  }
  return v;
};

try {
  switch (command) {
    case "bootstrap-owner":
      console.log(
        JSON.stringify(access.directory.bootstrapOwner(need(positional[0], "profile id"))),
      );
      break;
    case "members":
      if (positional[0] === "set") {
        const role = need(flag("role"), "--role");
        if (!isRole(role)) throw new Error(`unknown role ${role}`);
        console.log(
          JSON.stringify(
            access.directory.setMember(need(as, "--as <your profile id>"), {
              profileId: need(positional[1], "profile id"),
              role,
              ...(flag("workspaces")
                ? { workspaces: flag("workspaces")!.split(",").filter(Boolean) }
                : {}),
              ...(flag("github") ? { githubLogin: flag("github")! } : {}),
              ...(flags.has("address") ? { addresses: flags.get("address")! } : {}),
            }),
          ),
        );
      } else console.log(JSON.stringify(access.directory.list(), null, 2));
      break;
    case "connectors":
      console.log(
        JSON.stringify(access.connectors.list(need(as, "--as <your profile id>")), null, 2),
      );
      break;
    case "connect": {
      const kind = need(positional[0], "connector") as ConnectorKind;
      const def = CONNECTORS[kind];
      if (!def)
        throw new Error(`unknown connector ${kind}; known: ${Object.keys(CONNECTORS).join(", ")}`);
      const scope = need(flag("scope"), "--scope org|user") as "org" | "user";
      const fields: Record<string, string> = {};
      for (const f of def.fields) fields[f] = await readSecret(`${def.label} ${f}: `);
      access.connectors.connect(need(as, "--as <profile id>"), { connector: kind, scope, fields });
      console.log(`connected ${kind} (${scope})`);
      break;
    }
    case "disconnect":
      console.log(
        access.connectors.disconnect(need(as, "--as"), {
          connector: need(positional[0], "connector") as ConnectorKind,
          scope: need(flag("scope"), "--scope") as "org" | "user",
          ...(flag("user") ? { userId: flag("user")! } : {}),
        })
          ? "disconnected"
          : "nothing to disconnect",
      );
      break;
    case "policy": {
      const kind = need(positional[0], "connector") as ConnectorKind;
      const patch: { userScope?: boolean; orgFallback?: boolean } = {};
      if (flag("user-scope")) patch.userScope = flag("user-scope") === "on";
      if (flag("org-fallback")) patch.orgFallback = flag("org-fallback") === "on";
      console.log(JSON.stringify(access.connectors.setPolicy(need(as, "--as"), kind, patch)));
      break;
    }
    case "audit":
      console.log(JSON.stringify(access.vault.auditLog(), null, 2));
      break;
    default:
      throw new Error(`unknown command ${command}`);
  }
} catch (err) {
  console.error(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
} finally {
  access.close();
}
