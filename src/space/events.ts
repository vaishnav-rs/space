import { createHmac, timingSafeEqual } from "node:crypto";

/** Normalized events. Everything that wakes the agent arrives in this shape, whatever the source. */
export type SpaceEvent =
  | {
      type: "IssueMentioned";
      repo: string;
      issueNumber: number;
      url: string;
      title: string;
      body: string;
      author: string;
      intent: Intent;
      commentId?: number;
    }
  | {
      type: "IssueCreated";
      repo: string;
      issueNumber: number;
      url: string;
      title: string;
      body: string;
      author: string;
    }
  | {
      type: "IssueCommented";
      repo: string;
      issueNumber: number;
      url: string;
      body: string;
      author: string;
    }
  | {
      type: "PullRequestOpened";
      repo: string;
      prNumber: number;
      url: string;
      author: string;
      headRef: string;
    }
  | { type: "PullRequestUpdated"; repo: string; prNumber: number; url: string; headSha: string }
  | {
      type: "PullRequestReviewReceived";
      repo: string;
      prNumber: number;
      url: string;
      reviewer: string;
      state: string;
      body: string;
    }
  | { type: "CIFailed"; repo: string; headSha: string; checkName: string; url?: string }
  | { type: "CICompleted"; repo: string; headSha: string; conclusion: string };

export type Intent =
  | "investigate"
  | "diagnose"
  | "fix"
  | "test"
  | "review"
  | "create-pr"
  | "status"
  | "resume"
  | "stop"
  | "retry"
  | "explain";

export class MalformedEventError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MalformedEventError";
  }
}

export function verifyGithubSignature(
  secret: string,
  rawBody: string | Buffer,
  header: string | undefined,
): boolean {
  if (!secret || !header?.startsWith("sha256=")) {
    return false;
  }
  const expected = createHmac("sha256", secret).update(rawBody).digest();
  const given = Buffer.from(header.slice("sha256=".length), "hex");
  return given.length === expected.length && timingSafeEqual(given, expected);
}

const INTENT_PATTERNS: [Intent, RegExp][] = [
  [
    "create-pr",
    /\b(create|open)\b[^.\n]{0,20}\b(pr|pull request)\b|\binvestigate and (create|open)\b/i,
  ],
  ["fix", /\b(fix|resolve|patch)\b/i],
  ["diagnose", /\bdiagnos(e|is)\b/i],
  ["investigate", /\b(investigate|look into|triage|check)\b/i],
  ["test", /\b(run tests?|test this)\b/i],
  ["review", /\breview\b/i],
  ["status", /\b(status|what'?s happening|progress)\b/i],
  ["resume", /\b(resume|continue)\b/i],
  ["stop", /\b(stop|cancel|abort)\b/i],
  ["retry", /\b(retry|try again)\b/i],
  ["explain", /\b(explain|what did you do)\b/i],
];

/** Drops quoted lines and fenced code so quoting or pasting a mention cannot invoke the agent. */
function stripQuotedAndCode(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, "")
    .split("\n")
    .filter((l) => !l.trimStart().startsWith(">"))
    .join("\n");
}

export function mentionsAgent(body: string, handle: string): boolean {
  const escaped = handle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|[^A-Za-z0-9-])${escaped}(?![A-Za-z0-9-])`, "i").test(
    stripQuotedAndCode(body),
  );
}

export function parseIntent(body: string, handle: string): Intent {
  const text = stripQuotedAndCode(body);
  const after = text.slice(text.toLowerCase().indexOf(handle.toLowerCase()) + handle.length);
  for (const [intent, re] of INTENT_PATTERNS) {
    if (re.test(after)) {
      return intent;
    }
  }
  return "investigate";
}

type Rec = Record<string, unknown>;
const rec = (v: unknown, path: string): Rec => {
  if (typeof v !== "object" || v === null || Array.isArray(v)) {
    throw new MalformedEventError(`missing ${path}`);
  }
  return v as Rec;
};
const str = (v: unknown, path: string): string => {
  if (typeof v !== "string") {
    throw new MalformedEventError(`missing ${path}`);
  }
  return v;
};
const num = (v: unknown, path: string): number => {
  if (typeof v !== "number" || !Number.isInteger(v)) {
    throw new MalformedEventError(`missing ${path}`);
  }
  return v;
};

export type ParsedEvent =
  | { event: SpaceEvent; sender: { login: string; isBot: boolean } }
  | { ignored: string };

/** Turns a raw GitHub webhook into a normalized event. Unknown shapes are ignored; broken ones throw. */
export function parseGithubWebhook(
  eventName: string,
  payload: unknown,
  agentHandle: string,
): ParsedEvent {
  const p = rec(payload, "payload");
  const repo = str(rec(p.repository, "repository").full_name, "repository.full_name");
  const senderRec = rec(p.sender, "sender");
  const sender = { login: str(senderRec.login, "sender.login"), isBot: senderRec.type === "Bot" };

  if (eventName === "issues" && (p.action === "opened" || p.action === "edited")) {
    const issue = rec(p.issue, "issue");
    const body = typeof issue.body === "string" ? issue.body : "";
    const title = str(issue.title, "issue.title");
    const base = {
      repo,
      issueNumber: num(issue.number, "issue.number"),
      url: str(issue.html_url, "issue.html_url"),
      author: sender.login,
    };
    if (p.action === "opened" && !mentionsAgent(`${title}\n${body}`, agentHandle)) {
      return { event: { type: "IssueCreated", ...base, title, body }, sender };
    }
    if (mentionsAgent(`${title}\n${body}`, agentHandle)) {
      return {
        event: {
          type: "IssueMentioned",
          ...base,
          title,
          body,
          intent: parseIntent(`${title}\n${body}`, agentHandle),
        },
        sender,
      };
    }
    return { ignored: "issue without mention" };
  }

  if (eventName === "issue_comment" && p.action === "created") {
    const issue = rec(p.issue, "issue");
    const comment = rec(p.comment, "comment");
    const body = str(comment.body, "comment.body");
    const base = {
      repo,
      issueNumber: num(issue.number, "issue.number"),
      url: str(issue.html_url, "issue.html_url"),
      author: sender.login,
    };
    if (mentionsAgent(body, agentHandle)) {
      return {
        event: {
          type: "IssueMentioned",
          ...base,
          title: str(issue.title, "issue.title"),
          body,
          intent: parseIntent(body, agentHandle),
          commentId: num(comment.id, "comment.id"),
        },
        sender,
      };
    }
    return { event: { type: "IssueCommented", ...base, body }, sender };
  }

  if (eventName === "pull_request") {
    const pr = rec(p.pull_request, "pull_request");
    const head = rec(pr.head, "pull_request.head");
    const base = {
      repo,
      prNumber: num(pr.number, "pull_request.number"),
      url: str(pr.html_url, "pull_request.html_url"),
    };
    if (p.action === "opened") {
      return {
        event: {
          type: "PullRequestOpened",
          ...base,
          author: sender.login,
          headRef: str(head.ref, "head.ref"),
        },
        sender,
      };
    }
    if (p.action === "synchronize") {
      return {
        event: { type: "PullRequestUpdated", ...base, headSha: str(head.sha, "head.sha") },
        sender,
      };
    }
    return { ignored: `pull_request.${String(p.action)}` };
  }

  if (eventName === "pull_request_review" && p.action === "submitted") {
    const pr = rec(p.pull_request, "pull_request");
    const review = rec(p.review, "review");
    return {
      event: {
        type: "PullRequestReviewReceived",
        repo,
        prNumber: num(pr.number, "pull_request.number"),
        url: str(pr.html_url, "pull_request.html_url"),
        reviewer: sender.login,
        state: str(review.state, "review.state"),
        body: typeof review.body === "string" ? review.body : "",
      },
      sender,
    };
  }

  if (eventName === "check_run" && p.action === "completed") {
    const run = rec(p.check_run, "check_run");
    const headSha = str(run.head_sha, "check_run.head_sha");
    const conclusion = str(run.conclusion, "check_run.conclusion");
    const url = typeof run.html_url === "string" ? run.html_url : undefined;
    return conclusion === "failure" || conclusion === "timed_out"
      ? {
          event: {
            type: "CIFailed",
            repo,
            headSha,
            checkName: str(run.name, "check_run.name"),
            ...(url ? { url } : {}),
          },
          sender,
        }
      : { event: { type: "CICompleted", repo, headSha, conclusion }, sender };
  }

  return { ignored: `unhandled ${eventName}` };
}
