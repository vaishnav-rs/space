import type { AnyAgentTool } from "../agents/tools/common.js";
import { resolveGatewayPersonalToolParticipant } from "../agents/tools/gateway-caller-context.js";
import type { ConnectorKind, Resolution } from "../orion/access/connectors.js";
import { getAccess } from "../orion/access/context.js";
import { AccessDeniedError } from "../orion/access/directory.js";
import { readPersonalConfig, type PersonalConfig } from "./config.js";
import { createDevPulseTools } from "./dev-pulse-tools.js";
import { createGoogleTools } from "./google-tools.js";
import { guardMessageCall, type TurnOrigin } from "./guard.js";
import { createResendTool } from "./resend-tool.js";

export type PersonalToolsOptions = {
  /** Signed-in person for this turn. Defaults to the gateway caller identity. */
  getRequesterId?: () => string | undefined;
  /** Resolved per call: true only when the owner started the current turn with a message. */
  getOrigin: () => TurnOrigin;
  env?: NodeJS.ProcessEnv;
};

export function resolveRequesterId(env: NodeJS.ProcessEnv = process.env): string | undefined {
  try {
    const p = resolveGatewayPersonalToolParticipant(undefined, { requireSingleParticipant: true });
    if (p?.profileId) return p.profileId;
  } catch {
    // No authenticated participant on this turn.
  }
  // Scheduled and proactive turns have no signed-in person; they act for the configured default.
  return env.ORION_DEFAULT_REQUESTER?.trim() || undefined;
}

const EXPLAIN: Record<Exclude<Resolution, { ok: true }>["reason"], string> = {
  "not-connected":
    "isn't connected for you. Connect it from your account settings (orion-admin connect --scope user).",
  "vault-unavailable": "can't be used: the credential vault is not configured (ORION_VAULT_KEY).",
  "org-fallback-disabled":
    "isn't connected for you, and your admin does not allow using the organization's account for it.",
};

/**
 * A per-call view of the personal config. In multi-user mode each person's turn resolves their own
 * credentials and addresses; in single-user mode it is just the environment configuration.
 */
export function scopedPersonalConfig(
  base: PersonalConfig,
  options: Pick<PersonalToolsOptions, "env" | "getRequesterId">,
): PersonalConfig {
  const env = options.env ?? process.env;
  const requester = () => (options.getRequesterId ?? (() => resolveRequesterId(env)))();
  const person = () => {
    const access = getAccess(env);
    if (!access) return undefined;
    const uid = requester();
    if (!uid)
      throw new AccessDeniedError(
        "No signed-in person for this turn, so there is no account to act as.",
      );
    if (!access.directory.can(uid, "tools.personal"))
      throw new AccessDeniedError("Your role does not include the personal tools.");
    return { access, uid };
  };
  const creds = (kind: ConnectorKind) => {
    const p = person();
    if (!p) return undefined;
    const r = p.access.connectors.resolve(kind, p.uid);
    if (!r.ok) throw new AccessDeniedError(`${kind} ${EXPLAIN[r.reason]}`);
    return r.fields;
  };
  const addresses = () => {
    const access = getAccess(env);
    const uid = access ? requester() : undefined;
    return access && uid ? (access.directory.get(uid)?.addresses ?? []) : undefined;
  };
  return Object.defineProperties(
    { ...base },
    {
      google: {
        enumerable: true,
        get: () => {
          const f = creds("google");
          return f
            ? { clientId: f.clientId, clientSecret: f.clientSecret, refreshToken: f.refreshToken }
            : base.google;
        },
      },
      resend: {
        enumerable: true,
        get: () => {
          const f = creds("resend");
          return f ? { apiKey: f.apiKey, from: f.from } : base.resend;
        },
      },
      ownerEmails: { enumerable: true, get: () => addresses() ?? base.ownerEmails },
      ownerTargets: { enumerable: true, get: () => addresses() ?? base.ownerTargets },
    },
  ) as PersonalConfig;
}

/** Core tools for the owner: Google (Gmail, Calendar, Tasks), Resend, and dev analytics. */
export function createPersonalTools(options: PersonalToolsOptions): AnyAgentTool[] {
  const cfg = scopedPersonalConfig(readPersonalConfig(options.env), options);
  return [
    ...createGoogleTools(cfg, options.getOrigin),
    createResendTool(cfg, options.getOrigin),
    ...createDevPulseTools(cfg),
  ];
}

/** Wrap the channel message tool so other people are only contacted on the owner's request. */
export function guardMessageTool(tool: AnyAgentTool, options: PersonalToolsOptions): AnyAgentTool {
  const cfg = scopedPersonalConfig(readPersonalConfig(options.env), options);
  const execute = tool.execute;
  return {
    ...tool,
    execute: async (toolCallId, params, signal, onUpdate) => {
      const decision = guardMessageCall(
        (params ?? {}) as Record<string, unknown>,
        cfg.ownerTargets,
        options.getOrigin(),
      );
      if (!decision.allow) {
        throw new Error(decision.reason);
      }
      return execute(toolCallId, params, signal, onUpdate);
    },
  };
}

/** Tools that deliver into an exact external conversation have no recipient list to compare, so they need the owner's request. */
export function guardOutboundTool(tool: AnyAgentTool, options: PersonalToolsOptions): AnyAgentTool {
  const execute = tool.execute;
  return {
    ...tool,
    execute: async (toolCallId, params, signal, onUpdate) => {
      if (!options.getOrigin().ownerInitiated) {
        throw new Error(
          `Blocked: ${tool.name} contacts other people and requires the owner to ask for it in this turn. Draft it and message the owner instead.`,
        );
      }
      return execute(toolCallId, params, signal, onUpdate);
    },
  };
}
