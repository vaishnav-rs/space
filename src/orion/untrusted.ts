import { detectSuspiciousPatterns, wrapExternalContent } from "../security/external-content.js";

export type UntrustedKind = "issue" | "comment" | "review" | "knowledge" | "code" | "log" | "git";

const SOURCE = {
  issue: "webhook",
  comment: "webhook",
  review: "webhook",
  knowledge: "api",
  code: "unknown",
  log: "api",
  git: "unknown",
} as const;

export type WrappedUntrusted = { text: string; suspicious: string[] };

/**
 * Everything the agent reads from outside its own configuration is data, not instructions.
 * This wraps it with the platform's external-content markers and reports injection-looking
 * patterns so the task timeline can record them.
 */
export function wrapUntrusted(
  kind: UntrustedKind,
  label: string,
  content: string,
): WrappedUntrusted {
  return {
    text: wrapExternalContent(content, { source: SOURCE[kind], subject: label, taskName: kind }),
    suspicious: detectSuspiciousPatterns(content),
  };
}
