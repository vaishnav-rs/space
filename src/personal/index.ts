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
