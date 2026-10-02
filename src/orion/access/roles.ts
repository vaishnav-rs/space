/**
 * Orion roles. They sit on top of the gateway's named operator roles (which decide what a person
 * may do to other people's sessions): these decide what Orion's own capabilities a person gets.
 */
export const ROLES = ["owner", "admin", "engineer", "member", "viewer"] as const;
export type Role = (typeof ROLES)[number];

export const PERMISSIONS = [
  "connectors.org.manage", // connect/disconnect org-wide connectors, set connector policy
  "connectors.user.manage", // connect/disconnect one's own personal connectors
  "connectors.audit", // see who has connected what (never the secrets)
  "members.manage", // assign roles and workspace access
  "tools.personal", // Gmail, Calendar, Tasks, Resend on one's own connections
  "tasks.create", // start engineering maintenance tasks
  "tasks.view.workspace", // see team tasks in workspaces one belongs to
  "tasks.approve", // approve approval-gated capabilities for a task
  "tasks.steer", // stop / retry tasks
  "chats.share", // explicitly share a chat with others
] as const;
export type Permission = (typeof PERMISSIONS)[number];

const ALL = new Set<Permission>(PERMISSIONS);

const GRANTS: Record<Role, ReadonlySet<Permission>> = {
  owner: ALL,
  admin: ALL,
  engineer: new Set<Permission>([
    "connectors.user.manage",
    "tools.personal",
    "tasks.create",
    "tasks.view.workspace",
    "tasks.steer",
    "chats.share",
  ]),
  member: new Set<Permission>(["connectors.user.manage", "tools.personal", "chats.share"]),
  viewer: new Set<Permission>(["tasks.view.workspace"]),
};

export function can(role: Role, permission: Permission): boolean {
  return GRANTS[role].has(permission);
}

export function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/** Roles that can exercise authority over other people (assigning roles, org connectors). */
export function isAdminRole(role: Role): boolean {
  return role === "owner" || role === "admin";
}

/** Only an owner may create or change other owners and admins. */
export function canAssignRole(actor: Role, target: Role): boolean {
  if (actor === "owner") return true;
  return actor === "admin" && target !== "owner" && target !== "admin";
}
