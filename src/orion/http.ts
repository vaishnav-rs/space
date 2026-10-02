import type { IncomingMessage, ServerResponse } from "node:http";
import { readRequestBodyWithLimit, isRequestBodyLimitError } from "../infra/http-body.js";
import type { OrionRuntime } from "./runtime.js";

export const ORION_GITHUB_WEBHOOK_PATH = "/orion/github/webhook";
const MAX_BODY_BYTES = 1024 * 1024;

function header(req: IncomingMessage, name: string): string | undefined {
  const v = req.headers[name];
  return Array.isArray(v) ? v[0] : v;
}

/**
 * Gateway HTTP stage for the GitHub webhook. Public by design: GitHub cannot present gateway
 * credentials, so the HMAC signature is the authentication and is checked before any parsing.
 * Returns false for every other path so the rest of the gateway is unaffected.
 */
export async function handleOrionWebhookRequest(
  req: IncomingMessage,
  res: ServerResponse,
  getRuntime: () => OrionRuntime | undefined,
): Promise<boolean> {
  if (!req.url || new URL(req.url, "http://localhost").pathname !== ORION_GITHUB_WEBHOOK_PATH) {
    return false;
  }
  const send = (status: number, body: object) => {
    res.statusCode = status;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify(body));
  };
  const runtime = getRuntime();
  if (!runtime) {
    send(404, { error: "not configured" });
    return true;
  }
  if (req.method !== "POST") {
    res.setHeader("allow", "POST");
    send(405, { error: "method not allowed" });
    return true;
  }
  let raw: string;
  try {
    raw = await readRequestBodyWithLimit(req, { maxBytes: MAX_BODY_BYTES, timeoutMs: 10_000 });
  } catch (err) {
    send(isRequestBodyLimitError(err) ? 413 : 400, { error: "bad request body" });
    return true;
  }
  const result = runtime.webhook(raw, {
    "x-hub-signature-256": header(req, "x-hub-signature-256"),
    "x-github-event": header(req, "x-github-event"),
    "x-github-delivery": header(req, "x-github-delivery"),
  });
  // Never echo task contents back to the caller; the status code and a short note are enough.
  if (result.status === 202) send(202, { outcome: result.outcome.kind });
  else if (result.status === 200) send(200, { ignored: result.ignored });
  else send(result.status, { error: result.error });
  return true;
}
