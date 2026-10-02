import { ErrorCodes, errorShape } from "../../../packages/gateway-protocol/src/index.js";
import { adminOverview, applyAdminOp } from "../../orion/access/admin-api.js";
import { getAccess } from "../../orion/access/context.js";
import { AccessDeniedError } from "../../orion/access/directory.js";
import { getOrionRuntime } from "../../orion/singleton.js";
import type { GatewayRequestHandlerOptions, GatewayRequestHandlers } from "./types.js";
import { prepareAuthenticatedProfile } from "./users-profile-access.js";

const NOT_CONFIGURED =
  "Orion access control is not configured. Set ORION_STATE_DIR and ORION_VAULT_KEY on the gateway host and restart.";

/** The authenticated person behind this request. Orion roles, not gateway scopes, decide what they may change. */
async function requester(options: GatewayRequestHandlerOptions): Promise<string> {
  const prepared = await prepareAuthenticatedProfile(options);
  prepared.assertCurrent();
  if (!prepared.profileId)
    throw new AccessDeniedError("Sign in with a personal profile to use Orion admin.");
  return prepared.profileId;
}

function fail(options: GatewayRequestHandlerOptions, error: unknown) {
  // Orion's refusals and validation errors are meant for the person who asked.
  const message = error instanceof Error ? error.message : String(error);
  options.respond(false, undefined, errorShape(ErrorCodes.INVALID_REQUEST, message.slice(0, 400)));
}

export const orionHandlers: GatewayRequestHandlers = {
  "orion.admin.overview": async (options) => {
    const access = getAccess();
    if (!access) {
      options.respond(true, { configured: false, reason: NOT_CONFIGURED }, undefined);
      return;
    }
    try {
      const actor = await requester(options);
      const env = process.env;
      options.respond(
        true,
        adminOverview(access, actor, getOrionRuntime(), {
          agentBound: Boolean(getOrionRuntime()),
          githubToken: Boolean(env.ORION_GITHUB_TOKEN),
          webhookSecret: Boolean(env.ORION_GITHUB_WEBHOOK_SECRET),
          vaultKey: access.vault.available,
        }),
        undefined,
      );
    } catch (error) {
      fail(options, error);
    }
  },
  "orion.admin.apply": async (options) => {
    const access = getAccess();
    if (!access) {
      options.respond(false, undefined, errorShape(ErrorCodes.UNAVAILABLE, NOT_CONFIGURED));
      return;
    }
    try {
      const actor = await requester(options);
      options.respond(
        true,
        applyAdminOp(access, actor, options.params, getOrionRuntime()),
        undefined,
      );
    } catch (error) {
      fail(options, error);
    }
  },
};
