/**
 * Capability vocabulary. The model proposes actions; the policy engine maps each action to one
 * of these and decides. Production observation and production mutation are separate families.
 */
export const CAPABILITIES = [
  "filesystem.read",
  "filesystem.write",
  "shell.execute",
  "git.read",
  "git.write",
  "git.commit",
  "git.push",
  "github.issue.read",
  "github.issue.comment",
  "github.pr.read",
  "github.pr.create",
  "github.pr.comment",
  "github.pr.review.read",
  "github.ci.read",
  "knowledge.read",
  "prod.read.logs",
  "prod.read.processes",
  "prod.read.services",
  "prod.read.metrics",
  "prod.read.config",
  "prod.read.database",
  "prod.exec",
  "prod.restart",
  "prod.deploy",
  "prod.database.write",
] as const;

export type Capability = (typeof CAPABILITIES)[number];

const CAPABILITY_SET: ReadonlySet<string> = new Set(CAPABILITIES);

export function isCapability(value: string): value is Capability {
  return CAPABILITY_SET.has(value);
}

/** Capabilities that change production. Never granted by default, never auto-approved. */
export const PRODUCTION_MUTATION: ReadonlySet<Capability> = new Set([
  "prod.exec",
  "prod.restart",
  "prod.deploy",
  "prod.database.write",
]);

/** Production capabilities that only observe. */
export const PRODUCTION_OBSERVATION: ReadonlySet<Capability> = new Set([
  "prod.read.logs",
  "prod.read.processes",
  "prod.read.services",
  "prod.read.metrics",
  "prod.read.config",
  "prod.read.database",
]);

export function isProductionCapability(c: Capability): boolean {
  return c.startsWith("prod.");
}
