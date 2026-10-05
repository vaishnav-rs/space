#!/usr/bin/env node
/**
 * Builds or updates the gateway config for the Termux install (apps/android/scripts/orion-termux.sh).
 * The shape is validated by the gateway's own validator in src/orion/termux-config.test.ts.
 *
 *   node termux-config.mjs <config-path> <workspace-dir> [--local-model <id> <gguf-name> <context>]
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

export const LOCAL_PROVIDER = "local-llama";
// Not 8080: that port is commonly taken on phones (dev servers, other apps), and llama-server then fails to bind.
export const LOCAL_PORT = 18791;

export function baseConfig(workspace) {
  return {
    gateway: { mode: "local", port: 18790, bind: "loopback", auth: { mode: "token" } },
    agents: {
      defaults: { workspace },
      entries: { main: { identity: { name: "Orion", theme: "proactive personal assistant" } } },
    },
  };
}

/** A llama-server (llama.cpp) endpoint on this phone, selected as the default model. */
export function withLocalModel(config, { id, name, contextWindow }) {
  const next = structuredClone(config);
  next.models = {
    ...next.models,
    mode: "merge",
    providers: {
      ...next.models?.providers,
      [LOCAL_PROVIDER]: {
        baseUrl: `http://127.0.0.1:${LOCAL_PORT}/v1`,
        apiKey: "llamacpp-no-key",
        api: "openai-completions",
        models: [
          {
            id,
            name,
            reasoning: false,
            input: ["text"],
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
            contextWindow,
            maxTokens: 2048,
            compat: { supportsTools: true, toolSchemaProfile: "llamacpp" },
          },
        ],
      },
    },
  };
  next.agents = {
    ...next.agents,
    defaults: { ...next.agents?.defaults, model: { primary: `${LOCAL_PROVIDER}/${id}` } },
  };
  return next;
}

/** Existing settings win; only keys the install owns are added, so re-running never undoes the user's edits. */
export function mergeInto(existing, desired) {
  if (!existing) return desired;
  const out = structuredClone(existing);
  out.gateway = { ...desired.gateway, ...out.gateway };
  out.agents = { ...desired.agents, ...out.agents };
  out.agents.defaults = { ...desired.agents.defaults, ...out.agents.defaults };
  if (!out.agents.entries || Object.keys(out.agents.entries).length === 0) out.agents.entries = desired.agents.entries;
  if (desired.models) {
    out.models = { ...out.models, mode: "merge", providers: { ...out.models?.providers, ...desired.models.providers } };
    out.agents.defaults.model = desired.agents.defaults.model;
  }
  return out;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [configPath, workspace, ...rest] = process.argv.slice(2);
  if (!configPath || !workspace) {
    console.error("usage: termux-config.mjs <config-path> <workspace-dir> [--local-model <id> <name> <context>]");
    process.exit(2);
  }
  let desired = baseConfig(workspace);
  const flag = rest.indexOf("--local-model");
  if (flag >= 0) {
    const [id, name, context] = rest.slice(flag + 1);
    desired = withLocalModel(desired, { id, name, contextWindow: Number(context) });
  }
  let existing;
  if (existsSync(configPath)) {
    try {
      existing = JSON.parse(readFileSync(configPath, "utf8"));
    } catch {
      console.error(`${configPath} is not plain JSON; leaving it unchanged.`);
      process.exit(0);
    }
  }
  mkdirSync(dirname(configPath), { recursive: true });
  writeFileSync(configPath, `${JSON.stringify(mergeInto(existing, desired), null, 2)}\n`);
  console.log(`config written: ${configPath}`);
}
