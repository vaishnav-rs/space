import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parseEnvFile, provisionOrion } from "./setup.js";

const repoRoot = join(import.meta.dirname, "../..");

describe("provisionOrion", () => {
  it("provisions once and keeps secrets and edits on re-run", () => {
    const home = mkdtempSync(join(tmpdir(), "orion-setup-"));
    const input = { home, repoRoot, owner: "me", ownerWhatsapp: "+15550001111" };
    const first = provisionOrion(input);
    const env1 = parseEnvFile(readFileSync(first.envFile, "utf8"));
    expect(Buffer.from(env1.get("ORION_VAULT_KEY") ?? "", "base64")).toHaveLength(32);
    expect(statSync(first.envFile).mode & 0o077).toBe(0);

    const second = provisionOrion(input);
    const env2 = parseEnvFile(readFileSync(second.envFile, "utf8"));
    expect(env2.get("ORION_VAULT_KEY")).toBe(env1.get("ORION_VAULT_KEY"));
    expect(env2.get("OPENCLAW_GATEWAY_TOKEN")).toBe(env1.get("OPENCLAW_GATEWAY_TOKEN"));
    expect(second.created).toEqual([]);
    expect(second.kept).toContain("owner");
  });
});
