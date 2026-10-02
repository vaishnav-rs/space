import { parseGithubWebhook, verifyGithubSignature, MalformedEventError } from "./events.js";
import type { EventRouter, RouteOutcome } from "./router.js";

export type WebhookResult =
  | { status: 202; outcome: RouteOutcome }
  | { status: 200; ignored: string }
  | { status: 400; error: string }
  | { status: 401; error: string };

/**
 * Transport-agnostic GitHub webhook entrypoint. The gateway (or any HTTP host) passes the raw
 * body and headers; this verifies the signature before parsing anything, then routes.
 */
export function handleGithubWebhook(input: {
  secret: string;
  rawBody: string;
  headers: Record<string, string | undefined>;
  agentHandle: string;
  router: EventRouter;
}): WebhookResult {
  if (!verifyGithubSignature(input.secret, input.rawBody, input.headers["x-hub-signature-256"])) {
    return { status: 401, error: "invalid signature" };
  }
  const eventName = input.headers["x-github-event"];
  if (!eventName) return { status: 400, error: "missing x-github-event" };
  let payload: unknown;
  try {
    payload = JSON.parse(input.rawBody);
  } catch {
    return { status: 400, error: "invalid JSON" };
  }
  try {
    const parsed = parseGithubWebhook(eventName, payload, input.agentHandle);
    if ("ignored" in parsed) return { status: 200, ignored: parsed.ignored };
    return {
      status: 202,
      outcome: input.router.handle(parsed.event, parsed.sender, input.headers["x-github-delivery"]),
    };
  } catch (err) {
    if (err instanceof MalformedEventError) return { status: 400, error: err.message };
    throw err;
  }
}
