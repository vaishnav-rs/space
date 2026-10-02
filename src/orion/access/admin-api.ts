import { z } from "zod";
import type { OrionRuntime } from "../runtime.js";
import { describeTask } from "../status.js";
import { BUG_FIX_POLICY } from "../task-machine.js";
import type { MaintenanceTask } from "../task-types.js";
import { CONNECTORS, type ConnectorKind } from "./connectors.js";
import type { AccessContext } from "./context.js";
import { AccessDeniedError } from "./directory.js";
import { PERMISSIONS, ROLES, can, isAdminRole, type Permission, type Role } from "./roles.js";
import { canShareTask, canViewTask } from "./visibility.js";

/**
 * Backend of the Orion admin screen. Every call is made on behalf of one person and is authorized
 * here by Orion role, so the screen is the same page for everyone: admins see and change
 * everything, other people see and manage only their own connections and tasks.
 */
export type AdminHealth = {
  agentBound: boolean;
  githubToken: boolean;
  webhookSecret: boolean;
  vaultKey: boolean;
};

export type AdminOverview = {
  configured: true;
  needsBootstrap: boolean;
  vaultAvailable: boolean;
  me: { profileId: string; role: Role; permissions: Permission[] };
  roles: { role: Role; permissions: Permission[] }[];
  members: {
    profileId: string;
    role: Role;
    workspaces: string[];
    githubLogin?: string;
    addresses: string[];
  }[];
  connectors: {
    kind: ConnectorKind;
    label: string;
    scopes: ("org" | "user")[];
    fields: string[];
    policy: { userScope: boolean; orgFallback: boolean };
    org: { connected: boolean; by?: string; updatedAt?: string };
    mine: { connected: boolean; updatedAt?: string };
    people?: { userId: string; updatedAt: string }[];
  }[];
  tasks: {
    id: string;
    workspace: string;
    status: string;
    source: string;
    title: string;
    owner?: string;
    sharedWith: string[];
    updatedAt: string;
    pullRequest?: string;
    summary: string;
    canShare: boolean;
  }[];
  workspaces: {
    id: string;
    name: string;
    kind: string;
    repo?: string;
    grants: string[];
    requireApproval: string[];
    production: boolean;
    members: string[];
  }[];
  health: AdminHealth;
  audit?: {
    at: string;
    actor: string;
    action: string;
    connector: string | null;
    scope: string | null;
  }[];
};

export type AdminNotConfigured = { configured: false; reason: string };

const connector = z.enum(Object.keys(CONNECTORS) as [ConnectorKind, ...ConnectorKind[]]);
const role = z.enum(ROLES);

export const adminOpSchema = z.discriminatedUnion("op", [
  z.object({ op: z.literal("bootstrap") }),
  z.object({
    op: z.literal("member.set"),
    profileId: z.string().min(1).max(128),
    role,
    workspaces: z.array(z.string().max(64)).max(64).optional(),
    githubLogin: z.string().max(64).optional(),
    addresses: z.array(z.string().max(200)).max(20).optional(),
  }),
  z.object({ op: z.literal("member.remove"), profileId: z.string().min(1).max(128) }),
  z.object({
    op: z.literal("connector.connect"),
    connector,
    scope: z.enum(["org", "user"]),
    fields: z.record(z.string(), z.string().max(8192)),
  }),
  z.object({
    op: z.literal("connector.disconnect"),
    connector,
    scope: z.enum(["org", "user"]),
    userId: z.string().max(128).optional(),
  }),
  z.object({
    op: z.literal("connector.policy"),
    connector,
    userScope: z.boolean().optional(),
    orgFallback: z.boolean().optional(),
  }),
  z.object({ op: z.literal("task.stop"), taskId: z.string().min(1) }),
  z.object({ op: z.literal("task.retry"), taskId: z.string().min(1) }),
  z.object({
    op: z.literal("task.approve"),
    taskId: z.string().min(1),
    capability: z.string().min(1).max(64),
  }),
  z.object({
    op: z.literal("task.share"),
    taskId: z.string().min(1),
    with: z.string().min(1).max(128),
  }),
]);
export type AdminOp = z.infer<typeof adminOpSchema>;

const permsOf = (r: Role): Permission[] => PERMISSIONS.filter((p) => can(r, p));

function taskRow(t: MaintenanceTask, canShare: boolean): AdminOverview["tasks"][number] {
  return {
    id: t.id,
    workspace: t.workspaceId,
    status: t.status,
    source:
      t.source.kind === "github-issue"
        ? `GitHub #${t.source.issueNumber}`
        : t.source.kind === "chat"
          ? "Chat"
          : t.source.kind,
    title: t.report.split("\n")[0]?.slice(0, 140) ?? "",
    ...(t.ownerProfileId ? { owner: t.ownerProfileId } : {}),
    sharedWith: t.sharedWith,
    updatedAt: t.updatedAt,
    ...(t.pullRequest ? { pullRequest: t.pullRequest.url } : {}),
    summary: describeTask(t, BUG_FIX_POLICY),
    canShare,
  };
}

export function adminOverview(
  ctx: AccessContext,
  actorId: string,
  runtime: OrionRuntime | undefined,
  health: AdminHealth,
): AdminOverview {
  const { directory, connectors, vault } = ctx;
  const me = directory.get(actorId);
  const myRole = me?.role ?? directory.roleOf(actorId);
  const admin = isAdminRole(myRole);
  const listing = connectors.list(actorId);
  const everyone = listing.everyone ?? [];
  const tasks = (runtime?.store.list() ?? []).filter((t) => canViewTask(directory, actorId, t));
  return {
    configured: true,
    needsBootstrap: !directory.hasOwner(),
    vaultAvailable: vault.available,
    me: { profileId: actorId, role: myRole, permissions: permsOf(myRole) },
    roles: ROLES.map((r) => ({ role: r, permissions: permsOf(r) })),
    members: can(myRole, "members.manage") ? directory.list() : me ? [me] : [],
    connectors: Object.values(CONNECTORS).map((def) => {
      const org = listing.org.find((m) => m.connector === def.kind);
      const mine = listing.mine.find((m) => m.connector === def.kind);
      return {
        kind: def.kind,
        label: def.label,
        scopes: [...def.scopes],
        fields: [...def.fields],
        policy: connectors.policy(def.kind),
        org: {
          connected: Boolean(org),
          ...(org && admin ? { by: org.createdBy } : {}),
          ...(org ? { updatedAt: org.updatedAt } : {}),
        },
        mine: { connected: Boolean(mine), ...(mine ? { updatedAt: mine.updatedAt } : {}) },
        ...(admin
          ? {
              people: everyone
                .filter((m) => m.connector === def.kind && m.scope.kind === "user")
                .map((m) => ({
                  userId: m.scope.kind === "user" ? m.scope.userId : "",
                  updatedAt: m.updatedAt,
                })),
            }
          : {}),
      };
    }),
    tasks: tasks
      .map((t) => taskRow(t, canShareTask(directory, actorId, t)))
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
    workspaces: admin
      ? (runtime?.registry.list() ?? []).map((w) => ({
          id: w.id,
          name: w.name,
          kind: w.kind,
          ...(w.github ? { repo: w.github.repo } : {}),
          grants: w.policy.grant,
          requireApproval: w.policy.requireApproval,
          production: Boolean(w.production),
          members: directory
            .list()
            .filter((m) => m.workspaces.includes(w.id) || isAdminRole(m.role))
            .map((m) => m.profileId),
        }))
      : [],
    health,
    ...(can(myRole, "connectors.audit") ? { audit: vault.auditLog().slice(-100).reverse() } : {}),
  };
}

export function applyAdminOp(
  ctx: AccessContext,
  actorId: string,
  input: unknown,
  runtime: OrionRuntime | undefined,
): { ok: true; note?: string } {
  const op = adminOpSchema.parse(input);
  const { directory, connectors } = ctx;
  const task = (id: string): MaintenanceTask => {
    const t = runtime?.store.get(id);
    if (!t || !canViewTask(directory, actorId, t)) throw new AccessDeniedError("No such task");
    return t;
  };
  const needTask = (p: Permission) => {
    if (!can(directory.roleOf(actorId), p))
      throw new AccessDeniedError(`Your role does not allow this (${p}).`);
    if (!runtime) throw new Error("The maintenance engine is not running.");
  };
  switch (op.op) {
    case "bootstrap":
      directory.bootstrapOwner(actorId);
      return { ok: true, note: "You are now the owner." };
    case "member.set": {
      directory.setMember(actorId, {
        profileId: op.profileId,
        role: op.role,
        ...(op.workspaces ? { workspaces: op.workspaces } : {}),
        ...(op.githubLogin !== undefined ? { githubLogin: op.githubLogin } : {}),
        ...(op.addresses ? { addresses: op.addresses } : {}),
      });
      return { ok: true };
    }
    case "member.remove":
      directory.removeMember(actorId, op.profileId);
      return { ok: true };
    case "connector.connect":
      connectors.connect(actorId, { connector: op.connector, scope: op.scope, fields: op.fields });
      return { ok: true };
    case "connector.disconnect":
      connectors.disconnect(actorId, {
        connector: op.connector,
        scope: op.scope,
        ...(op.userId ? { userId: op.userId } : {}),
      });
      return { ok: true };
    case "connector.policy":
      connectors.setPolicy(actorId, op.connector, {
        ...(op.userScope !== undefined ? { userScope: op.userScope } : {}),
        ...(op.orgFallback !== undefined ? { orgFallback: op.orgFallback } : {}),
      });
      return { ok: true };
    case "task.stop": {
      needTask("tasks.steer");
      runtime?.tasks.handoff(task(op.taskId).id, "blocked", `Stopped by ${actorId}`);
      return { ok: true };
    }
    case "task.retry": {
      needTask("tasks.steer");
      runtime?.tasks.transition(
        task(op.taskId).id,
        "INVESTIGATING",
        `Retry requested by ${actorId}`,
      );
      void runtime?.tick().catch(() => {});
      return { ok: true };
    }
    case "task.approve": {
      needTask("tasks.approve");
      const t = task(op.taskId);
      runtime?.tasks.approve(t.id, op.capability, actorId);
      runtime?.tasks.transition(
        t.id,
        t.changes.commits.length ? "TESTING" : "INVESTIGATING",
        `Approved ${op.capability} by ${actorId}`,
      );
      void runtime?.tick().catch(() => {});
      return { ok: true };
    }
    case "task.share": {
      const t = task(op.taskId);
      if (!canShareTask(directory, actorId, t))
        throw new AccessDeniedError("Only the task's owner or an admin can share it.");
      runtime?.tasks.apply(
        t.id,
        (x) => void (x.sharedWith.includes(op.with) || x.sharedWith.push(op.with)),
        { type: "task.shared", message: `Shared with ${op.with} by ${actorId}` },
      );
      return { ok: true };
    }
  }
}
