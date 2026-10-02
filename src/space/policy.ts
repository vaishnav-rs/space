import { isProductionCapability, PRODUCTION_MUTATION, type Capability } from "./capabilities.js";
import type { WorkspaceManifest } from "./workspace.js";

/** Who is asking. Text found in issues, repos, docs or logs is data and never becomes an actor. */
export type Actor =
  | { kind: "owner" }
  | { kind: "github-user"; login: string }
  | { kind: "system"; reason: string };

export type PolicyDecision =
  | { effect: "allow"; reason: string }
  | { effect: "needs_approval"; reason: string }
  | { effect: "deny"; reason: string };

export type PolicyRequest = {
  workspace: WorkspaceManifest;
  actor: Actor;
  capability: Capability;
  /** Human-readable target, recorded in the audit trail. */
  resource?: string;
  /** Set when a human has approved exactly this capability for this task. */
  approvedCapabilities?: readonly Capability[];
};

export function isAuthorizedGithubUser(workspace: WorkspaceManifest, login: string): boolean {
  const allowed = workspace.github?.authorizedUsers ?? [];
  return allowed.some((u) => u.toLowerCase() === login.toLowerCase());
}

/**
 * Deterministic policy decision. Order matters: actor authorization, then explicit grants,
 * then approval gates, then default deny.
 */
export function decide(req: PolicyRequest): PolicyDecision {
  const { workspace, actor, capability } = req;

  if (actor.kind === "github-user" && !isAuthorizedGithubUser(workspace, actor.login)) {
    return {
      effect: "deny",
      reason: `GitHub user ${actor.login} is not authorized for ${workspace.id}`,
    };
  }

  if (isProductionCapability(capability) && !workspace.production) {
    return { effect: "deny", reason: `${workspace.id} has no production configuration` };
  }

  if (workspace.policy.grant.includes(capability)) {
    // Grants are validated at load time to exclude production mutation; re-check defensively.
    if (PRODUCTION_MUTATION.has(capability)) {
      return { effect: "deny", reason: `${capability} cannot be granted without approval` };
    }
    return { effect: "allow", reason: `granted by ${workspace.id} policy` };
  }

  if (workspace.policy.requireApproval.includes(capability)) {
    return req.approvedCapabilities?.includes(capability)
      ? { effect: "allow", reason: "approved by a human" }
      : { effect: "needs_approval", reason: `${capability} requires human approval` };
  }

  return { effect: "deny", reason: `${capability} is not granted in ${workspace.id}` };
}

export class PolicyDeniedError extends Error {
  constructor(
    readonly decision: Exclude<PolicyDecision, { effect: "allow" }>,
    readonly capability: Capability,
  ) {
    super(`${capability}: ${decision.reason}`);
    this.name = "PolicyDeniedError";
  }
}

/** Throws unless the request is allowed. Capability ports call this before any side effect. */
export function enforce(req: PolicyRequest): void {
  const decision = decide(req);
  if (decision.effect !== "allow") {
    throw new PolicyDeniedError(decision, req.capability);
  }
}
