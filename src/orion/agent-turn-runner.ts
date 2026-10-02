import { agentCommandFromSystem } from "../agents/agent-command.js";
import { extractAgentRunTerminalError, extractAgentRunText } from "../agents/agent-run-result.js";
import type { AgentTurnRunner } from "./openclaw-agent.js";

/**
 * The only place Orion touches the OpenClaw agent runtime. Each turn is a system-ingress run in
 * its own session: not owner-authored (so owner-only tools stay off), no message tool, no
 * delivery, and a tool allowlist limited to what the step needs. Personal tools (Gmail, Resend,
 * channel sends) are not on any step's allowlist.
 */
export function createSystemTurnRunner(): AgentTurnRunner {
  return async (req) => {
    const result = await agentCommandFromSystem(
      {
        message: req.message,
        sessionKey: req.sessionKey,
        extraSystemPrompt: req.systemPrompt,
        ...(req.cwd ? { cwd: req.cwd } : {}),
        toolsAllow: req.tools,
        timeout: String(req.timeoutSeconds),
        deliver: false,
        disableMessageTool: true,
        senderIsOwner: false,
        sessionEffects: "internal",
        allowModelOverride: false,
      },
      { boundary: "orion.maintenance" },
    );
    const view = result as Parameters<typeof extractAgentRunText>[0];
    const error = extractAgentRunTerminalError(view);
    if (error) {
      throw new Error(`agent turn failed: ${error}`);
    }
    const text = extractAgentRunText(view);
    if (!text) {
      throw new Error("agent turn produced no reply");
    }
    return text;
  };
}
