import { randomBytes } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import { ConnectorService } from "./connectors.js";
import { AccessDeniedError, Directory } from "./directory.js";
import { CredentialVault, parseVaultKey, VaultUnavailableError } from "./vault.js";

function setup(withKey = true) {
  const db = new DatabaseSync(":memory:");
  const now = () => "2026-03-01T00:00:00Z";
  const key = withKey ? parseVaultKey(randomBytes(32).toString("base64")) : undefined;
  const vault = new CredentialVault(db, key, now);
  const directory = new Directory(db, now, "viewer");
  const connectors = new ConnectorService(db, vault, directory);
  directory.bootstrapOwner("owner");
  directory.setMember("owner", { profileId: "admin1", role: "admin" });
  directory.setMember("owner", { profileId: "alice", role: "engineer", workspaces: ["hewar"] });
  directory.setMember("owner", { profileId: "bob", role: "member" });
  return { db, vault, directory, connectors };
}

const google = { clientId: "id", clientSecret: "secret", refreshToken: "rt" };

describe("roles and directory", () => {
  it("bootstraps one owner and keeps role assignment narrow", () => {
    const { directory } = setup();
    expect(() => directory.bootstrapOwner("mallory")).toThrow(AccessDeniedError);
    expect(() => directory.setMember("alice", { profileId: "bob", role: "admin" })).toThrow(
      AccessDeniedError,
    ); // engineers manage nobody
    expect(() => directory.setMember("admin1", { profileId: "bob", role: "admin" })).toThrow(
      AccessDeniedError,
    ); // admins cannot mint admins
    expect(() => directory.setMember("admin1", { profileId: "owner", role: "member" })).toThrow(
      AccessDeniedError,
    );
    expect(() => directory.setMember("owner", { profileId: "owner", role: "member" })).toThrow(
      /last owner/,
    );
    expect(
      directory.setMember("admin1", { profileId: "bob", role: "engineer", workspaces: ["hewar"] })
        .role,
    ).toBe("engineer");
  });

  it("gives strangers the safest role and scopes workspaces per person", () => {
    const { directory } = setup();
    expect(directory.roleOf("stranger")).toBe("viewer");
    expect(directory.can("stranger", "tools.personal")).toBe(false);
    expect(directory.can("stranger", "tasks.create")).toBe(false);
    expect(directory.workspaceAllowed("alice", "hewar")).toBe(true);
    expect(directory.workspaceAllowed("alice", "other")).toBe(false);
    expect(directory.workspaceAllowed("admin1", "other")).toBe(true);
    expect(directory.workspaceAllowed("bob", "hewar")).toBe(false);
  });

  it("maps GitHub logins to people", () => {
    const { directory } = setup();
    directory.setMember("owner", { profileId: "alice", role: "engineer", githubLogin: "alice-gh" });
    expect(directory.findByGithubLogin("ALICE-GH")?.profileId).toBe("alice");
  });
});

describe("connectors: org vs personal scope", () => {
  it("keeps WhatsApp organization-only and admin-managed", () => {
    const { connectors } = setup();
    expect(() =>
      connectors.connect("alice", {
        connector: "whatsapp",
        scope: "user",
        fields: { account: "x" },
      }),
    ).toThrow(/organization|org/);
    expect(() =>
      connectors.connect("alice", {
        connector: "whatsapp",
        scope: "org",
        fields: { account: "x" },
      }),
    ).toThrow(/only an admin/);
    expect(() => connectors.setPolicy("admin1", "whatsapp", { userScope: true })).not.toThrow();
    expect(connectors.policy("whatsapp").userScope).toBe(false); // cannot be made personal even by an admin
    connectors.connect("admin1", {
      connector: "whatsapp",
      scope: "org",
      fields: { account: "+15550009999" },
    });
    // Everyone resolves to the org's connection.
    expect(connectors.resolve("whatsapp", "bob")).toMatchObject({ ok: true, scope: "org" });
  });

  it("lets one person connect Google exclusively, with no fallback to anyone else's", () => {
    const { connectors } = setup();
    connectors.connect("alice", { connector: "google", scope: "user", fields: google });
    expect(connectors.resolve("google", "alice")).toMatchObject({ ok: true, scope: "user" });
    // Bob has nothing, and Alice's mailbox is never used for him.
    expect(connectors.resolve("google", "bob")).toEqual({ ok: false, reason: "not-connected" });
    // Even an org-level Google connection does not leak to people without their own unless the admin allows it.
    connectors.connect("admin1", { connector: "google", scope: "org", fields: google });
    expect(connectors.resolve("google", "bob")).toEqual({
      ok: false,
      reason: "org-fallback-disabled",
    });
    connectors.setPolicy("admin1", "google", { orgFallback: true });
    expect(connectors.resolve("google", "bob")).toMatchObject({ ok: true, scope: "org" });
    // Personal still wins over org.
    expect(connectors.resolve("google", "alice")).toMatchObject({ scope: "user" });
  });

  it("cannot connect on someone else's behalf, but admins can revoke", () => {
    const { connectors } = setup();
    connectors.connect("alice", { connector: "github", scope: "user", fields: { token: "ghp_x" } });
    expect(() =>
      connectors.disconnect("bob", { connector: "github", scope: "user", userId: "alice" }),
    ).toThrow(AccessDeniedError);
    expect(
      connectors.disconnect("admin1", { connector: "github", scope: "user", userId: "alice" }),
    ).toBe(true);
    expect(connectors.resolve("github", "alice")).toEqual({ ok: false, reason: "not-connected" });
  });

  it("can disable personal connections for a connector, and viewers cannot connect", () => {
    const { connectors } = setup();
    connectors.setPolicy("admin1", "resend", { userScope: false });
    expect(() =>
      connectors.connect("bob", {
        connector: "resend",
        scope: "user",
        fields: { apiKey: "k", from: "a@b.c" },
      }),
    ).toThrow(/disabled by your admin/);
    expect(() =>
      connectors.connect("stranger", {
        connector: "github",
        scope: "user",
        fields: { token: "t" },
      }),
    ).toThrow(/cannot connect/);
    expect(() => connectors.setPolicy("bob", "github", { orgFallback: false })).toThrow(
      AccessDeniedError,
    );
  });

  it("lists metadata by role and never exposes secrets", () => {
    const { connectors, vault } = setup();
    connectors.connect("alice", { connector: "google", scope: "user", fields: google });
    connectors.connect("admin1", {
      connector: "github",
      scope: "org",
      fields: { token: "ghp_org" },
    });
    const aliceView = connectors.list("alice");
    expect(aliceView.everyone).toBeUndefined();
    expect(aliceView.mine.map((m) => m.connector)).toEqual(["google"]);
    expect(aliceView.org[0]?.createdBy).toBe(""); // who set the org connector is admin-only
    const adminView = connectors.list("admin1");
    expect(adminView.everyone?.map((m) => m.connector).sort()).toEqual(["github", "google"]);
    expect(JSON.stringify(adminView)).not.toMatch(/ghp_org|refreshToken|"rt"/);
    expect(vault.auditLog().map((a) => a.action)).toEqual(expect.arrayContaining(["connect"]));
  });
});

describe("vault encryption", () => {
  it("seals credentials at rest and binds them to their slot", () => {
    const { db, connectors } = setup();
    connectors.connect("alice", { connector: "google", scope: "user", fields: google });
    const raw = db.prepare("SELECT sealed FROM orion_credentials").get() as { sealed: string };
    expect(raw.sealed).not.toContain("secret");
    expect(Buffer.from(raw.sealed, "base64").toString("utf8")).not.toContain("refreshToken");
    // Replaying Alice's ciphertext under Bob's slot fails authentication.
    db.prepare("INSERT INTO orion_credentials VALUES ('user','bob','google',?, 'x','t')").run(
      raw.sealed,
    );
    expect(() => connectors.resolve("google", "bob")).toThrow();
  });

  it("refuses to store anything without a key", () => {
    const { connectors } = setup(false);
    expect(() =>
      connectors.connect("alice", { connector: "github", scope: "user", fields: { token: "t" } }),
    ).toThrow(VaultUnavailableError);
    expect(connectors.resolve("github", "alice")).toEqual({
      ok: false,
      reason: "vault-unavailable",
    });
  });
});
