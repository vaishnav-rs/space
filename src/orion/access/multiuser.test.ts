import { createHmac, randomBytes } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { readPersonalConfig } from "../../personal/config.js";
import { scopedPersonalConfig } from "../../personal/index.js";
import { createOrionRuntime } from "../runtime.js";
import { createHewarFixture } from "../testing/hewar-fixture.js";
import { createOrionTaskTool } from "../tool.js";
import { getAccess } from "./context.js";

function team() {
  const dir = mkdtempSync(join(tmpdir(), "orion-team-"));
  const env = {
    ORION_STATE_DIR: dir,
    ORION_VAULT_KEY: randomBytes(32).toString("base64"),
  } as NodeJS.ProcessEnv;
  const access = getAccess(env);
  if (!access) throw new Error("no access context");
  access.directory.bootstrapOwner("owner");
  access.directory.setMember("owner", {
    profileId: "alice",
    role: "engineer",
    workspaces: ["hewar"],
    githubLogin: "alice-gh",
    addresses: ["alice@acme.test", "+15550000001"],
  });
  access.directory.setMember("owner", {
    profileId: "bob",
    role: "member",
    addresses: ["bob@acme.test"],
  });
  access.directory.setMember("owner", {
    profileId: "carol",
    role: "engineer",
    workspaces: ["hewar"],
    githubLogin: "carol-gh",
  });
  access.directory.setMember("owner", { profileId: "vic", role: "viewer", workspaces: ["hewar"] });
  return { env, access, dir };
}
const google = (n: string) => ({
  clientId: `id-${n}`,
  clientSecret: `s-${n}`,
  refreshToken: `rt-${n}`,
});

describe("personal tools resolve the requesting person's own accounts", () => {
  it("uses each person's Google, never another's, and enforces role and addresses", () => {
    const { env, access } = team();
    access.connectors.connect("alice", {
      connector: "google",
      scope: "user",
      fields: google("alice"),
    });
    access.connectors.connect("bob", { connector: "google", scope: "user", fields: google("bob") });
    const base = readPersonalConfig({});
    const as = (uid: string | undefined) =>
      scopedPersonalConfig(base, { env, getRequesterId: () => uid });
    expect(as("alice").google.refreshToken).toBe("rt-alice");
    expect(as("bob").google.refreshToken).toBe("rt-bob");
    expect(() => as("carol").google).toThrow(/isn't connected for you/);
    expect(() => as("vic").google).toThrow(/personal tools/); // viewer role
    expect(() => as(undefined).google).toThrow(/No signed-in person/);
    // The outbound guard's notion of "me" is the requester's own addresses, not the team's.
    expect(as("alice").ownerEmails).toEqual(["alice@acme.test", "+15550000001"]);
    expect(as("bob").ownerEmails).not.toContain("alice@acme.test");
  });

  it("falls back to environment credentials in single-user mode", () => {
    const env = {
      GOOGLE_CLIENT_ID: "i",
      GOOGLE_CLIENT_SECRET: "s",
      GOOGLE_REFRESH_TOKEN: "r",
    } as NodeJS.ProcessEnv;
    expect(scopedPersonalConfig(readPersonalConfig(env), { env }).google.refreshToken).toBe("r");
  });
});

describe("orion_task: private by default, shared explicitly, role-gated", () => {
  function tool(
    rt: ReturnType<typeof createOrionRuntime>,
    access: ReturnType<typeof getAccess>,
    uid: string,
  ) {
    return createOrionTaskTool({
      getRuntime: () => rt,
      ownerInitiated: () => true,
      getAccess: () => access,
      getRequesterId: () => uid,
    });
  }
  function boot() {
    const t = team();
    const fx = createHewarFixture();
    const wsDir = mkdtempSync(join(tmpdir(), "ws-"));
    writeFileSync(join(wsDir, "hewar.json"), JSON.stringify(fx.manifest));
    const rt = createOrionRuntime({
      stateDir: mkdtempSync(join(tmpdir(), "st-")),
      workspacesDir: wsDir,
      env: {},
      access: t.access,
    });
    return { ...t, rt };
  }
  const text = (r: unknown) => JSON.stringify(r);

  it("keeps chat-started tasks private until the owner shares them", async () => {
    const { rt, access } = boot();
    const made = JSON.parse(
      text(
        await tool(rt, access, "alice").execute("1", {
          action: "create",
          report: "Client says the Hewar upload is broken",
        }),
      ).includes("taskId")
        ? "{}"
        : "{}",
    );
    void made;
    const task = rt.store.list()[0];
    expect(task?.ownerProfileId).toBe("alice");
    const listFor = async (uid: string) =>
      text(await tool(rt, access, uid).execute("1", { action: "list" }));
    expect(await listFor("alice")).toContain("hewar");
    expect(await listFor("carol")).not.toContain("hewar"); // a teammate in the same workspace still cannot see it
    await expect(
      tool(rt, access, "carol").execute("1", { action: "status", task: task?.id ?? "" }),
    ).rejects.toThrow(/No task found/);
    await expect(
      tool(rt, access, "carol").execute("1", {
        action: "share",
        task: task?.id ?? "",
        with: "carol",
      }),
    ).rejects.toThrow(/No task found/);
    await tool(rt, access, "alice").execute("1", {
      action: "share",
      task: task?.id ?? "",
      with: "carol",
    });
    expect(await listFor("carol")).toContain("hewar");
    expect(await listFor("owner")).toContain("hewar"); // admins see all
    expect(await listFor("bob")).not.toContain("hewar");
    rt.close();
  });

  it("enforces roles and workspace membership on mutations", async () => {
    const { rt, access } = boot();
    await expect(
      tool(rt, access, "bob").execute("1", { action: "create", report: "Hewar upload broken" }),
    ).rejects.toThrow(/does not allow/); // member cannot start tasks
    await expect(
      tool(rt, access, "vic").execute("1", { action: "create", report: "Hewar upload broken" }),
    ).rejects.toThrow(/does not allow/);
    access.directory.setMember("owner", { profileId: "dan", role: "engineer", workspaces: [] });
    await expect(
      tool(rt, access, "dan").execute("1", { action: "create", report: "Hewar upload broken" }),
    ).rejects.toThrow(/do not have access to the hewar workspace/);
    await tool(rt, access, "alice").execute("1", {
      action: "create",
      report: "Hewar upload broken",
    });
    const id = rt.store.list()[0]?.id ?? "";
    await tool(rt, access, "alice").execute("1", { action: "share", task: id, with: "vic" });
    await expect(
      tool(rt, access, "vic").execute("1", { action: "stop", task: id }),
    ).rejects.toThrow(/does not allow/); // can view, cannot steer
    await expect(
      tool(rt, access, "alice").execute("1", {
        action: "approve",
        task: id,
        capability: "prod.restart",
      }),
    ).rejects.toThrow(/does not allow/); // engineers cannot approve
    await expect(
      tool(rt, access, "owner").execute("1", {
        action: "approve",
        task: id,
        capability: "prod.restart",
      }),
    ).resolves.toBeDefined();
    rt.close();
  });

  it("maps GitHub mentions to people: unknown logins rejected, team tasks visible to the workspace", () => {
    const { rt, access } = boot();
    const body = (login: string) =>
      JSON.stringify({
        action: "created",
        repository: { full_name: "acme/hewar" },
        sender: { login, type: "User" },
        issue: { number: 7, title: "Upload fails", html_url: "u" },
        comment: { id: 1, body: "@hewar-agent fix this" },
      });
    const signed = { SPACE: "" };
    void signed;
    const rtSecret = createOrionRuntime({
      stateDir: mkdtempSync(join(tmpdir(), "st2-")),
      workspacesDir: rt.registry.list().length
        ? (() => {
            const d = mkdtempSync(join(tmpdir(), "ws2-"));
            writeFileSync(join(d, "hewar.json"), JSON.stringify(rt.registry.get("hewar")));
            return d;
          })()
        : "",
      env: { ORION_GITHUB_WEBHOOK_SECRET: "k" } as NodeJS.ProcessEnv,
      access,
    });
    const send = (login: string, delivery: string) => {
      const b = body(login);
      return rtSecret.webhook(b, {
        "x-github-event": "issue_comment",
        "x-hub-signature-256": `sha256=${createHmac("sha256", "k").update(b).digest("hex")}`,
        "x-github-delivery": delivery,
      });
    };
    expect(send("stranger", "d1")).toMatchObject({ status: 202, outcome: { kind: "rejected" } });
    expect(send("bob-gh", "d2")).toMatchObject({ outcome: { kind: "rejected" } });
    const ok = send("alice-gh", "d3");
    expect(ok).toMatchObject({ status: 202, outcome: { kind: "task-created" } });
    const t = rtSecret.store.list()[0];
    expect(t?.ownerProfileId).toBe("alice");
    // GitHub-origin work is team work: carol (same workspace) sees it, bob (not in the workspace) does not.
    const visible = (uid: string) =>
      createOrionTaskTool({
        getRuntime: () => rtSecret,
        ownerInitiated: () => true,
        getAccess: () => access,
        getRequesterId: () => uid,
      }).execute("1", { action: "list" });
    return Promise.all([visible("carol"), visible("bob")]).then(([c, b]) => {
      expect(JSON.stringify(c)).toContain("#7");
      expect(JSON.stringify(b)).not.toContain("#7");
      rtSecret.close();
      rt.close();
    });
  });
});
