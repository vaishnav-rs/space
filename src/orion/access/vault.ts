import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";

/**
 * Encrypted credential storage. Each entry is AES-256-GCM sealed and bound to its slot
 * (scope + owner + connector) so a stolen ciphertext cannot be replayed under another user.
 * Without a configured key the vault refuses to store anything.
 */
export type VaultScope = { kind: "org" } | { kind: "user"; userId: string };

export class VaultUnavailableError extends Error {
  constructor() {
    super(
      "Credential vault is not configured: set ORION_VAULT_KEY to a base64-encoded 32-byte key.",
    );
    this.name = "VaultUnavailableError";
  }
}

export function parseVaultKey(b64: string | undefined): Buffer | undefined {
  if (!b64) return undefined;
  const key = Buffer.from(b64, "base64");
  return key.length === 32 ? key : undefined;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS orion_credentials (
  scope_kind TEXT NOT NULL,
  scope_id TEXT NOT NULL,
  connector TEXT NOT NULL,
  sealed TEXT NOT NULL,
  created_by TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (scope_kind, scope_id, connector)
);
CREATE TABLE IF NOT EXISTS orion_audit (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  at TEXT NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  scope_kind TEXT,
  scope_id TEXT,
  connector TEXT,
  detail TEXT
);
`;

const slot = (scope: VaultScope, connector: string) =>
  `${scope.kind}:${scope.kind === "user" ? scope.userId : ""}:${connector}`;
const scopeId = (scope: VaultScope) => (scope.kind === "user" ? scope.userId : "");

export type ConnectionMeta = {
  scope: VaultScope;
  connector: string;
  createdBy: string;
  updatedAt: string;
};

export class CredentialVault {
  constructor(
    private readonly db: DatabaseSync,
    private readonly key: Buffer | undefined,
    private readonly now: () => string,
  ) {
    db.exec(SCHEMA);
  }

  get available(): boolean {
    return this.key !== undefined;
  }

  private seal(scope: VaultScope, connector: string, fields: Record<string, string>): string {
    if (!this.key) throw new VaultUnavailableError();
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.key, iv);
    cipher.setAAD(Buffer.from(slot(scope, connector)));
    const body = Buffer.concat([cipher.update(JSON.stringify(fields), "utf8"), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64");
  }

  private open(scope: VaultScope, connector: string, sealed: string): Record<string, string> {
    if (!this.key) throw new VaultUnavailableError();
    const raw = Buffer.from(sealed, "base64");
    const decipher = createDecipheriv("aes-256-gcm", this.key, raw.subarray(0, 12));
    decipher.setAAD(Buffer.from(slot(scope, connector)));
    decipher.setAuthTag(raw.subarray(12, 28));
    return JSON.parse(
      Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString("utf8"),
    ) as Record<string, string>;
  }

  put(scope: VaultScope, connector: string, fields: Record<string, string>, by: string): void {
    const sealed = this.seal(scope, connector, fields);
    this.db
      .prepare(
        `INSERT INTO orion_credentials(scope_kind, scope_id, connector, sealed, created_by, updated_at) VALUES (?,?,?,?,?,?)
         ON CONFLICT(scope_kind, scope_id, connector) DO UPDATE SET sealed=excluded.sealed, updated_at=excluded.updated_at`,
      )
      .run(scope.kind, scopeId(scope), connector, sealed, by, this.now());
  }

  get(scope: VaultScope, connector: string): Record<string, string> | undefined {
    const row = this.db
      .prepare(
        "SELECT sealed FROM orion_credentials WHERE scope_kind=? AND scope_id=? AND connector=?",
      )
      .get(scope.kind, scopeId(scope), connector) as { sealed: string } | undefined;
    return row ? this.open(scope, connector, row.sealed) : undefined;
  }

  delete(scope: VaultScope, connector: string): boolean {
    return (
      this.db
        .prepare("DELETE FROM orion_credentials WHERE scope_kind=? AND scope_id=? AND connector=?")
        .run(scope.kind, scopeId(scope), connector).changes > 0
    );
  }

  /** Metadata only. Secrets never leave through listing. */
  list(filter?: { userId?: string; org?: boolean }): ConnectionMeta[] {
    const rows = this.db
      .prepare(
        "SELECT scope_kind, scope_id, connector, created_by, updated_at FROM orion_credentials ORDER BY connector, scope_id",
      )
      .all() as {
      scope_kind: string;
      scope_id: string;
      connector: string;
      created_by: string;
      updated_at: string;
    }[];
    return rows
      .filter((r) =>
        !filter
          ? true
          : (filter.org && r.scope_kind === "org") ||
            (filter.userId !== undefined &&
              r.scope_kind === "user" &&
              r.scope_id === filter.userId),
      )
      .map((r) => ({
        scope:
          r.scope_kind === "org"
            ? ({ kind: "org" } as const)
            : ({ kind: "user", userId: r.scope_id } as const),
        connector: r.connector,
        createdBy: r.created_by,
        updatedAt: r.updated_at,
      }));
  }

  audit(
    actor: string,
    action: string,
    scope?: VaultScope,
    connector?: string,
    detail?: string,
  ): void {
    this.db
      .prepare(
        "INSERT INTO orion_audit(at, actor, action, scope_kind, scope_id, connector, detail) VALUES (?,?,?,?,?,?,?)",
      )
      .run(
        this.now(),
        actor,
        action,
        scope?.kind ?? null,
        scope ? scopeId(scope) : null,
        connector ?? null,
        detail ?? null,
      );
  }

  auditLog(): {
    at: string;
    actor: string;
    action: string;
    connector: string | null;
    scope: string | null;
  }[] {
    return this.db
      .prepare(
        "SELECT at, actor, action, connector, scope_kind || ':' || COALESCE(scope_id,'') AS scope FROM orion_audit ORDER BY seq",
      )
      .all() as {
      at: string;
      actor: string;
      action: string;
      connector: string | null;
      scope: string | null;
    }[];
  }
}
