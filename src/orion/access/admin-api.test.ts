import { randomBytes } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { createOrionRuntime } from "../runtime.js";
import { createHewarFixture } from "../testing/hewar-fixture.js";
import { adminOverview, applyAdminOp } from "./admin-api.js";
import { createAccessContext } from "./context.js";

const health = { agentBound: false, githubToken: false, webhookSecret: false, vaultKey: true };

function boot() {
  const access = createAccessContext({
    stateDir: mkdtempSync(join(tmpdir(), "adm-")),
    vaultKey: randomBytes(32).toString("base64"),
  });
  const fx = createHewarFixture();
  const wsDir = mkdtempSync(join(tmpdir(), "ws-"));
  writeFileSync(join(wsDir, "hewar.json"), JSON.stringify(fx.manifest));
  const rt = createOrionRuntime({
    stateDir: mkdtempSync(join(tmpdir(), "st-")),
    workspacesDir: wsDir,
    env: {},
    access,
  });
  return { access, rt };
}

describe("admin API", () => {
  it("bootstraps the first owner exactly once", () => {
    const { access, rt } = boot();
    expect(adminOverview(access, "you", rt, health).needsBootstrap).toBe(true);
    applyAdminOp(access, "you", { op: "bootstrap" }, rt);
    expect(adminOverview(access, "you", rt, health)).toMatchObject({
      needsBootstrap: false,
      me: { role: "owner" },
    });
    expect(() => applyAdminOp(access, "mallory", { op: "bootstrap" }, rt)).toThrow(
      /already exists/,
    );
  });

  it("shows admins everything and everyone else only their own", () => {
    const { access, rt } = boot();
    applyAdminOp(access, "you", { op: "bootstrap" }, rt);
    applyAdminOp(
      access,
      "you",
      {
        op: "member.set",
        profileId: "alice",
        role: "engineer",
        workspaces: ["hewar"],
        githubLogin: "alice-gh",
      },
      rt,
    );
    applyAdminOp(access, "you", { op: "member.set", profileId: "bob", role: "member" }, rt);
    applyAdminOp(
      access,
      "alice",
      {
        op: "connector.connect",
        connector: "google",
        scope: "user",
        fields: { clientId: "i", clientSecret: "SECRET-VALUE", refreshToken: "rt" },
      },
      rt,
    );
    applyAdminOp(
      access,
      "you",
      {
        op: "connector.connect",
        connector: "whatsapp",
        scope: "org",
        fields: { account: "+1555" },
      },
      rt,
    );

    const admin = adminOverview(access, "you", rt, health);
    expect(admin.members.map((m) => m.profileId).sort()).toEqual(["alice", "bob", "you"]);
    expect(admin.connectors.find((c) => c.kind === "google")?.people).toEqual([
      expect.objectContaining({ userId: "alice" }),
    ]);
    expect(admin.workspaces[0]).toMatchObject({ id: "hewar", production: true });
    expect(admin.audit?.length).toBeGreaterThan(0);
    expect(JSON.stringify(admin)).not.toContain("SECRET-VALUE"); // credentials never leave the vault

    const bob = adminOverview(access, "bob", rt, health);
    expect(bob.members.map((m) => m.profileId)).toEqual(["bob"]);
    expect(bob.workspaces).toEqual([]);
    expect(bob.audit).toBeUndefined();
    expect(bob.connectors.find((c) => c.kind === "google")).toMatchObject({
      mine: { connected: false },
    });
    expect(bob.connectors.find((c) => c.kind === "google")?.people).toBeUndefined();
    expect(bob.connectors.find((c) => c.kind === "whatsapp")?.org).toMatchObject({
      connected: true,
    }); // exists, but not who set it
    expect(bob.connectors.find((c) => c.kind === "whatsapp")?.org.by).toBeUndefined();
  });

  it("applies role checks to every mutation and validates input", () => {
    const { access, rt } = boot();
    applyAdminOp(access, "you", { op: "bootstrap" }, rt);
    applyAdminOp(access, "you", { op: "member.set", profileId: "bob", role: "member" }, rt);
    expect(() =>
      applyAdminOp(access, "bob", { op: "member.set", profileId: "bob", role: "admin" }, rt),
    ).toThrow(/cannot manage/);
    expect(() =>
      applyAdminOp(
        access,
        "bob",
        { op: "connector.connect", connector: "github", scope: "org", fields: { token: "t" } },
        rt,
      ),
    ).toThrow(/only an admin/);
    expect(() =>
      applyAdminOp(
        access,
        "bob",
        { op: "connector.policy", connector: "google", userScope: false },
        rt,
      ),
    ).toThrow(/only an admin/);
    expect(() =>
      applyAdminOp(
        access,
        "you",
        { op: "connector.connect", connector: "whatsapp", scope: "user", fields: { account: "x" } },
        rt,
      ),
    ).toThrow(/org/);
    expect(() => applyAdminOp(access, "you", { op: "nonsense" }, rt)).toThrow();
    expect(() =>
      applyAdminOp(access, "you", { op: "member.remove", profileId: "you" }, rt),
    ).toThrow(/last owner/);
    expect(applyAdminOp(access, "you", { op: "member.remove", profileId: "bob" }, rt)).toEqual({
      ok: true,
    });
  });

  it("lets people act on tasks they can see, within role", () => {
    const { access, rt } = boot();
    applyAdminOp(access, "you", { op: "bootstrap" }, rt);
    applyAdminOp(
      access,
      "you",
      { op: "member.set", profileId: "alice", role: "engineer", workspaces: ["hewar"] },
      rt,
    );
    applyAdminOp(
      access,
      "you",
      { op: "member.set", profileId: "carol", role: "engineer", workspaces: ["hewar"] },
      rt,
    );
    const t = rt.tasks.create({
      workspaceId: "hewar",
      source: { kind: "chat", channel: "assistant" },
      reporter: {},
      report: "Hewar upload broken",
      ownerProfileId: "alice",
    });
    expect(() => applyAdminOp(access, "carol", { op: "task.stop", taskId: t.id }, rt)).toThrow(
      /No such task/,
    ); // private
    applyAdminOp(access, "alice", { op: "task.share", taskId: t.id, with: "carol" }, rt);
    expect(adminOverview(access, "carol", rt, health).tasks).toHaveLength(1);
    expect(() =>
      applyAdminOp(
        access,
        "alice",
        { op: "task.approve", taskId: t.id, capability: "prod.restart" },
        rt,
      ),
    ).toThrow(/does not allow/);
    applyAdminOp(access, "carol", { op: "task.stop", taskId: t.id }, rt);
    expect(rt.tasks.get(t.id).status).toBe("BLOCKED");
    applyAdminOp(
      access,
      "you",
      { op: "task.approve", taskId: t.id, capability: "prod.restart" },
      rt,
    );
  });
});
