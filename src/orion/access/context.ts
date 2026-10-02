import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { ConnectorService } from "./connectors.js";
import { Directory } from "./directory.js";
import { isRole, type Role } from "./roles.js";
import { CredentialVault, parseVaultKey } from "./vault.js";

export type AccessContext = {
  directory: Directory;
  connectors: ConnectorService;
  vault: CredentialVault;
  close(): void;
};

let cached: { dir: string; ctx: AccessContext } | undefined;

export function createAccessContext(opts: {
  stateDir: string;
  vaultKey?: string;
  defaultRole?: Role;
  now?: () => string;
}): AccessContext {
  mkdirSync(opts.stateDir, { recursive: true });
  const db = new DatabaseSync(join(opts.stateDir, "orion-access.sqlite"));
  const now = opts.now ?? (() => new Date().toISOString());
  const vault = new CredentialVault(db, parseVaultKey(opts.vaultKey), now);
  const directory = new Directory(db, now, opts.defaultRole ?? "viewer");
  return {
    directory,
    vault,
    connectors: new ConnectorService(db, vault, directory),
    close: () => db.close(),
  };
}

/**
 * Multi-user access control, active only when ORION_STATE_DIR is set. Without it Orion runs in
 * single-user mode and uses the environment-provided credentials.
 */
export function getAccess(env: NodeJS.ProcessEnv = process.env): AccessContext | undefined {
  const dir = env.ORION_STATE_DIR?.trim();
  if (!dir) return undefined;
  if (cached?.dir === dir) return cached.ctx;
  const role = env.ORION_DEFAULT_ROLE?.trim();
  const ctx = createAccessContext({
    stateDir: dir,
    vaultKey: env.ORION_VAULT_KEY,
    ...(role && isRole(role) ? { defaultRole: role } : {}),
  });
  cached = { dir, ctx };
  return ctx;
}
