import type { DatabaseSync } from "node:sqlite";
import { AccessDeniedError, type Directory } from "./directory.js";
import { isAdminRole } from "./roles.js";
import { CredentialVault, type ConnectionMeta, type VaultScope } from "./vault.js";

export type ConnectorKind = "whatsapp" | "github" | "google" | "resend";

export type ConnectorDefinition = {
  kind: ConnectorKind;
  label: string;
  /** Scopes this connector can ever exist in. WhatsApp is organization-only by design. */
  scopes: readonly ("org" | "user")[];
  fields: readonly string[];
  /** Whether a person without their own connection may use the organization's by default. */
  defaultOrgFallback: boolean;
};

export const CONNECTORS: Record<ConnectorKind, ConnectorDefinition> = {
  whatsapp: {
    kind: "whatsapp",
    label: "WhatsApp",
    scopes: ["org"],
    // The gateway links WhatsApp through its own channel setup (one account per gateway); this only
    // records which number the organization owns. Organization-only by design.
    fields: ["account"],
    defaultOrgFallback: true,
  },
  github: {
    kind: "github",
    label: "GitHub",
    scopes: ["org", "user"],
    fields: ["token"],
    defaultOrgFallback: true,
  },
  // WhatsApp is linked through the gateway's own channel setup (one account per gateway); this entry only
  // records which number the organization owns. It is organization-only by design.
  // A mailbox and calendar belong to a person; they never fall back to someone else's.
  google: {
    kind: "google",
    label: "Google (Gmail, Calendar, Tasks)",
    scopes: ["org", "user"],
    fields: ["clientId", "clientSecret", "refreshToken"],
    defaultOrgFallback: false,
  },
  resend: {
    kind: "resend",
    label: "Resend",
    scopes: ["org", "user"],
    fields: ["apiKey", "from"],
    defaultOrgFallback: true,
  },
};

export type ConnectorPolicy = { userScope: boolean; orgFallback: boolean };

const SCHEMA = `CREATE TABLE IF NOT EXISTS orion_connector_policy (connector TEXT PRIMARY KEY, user_scope INTEGER NOT NULL, org_fallback INTEGER NOT NULL);`;

export type Resolution =
  | { ok: true; scope: "user" | "org"; fields: Record<string, string> }
  | { ok: false; reason: "not-connected" | "vault-unavailable" | "org-fallback-disabled" };

export class ConnectorService {
  constructor(
    private readonly db: DatabaseSync,
    readonly vault: CredentialVault,
    private readonly directory: Directory,
  ) {
    db.exec(SCHEMA);
  }

  policy(kind: ConnectorKind): ConnectorPolicy {
    const def = CONNECTORS[kind];
    const row = this.db
      .prepare("SELECT user_scope, org_fallback FROM orion_connector_policy WHERE connector=?")
      .get(kind) as { user_scope: number; org_fallback: number } | undefined;
    return {
      userScope: def.scopes.includes("user") && (row ? row.user_scope === 1 : true),
      orgFallback:
        def.scopes.includes("org") && (row ? row.org_fallback === 1 : def.defaultOrgFallback),
    };
  }

  setPolicy(
    actorId: string,
    kind: ConnectorKind,
    patch: Partial<ConnectorPolicy>,
  ): ConnectorPolicy {
    if (!this.directory.can(actorId, "connectors.org.manage"))
      throw new AccessDeniedError("only an admin can change connector policy");
    const next = { ...this.policy(kind), ...patch };
    const def = CONNECTORS[kind];
    if (!def.scopes.includes("user")) next.userScope = false; // org-only connectors can never be made personal
    this.db
      .prepare(
        "INSERT INTO orion_connector_policy(connector, user_scope, org_fallback) VALUES (?,?,?) ON CONFLICT(connector) DO UPDATE SET user_scope=excluded.user_scope, org_fallback=excluded.org_fallback",
      )
      .run(kind, next.userScope ? 1 : 0, next.orgFallback ? 1 : 0);
    this.vault.audit(actorId, "policy.set", undefined, kind, JSON.stringify(next));
    return this.policy(kind);
  }

  connect(
    actorId: string,
    input: { connector: ConnectorKind; scope: "org" | "user"; fields: Record<string, string> },
  ): void {
    const def = CONNECTORS[input.connector];
    if (!def.scopes.includes(input.scope))
      throw new AccessDeniedError(
        `${def.label} can only be connected at the ${def.scopes.join("/")} level`,
      );
    for (const f of def.fields) {
      if (!input.fields[f]?.trim()) throw new Error(`${def.label} needs ${f}`);
    }
    const fields = Object.fromEntries(
      def.fields.map((f) => [f, (input.fields[f] as string).trim()]),
    );
    let scope: VaultScope;
    if (input.scope === "org") {
      if (!this.directory.can(actorId, "connectors.org.manage"))
        throw new AccessDeniedError("only an admin can connect organization-wide connectors");
      scope = { kind: "org" };
    } else {
      if (!this.policy(input.connector).userScope)
        throw new AccessDeniedError(`personal ${def.label} connections are disabled by your admin`);
      if (!this.directory.can(actorId, "connectors.user.manage"))
        throw new AccessDeniedError("your role cannot connect personal accounts");
      // A personal credential is that person's own. Admins cannot connect on someone's behalf.
      scope = { kind: "user", userId: actorId };
    }
    this.vault.put(scope, input.connector, fields, actorId);
    this.vault.audit(actorId, "connect", scope, input.connector);
  }

  disconnect(
    actorId: string,
    input: { connector: ConnectorKind; scope: "org" | "user"; userId?: string },
  ): boolean {
    let scope: VaultScope;
    if (input.scope === "org") {
      if (!this.directory.can(actorId, "connectors.org.manage"))
        throw new AccessDeniedError("only an admin can disconnect organization-wide connectors");
      scope = { kind: "org" };
    } else {
      const target = input.userId ?? actorId;
      // People remove their own; admins may revoke anyone's (e.g. someone leaves the team).
      if (target !== actorId && !isAdminRole(this.directory.roleOf(actorId)))
        throw new AccessDeniedError("you can only disconnect your own accounts");
      scope = { kind: "user", userId: target };
    }
    const removed = this.vault.delete(scope, input.connector);
    if (removed) this.vault.audit(actorId, "disconnect", scope, input.connector);
    return removed;
  }

  /** Admins see every connection (metadata only). Everyone else sees their own plus whether org ones exist. */
  list(actorId: string): {
    mine: ConnectionMeta[];
    org: ConnectionMeta[];
    everyone?: ConnectionMeta[];
  } {
    const org = this.vault.list({ org: true }).map((m) => ({
      ...m,
      createdBy: this.directory.can(actorId, "connectors.audit") ? m.createdBy : "",
    }));
    const mine = this.vault.list({ userId: actorId });
    return this.directory.can(actorId, "connectors.audit")
      ? { mine, org, everyone: this.vault.list() }
      : { mine, org };
  }

  /** Credential resolution for a request made by `userId`. Own connection first, org only if policy allows. */
  resolve(connector: ConnectorKind, userId: string | undefined): Resolution {
    if (!this.vault.available) return { ok: false, reason: "vault-unavailable" };
    const def = CONNECTORS[connector];
    if (userId && def.scopes.includes("user") && this.policy(connector).userScope) {
      const own = this.vault.get({ kind: "user", userId }, connector);
      if (own) return { ok: true, scope: "user", fields: own };
    }
    if (!def.scopes.includes("org")) return { ok: false, reason: "not-connected" };
    const org = this.vault.get({ kind: "org" }, connector);
    if (!org) return { ok: false, reason: "not-connected" };
    // Org-only connectors (WhatsApp) are the org's by definition; others need the fallback policy.
    if (def.scopes.length > 1 && !this.policy(connector).orgFallback)
      return { ok: false, reason: "org-fallback-disabled" };
    return { ok: true, scope: "org", fields: org };
  }
}
