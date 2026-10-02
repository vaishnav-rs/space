import type { DatabaseSync } from "node:sqlite";
import { canAssignRole, can, isAdminRole, type Permission, type Role } from "./roles.js";

const SCHEMA = `
CREATE TABLE IF NOT EXISTS orion_members (
  profile_id TEXT PRIMARY KEY,
  role TEXT NOT NULL,
  workspaces TEXT,
  github_login TEXT,
  addresses TEXT,
  updated_at TEXT NOT NULL
);
`;

export type Member = {
  profileId: string;
  role: Role;
  workspaces: string[];
  githubLogin?: string;
  /** The person's own emails and phone numbers; the outbound guard lets Orion contact only these on its own. */ addresses: string[];
};

export class AccessDeniedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AccessDeniedError";
  }
}

/**
 * Who is who. Role assignment is deliberately narrow: only an owner can mint admins, admins cannot
 * touch owners or each other, and the first owner is created once through an explicit bootstrap.
 */
export class Directory {
  constructor(
    private readonly db: DatabaseSync,
    private readonly now: () => string,
    /** Role for people the directory has never seen. `viewer` keeps new people read-only until assigned. */
    private readonly defaultRole: Role = "viewer",
  ) {
    db.exec(SCHEMA);
  }

  get(profileId: string): Member | undefined {
    const r = this.db
      .prepare(
        "SELECT profile_id, role, workspaces, github_login, addresses FROM orion_members WHERE profile_id=?",
      )
      .get(profileId) as
      | {
          profile_id: string;
          role: Role;
          workspaces: string | null;
          github_login: string | null;
          addresses: string | null;
        }
      | undefined;
    return r
      ? {
          profileId: r.profile_id,
          role: r.role,
          workspaces: r.workspaces ? (JSON.parse(r.workspaces) as string[]) : [],
          addresses: r.addresses ? (JSON.parse(r.addresses) as string[]) : [],
          ...(r.github_login ? { githubLogin: r.github_login } : {}),
        }
      : undefined;
  }

  roleOf(profileId: string): Role {
    return this.get(profileId)?.role ?? this.defaultRole;
  }

  list(): Member[] {
    return (
      this.db.prepare("SELECT profile_id FROM orion_members ORDER BY profile_id").all() as {
        profile_id: string;
      }[]
    ).map((r) => this.get(r.profile_id) as Member);
  }

  hasOwner(): boolean {
    return (
      this.db.prepare("SELECT 1 FROM orion_members WHERE role='owner' LIMIT 1").get() !== undefined
    );
  }

  /** One-time creation of the first owner. Refuses once any owner exists. */
  bootstrapOwner(profileId: string): Member {
    if (this.hasOwner()) throw new AccessDeniedError("an owner already exists");
    this.write(profileId, "owner", [], undefined, []);
    return this.get(profileId) as Member;
  }

  setMember(
    actorId: string,
    target: {
      profileId: string;
      role: Role;
      workspaces?: string[];
      githubLogin?: string;
      addresses?: string[];
    },
  ): Member {
    const actorRole = this.roleOf(actorId);
    if (!can(actorRole, "members.manage")) throw new AccessDeniedError("you cannot manage members");
    const current = this.get(target.profileId);
    if (current && !canAssignRole(actorRole, current.role))
      throw new AccessDeniedError(`you cannot change a ${current.role}`);
    if (!canAssignRole(actorRole, target.role))
      throw new AccessDeniedError(`you cannot assign the ${target.role} role`);
    if (
      current?.role === "owner" &&
      target.role !== "owner" &&
      this.db.prepare("SELECT COUNT(*) AS n FROM orion_members WHERE role='owner'").get()?.n === 1
    ) {
      throw new AccessDeniedError("cannot demote the last owner");
    }
    this.write(
      target.profileId,
      target.role,
      target.workspaces ?? current?.workspaces ?? [],
      target.githubLogin ?? current?.githubLogin,
      target.addresses ?? current?.addresses ?? [],
    );
    return this.get(target.profileId) as Member;
  }

  private write(
    profileId: string,
    role: Role,
    workspaces: string[],
    githubLogin: string | undefined,
    addresses: string[],
  ): void {
    this.db
      .prepare(
        `INSERT INTO orion_members(profile_id, role, workspaces, github_login, addresses, updated_at) VALUES (?,?,?,?,?,?)
         ON CONFLICT(profile_id) DO UPDATE SET role=excluded.role, workspaces=excluded.workspaces, github_login=excluded.github_login, addresses=excluded.addresses, updated_at=excluded.updated_at`,
      )
      .run(
        profileId,
        role,
        JSON.stringify(workspaces),
        githubLogin ?? null,
        JSON.stringify(addresses),
        this.now(),
      );
  }

  can(profileId: string, permission: Permission): boolean {
    return can(this.roleOf(profileId), permission);
  }

  /** Owners and admins reach every workspace; others only those they were added to. */
  workspaceAllowed(profileId: string, workspaceId: string): boolean {
    const m = this.get(profileId);
    const role = m?.role ?? this.defaultRole;
    return isAdminRole(role) || (m?.workspaces.includes(workspaceId) ?? false);
  }

  findByGithubLogin(login: string): Member | undefined {
    const r = this.db
      .prepare("SELECT profile_id FROM orion_members WHERE lower(github_login)=lower(?)")
      .get(login) as { profile_id: string } | undefined;
    return r ? this.get(r.profile_id) : undefined;
  }
}
