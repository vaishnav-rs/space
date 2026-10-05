import { describe, expect, it } from "vitest";
import { validateConfigObjectRaw } from "../config/validation-core.js";

type Config = {
  gateway: { port: number };
  agents: { defaults: { model: { primary: string } } };
  models: { providers: Record<string, { models: Array<{ id: string }> }> };
};
type Api = {
  baseConfig: (workspace: string) => Config;
  withLocalModel: (
    config: Config,
    model: { id: string; name: string; contextWindow: number },
  ) => Config;
  mergeInto: (existing: Config, desired: Config) => Config;
};
// A plain ESM script shipped to phones, outside the TypeScript build; loaded by URL so it needs no declarations.
const scriptUrl = new URL("../../apps/android/scripts/termux-config.mjs", import.meta.url).href;
const { baseConfig, mergeInto, withLocalModel } = (await import(scriptUrl)) as Api;

const gemma = { id: "gemma-4-e2b", name: "Gemma 4 E2B (local)", contextWindow: 16384 };

function expectValid(config: unknown) {
  const result = validateConfigObjectRaw(config);
  expect(result.ok, JSON.stringify(result.ok ? [] : result.issues)).toBe(true);
}

describe("Termux install config", () => {
  it("base config is accepted by the gateway validator", () => {
    expectValid(baseConfig("/data/data/com.termux/files/home/.orion/workspaces/agent"));
  });

  it("local llama.cpp model config is accepted and becomes the default model", () => {
    const config = withLocalModel(baseConfig("/w"), gemma);
    expectValid(config);
    expect(config.agents.defaults.model.primary).toBe("local-llama/gemma-4-e2b");
  });

  it("accepts the Tailscale bind settings written by `orion tailscale on`", () => {
    const base = baseConfig("/w");
    expectValid({ ...base, gateway: { ...base.gateway, bind: "tailnet" } });
    expectValid({
      ...base,
      gateway: { ...base.gateway, bind: "custom", customBindHost: "100.101.102.103" },
    });
  });

  it("re-running keeps the user's settings and only adds what the install owns", () => {
    const existing = { ...baseConfig("/w"), gateway: { ...baseConfig("/w").gateway, port: 19000 } };
    const merged = mergeInto(existing, withLocalModel(baseConfig("/w"), gemma));
    expectValid(merged);
    expect(merged.gateway.port).toBe(19000);
    expect(merged.models.providers["local-llama"].models[0].id).toBe("gemma-4-e2b");
  });
});
