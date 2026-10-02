/** View model returned by the gateway's orion.admin.* methods (see src/orion/access/admin-api.ts). */
export type OrionRole = "owner" | "admin" | "engineer" | "member" | "viewer";

export type OrionConnector = {
  kind: string;
  label: string;
  scopes: ("org" | "user")[];
  fields: string[];
  policy: { userScope: boolean; orgFallback: boolean };
  org: { connected: boolean; by?: string; updatedAt?: string };
  mine: { connected: boolean; updatedAt?: string };
  people?: { userId: string; updatedAt: string }[];
};

export type OrionOverview =
  | { configured: false; reason: string }
  | {
      configured: true;
      needsBootstrap: boolean;
      vaultAvailable: boolean;
      me: { profileId: string; role: OrionRole; permissions: string[] };
      roles: { role: OrionRole; permissions: string[] }[];
      members: {
        profileId: string;
        role: OrionRole;
        workspaces: string[];
        githubLogin?: string;
        addresses: string[];
      }[];
      connectors: OrionConnector[];
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
      health: {
        agentBound: boolean;
        githubToken: boolean;
        webhookSecret: boolean;
        vaultKey: boolean;
      };
      audit?: {
        at: string;
        actor: string;
        action: string;
        connector: string | null;
        scope: string | null;
      }[];
    };

export const ROLE_LABEL: Record<OrionRole, string> = {
  owner: "Owner",
  admin: "Admin",
  engineer: "Engineer",
  member: "Member",
  viewer: "Viewer",
};

export const ROLE_BLURB: Record<OrionRole, string> = {
  owner: "Everything, including creating admins.",
  admin: "Manages people, org connectors and approvals; sees all tasks.",
  engineer: "Personal tools, starts and steers engineering tasks in their workspaces.",
  member: "Personal assistant with their own accounts; no engineering tasks.",
  viewer: "Read-only: sees what has been shared with them.",
};
