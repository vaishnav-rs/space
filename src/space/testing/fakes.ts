import type { AgentPort } from "../agent-port.js";
import type {
  CiState,
  GitHubPort,
  KnowledgeHit,
  KnowledgePort,
  ProductionPort,
  RemoteReview,
} from "../ports.js";
import type { ClassifiedFinding, FindingClassifier } from "../review-loop.js";

/** Test doubles. They declare `integration: "mock"` so production wiring refuses them. */
export function fakeKnowledge(
  id: string,
  kind: KnowledgePort["kind"],
  hits: KnowledgeHit[],
  integration: KnowledgePort["integration"] = "mock",
): KnowledgePort {
  return { integration, name: id, id, kind, query: async () => hits };
}

export class FakeGitHub implements GitHubPort {
  readonly integration = "mock" as const;
  readonly name = "github";
  comments: { target: string; body: string }[] = [];
  prs: { number: number; head: string; title: string; body: string }[] = [];
  reviewRequests = 0;
  ci: CiState[] = ["passing"];
  reviews: RemoteReview[] = [];
  async commentOnIssue(repo: string, n: number, body: string) {
    this.comments.push({ target: `${repo}#${n}`, body });
  }
  async commentOnPullRequest(repo: string, n: number, body: string) {
    this.comments.push({ target: `${repo}!${n}`, body });
  }
  async createPullRequest(i: {
    repo: string;
    head: string;
    base: string;
    title: string;
    body: string;
  }) {
    const number = 427;
    this.prs.push({ number, head: i.head, title: i.title, body: i.body });
    return { number, url: `https://github.com/${i.repo}/pull/${number}`, headSha: "pending-sha" };
  }
  async requestCopilotReview() {
    this.reviewRequests += 1;
  }
  async listReviews() {
    return this.reviews;
  }
  async getCiState() {
    return this.ci.length > 1 ? (this.ci.shift() as CiState) : (this.ci[0] ?? "unknown");
  }
}

export function fakeProduction(logs: string): ProductionPort & { calls: string[] } {
  const calls: string[] = [];
  return {
    integration: "mock",
    name: "production",
    calls,
    async readLogs(i) {
      calls.push(`logs:${i.source}`);
      return logs;
    },
    async readProcesses() {
      calls.push("processes");
      return "";
    },
    async readServiceStatus(s) {
      calls.push(`service:${s}`);
      return "";
    },
  };
}

export class ScriptedClassifier implements FindingClassifier {
  seen: string[] = [];
  constructor(private readonly byMatch: [RegExp, ClassifiedFinding["classification"], string][]) {}
  async classify({ findings }: Parameters<FindingClassifier["classify"]>[0]) {
    return findings.map((f) => {
      this.seen.push(f.body);
      const hit = this.byMatch.find(([re]) => re.test(f.body));
      return {
        findingId: f.id,
        classification: hit?.[1] ?? "UNCERTAIN",
        rationale: hit?.[2] ?? "no rule",
      };
    });
  }
}

export type ScriptedAgentHooks = {
  fix: (input: Parameters<AgentPort["fix"]>[0]) => Promise<void>;
  diagnosis?: Awaited<ReturnType<AgentPort["diagnose"]>>;
  needsInformation?: { message: string; needed: string };
};

export function scriptedAgent(
  hooks: ScriptedAgentHooks,
): AgentPort & { investigations: Parameters<AgentPort["investigate"]>[0][] } {
  const investigations: Parameters<AgentPort["investigate"]>[0][] = [];
  return {
    integration: "mock",
    name: "agent",
    investigations,
    async investigate(input) {
      investigations.push(input);
      return {
        summary: "Profile image upload fails",
        area: "uploads",
        evidence: [
          {
            kind: "code",
            summary: "upload handler enforces a 100kb body limit",
            ref: "src/upload.js:3",
          },
        ],
        hypotheses: [
          { statement: "Body size limit too low", status: "supported", evidenceIds: [] },
        ],
        tryReproduce: true,
        ...(hooks.needsInformation ? { needsInformation: hooks.needsInformation } : {}),
      };
    },
    async reproduce() {
      return {
        status: "confirmed",
        evidence: [{ kind: "test", summary: "2MB avatar returns 413", ref: "test/upload.test.js" }],
      };
    },
    async diagnose() {
      return (
        hooks.diagnosis ?? {
          rootCause: "MAX_UPLOAD_BYTES is 100kb; avatars are up to 5MB",
          confidence: "confirmed",
        }
      );
    },
    async fix(input) {
      await hooks.fix(input);
      return {
        summary:
          input.reason.kind === "review" ? "addressed review findings" : "raise upload limit",
        commitMessage:
          input.reason.kind === "review"
            ? "fix: address review findings"
            : "fix: allow avatar uploads up to 5MB",
      };
    },
    async selfReview() {
      return {
        passed: true,
        notes: ["scope limited to upload limit", "test added"],
        answeredSolved: true,
        unrelatedChanges: false,
      };
    },
    async describePullRequest({ task }) {
      return {
        title: "Fix profile image upload",
        body: `Fixes the reported issue.\n\nRoot cause: ${task.investigation.diagnosis?.rootCause ?? "see task"}`,
      };
    },
  };
}
