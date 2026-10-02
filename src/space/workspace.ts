import { z } from "zod";
import { CAPABILITIES, PRODUCTION_MUTATION, type Capability } from "./capabilities.js";

const capability = z.enum(CAPABILITIES);
const name = z.string().regex(/^[a-z0-9][a-z0-9._-]{0,63}$/);
/** Identifiers that end up in shell argv or file names: no separators, no whitespace. */
const token = z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._@:-]{0,127}$/);

const knowledgeSource = z.strictObject({
  id: name,
  kind: z.enum(["graphify", "wiki", "product-docs", "code", "git", "other"]),
  /** Local path (relative to the workspace root) or command-backed source. */
  path: z.string().min(1).max(512).optional(),
  description: z.string().max(280).optional(),
});

const commands = z.strictObject({
  install: z.string().max(400).optional(),
  build: z.string().max(400).optional(),
  test: z.string().max(400).optional(),
  /** `{files}` is replaced with the quoted changed-test paths. */
  testTargeted: z.string().max(400).optional(),
  lint: z.string().max(400).optional(),
  typecheck: z.string().max(400).optional(),
  format: z.string().max(400).optional(),
  dev: z.string().max(400).optional(),
});

const production = z.strictObject({
  /** An ssh_config host alias. Keys and passwords are never stored in the manifest. */
  sshHost: token,
  /** Named, fixed log sources. The agent picks a name; it never supplies a path. */
  logSources: z.record(
    name,
    z.strictObject({ unit: token.optional(), file: z.string().max(256).optional() }),
  ),
  services: z.array(token).max(32).default([]),
});

const github = z.strictObject({
  repo: z.string().regex(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/),
  /** The mention that invokes the agent, e.g. "@hewar-agent". */
  agentHandle: z.string().regex(/^@[A-Za-z0-9-]{1,39}$/),
  /** GitHub logins allowed to invoke the agent. Empty means nobody. */
  authorizedUsers: z.array(z.string().regex(/^[A-Za-z0-9-]{1,39}$/)).max(64),
});

const review = z.strictObject({
  copilot: z.boolean().default(true),
  maxReviewIterations: z.number().int().min(1).max(10).default(3),
  requireCi: z.boolean().default(true),
});

export const workspaceManifestSchema = z
  .strictObject({
    id: name,
    name: z.string().min(1).max(80),
    kind: z.enum(["personal", "project"]),
    repository: z
      .strictObject({
        remote: z.string().min(1).max(256),
        defaultBranch: z.string().min(1).max(100).default("main"),
        /** Developer's own checkout. The agent never edits it. */
        root: z.string().min(1).max(512),
        /** Where agent-owned worktrees live. */
        worktreesDir: z.string().min(1).max(512),
        branchPrefix: z
          .string()
          .regex(/^[a-z0-9][a-z0-9/_-]{0,30}$/)
          .default("agent"),
      })
      .optional(),
    packageManager: z
      .enum(["pnpm", "npm", "yarn", "bun", "pip", "uv", "cargo", "go", "other"])
      .optional(),
    runtime: z.string().max(80).optional(),
    commands: commands.default({}),
    knowledge: z.array(knowledgeSource).max(32).default([]),
    github: github.optional(),
    production: production.optional(),
    review: review.default({ copilot: true, maxReviewIterations: 3, requireCi: true }),
    policy: z.strictObject({
      /** Capabilities the agent may use without asking. */
      grant: z.array(capability).default([]),
      /** Capabilities that need explicit human approval each time. */
      requireApproval: z.array(capability).default([]),
    }),
  })
  .superRefine((m, ctx) => {
    if (m.kind === "project" && !m.repository) {
      ctx.addIssue({
        code: "custom",
        message: "project workspaces need a repository",
        path: ["repository"],
      });
    }
    const grants = new Set<Capability>(m.policy.grant);
    for (const c of m.policy.grant) {
      if (PRODUCTION_MUTATION.has(c)) {
        ctx.addIssue({
          code: "custom",
          message: `${c} is production mutation and can only be granted through requireApproval`,
          path: ["policy", "grant"],
        });
      }
    }
    for (const c of m.policy.requireApproval) {
      if (grants.has(c)) {
        ctx.addIssue({
          code: "custom",
          message: `${c} is both granted and approval-gated`,
          path: ["policy"],
        });
      }
    }
    const needsProd = [...m.policy.grant, ...m.policy.requireApproval].some((c) =>
      c.startsWith("prod."),
    );
    if (needsProd && !m.production) {
      ctx.addIssue({
        code: "custom",
        message: "production capabilities require a production block",
        path: ["production"],
      });
    }
    const gh = [...m.policy.grant, ...m.policy.requireApproval].some((c) =>
      c.startsWith("github."),
    );
    if (gh && !m.github) {
      ctx.addIssue({
        code: "custom",
        message: "github capabilities require a github block",
        path: ["github"],
      });
    }
  });

export type WorkspaceManifest = z.infer<typeof workspaceManifestSchema>;

export function parseWorkspaceManifest(input: unknown): WorkspaceManifest {
  return workspaceManifestSchema.parse(input);
}

export type WorkspaceRegistry = {
  get(id: string): WorkspaceManifest | undefined;
  list(): WorkspaceManifest[];
  findByRepo(repo: string): WorkspaceManifest | undefined;
};

export function createWorkspaceRegistry(
  manifests: readonly WorkspaceManifest[],
): WorkspaceRegistry {
  const byId = new Map<string, WorkspaceManifest>();
  for (const m of manifests) {
    if (byId.has(m.id)) {
      throw new Error(`duplicate workspace id: ${m.id}`);
    }
    byId.set(m.id, m);
  }
  return {
    get: (id) => byId.get(id),
    list: () => [...byId.values()],
    findByRepo: (repo) =>
      [...byId.values()].find((m) => m.github?.repo.toLowerCase() === repo.toLowerCase()),
  };
}
