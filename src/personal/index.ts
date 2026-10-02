import type { AnyAgentTool } from "../agents/tools/common.js";
import { readPersonalConfig } from "./config.js";
import { createDevPulseTools } from "./dev-pulse-tools.js";
import { createGoogleTools } from "./google-tools.js";
import { guardMessageCall, type TurnOrigin } from "./guard.js";
import { createResendTool } from "./resend-tool.js";

export type PersonalToolsOptions = {
  /** Resolved per call: true only when the owner started the current turn with a message. */
  getOrigin: () => TurnOrigin;
  env?: NodeJS.ProcessEnv;
};

/** Core tools for the owner: Google (Gmail, Calendar, Tasks), Resend, and dev analytics. */
export function createPersonalTools(options: PersonalToolsOptions): AnyAgentTool[] {
  const cfg = readPersonalConfig(options.env);
  return [
    ...createGoogleTools(cfg, options.getOrigin),
    createResendTool(cfg, options.getOrigin),
    ...createDevPulseTools(cfg),
  ];
}

/** Wrap the channel message tool so other people are only contacted on the owner's request. */
export function guardMessageTool(tool: AnyAgentTool, options: PersonalToolsOptions): AnyAgentTool {
  const cfg = readPersonalConfig(options.env);
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
