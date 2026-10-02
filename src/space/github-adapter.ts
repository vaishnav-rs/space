import {
  IntegrationUnavailableError,
  type CiState,
  type GitHubPort,
  type RemoteReview,
} from "./ports.js";

type Fetch = typeof fetch;

/** Real GitHub REST adapter. With no token it reports `unavailable` instead of pretending to work. */
export function createGitHubAdapter(opts: {
  token?: string;
  apiBase?: string;
  fetchImpl?: Fetch;
  copilotReviewer?: string;
}): GitHubPort {
  const api = opts.apiBase ?? "https://api.github.com";
  const f = opts.fetchImpl ?? fetch;
  if (!opts.token) {
    const no = () =>
      Promise.reject(new IntegrationUnavailableError("github", "no token configured"));
    return {
      integration: "unavailable",
      name: "github",
      commentOnIssue: no,
      createPullRequest: no,
      requestCopilotReview: no,
      listReviews: no,
      getCiState: no,
      commentOnPullRequest: no,
    };
  }
  const call = async <T>(method: string, path: string, body?: unknown): Promise<T> => {
    const res = await f(`${api}${path}`, {
      method,
      headers: {
        authorization: `Bearer ${opts.token}`,
        accept: "application/vnd.github+json",
        "x-github-api-version": "2022-11-28",
        ...(body === undefined ? {} : { "content-type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (res.status === 403 || res.status === 429) {
      const retry = res.headers.get("retry-after");
      throw new Error(
        `GitHub rate limited or forbidden (${res.status})${retry ? `, retry after ${retry}s` : ""}`,
      );
    }
    if (!res.ok) {
      throw new Error(`GitHub ${method} ${path} failed: ${res.status}`);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  };

  return {
    integration: "real",
    name: "github",
    async commentOnIssue(repo, n, body) {
      await call("POST", `/repos/${repo}/issues/${n}/comments`, { body });
    },
    commentOnPullRequest: async (repo, n, body) => {
      await call("POST", `/repos/${repo}/issues/${n}/comments`, { body });
    },
    async createPullRequest({ repo, head, base, title, body }) {
      const pr = await call<{ number: number; html_url: string; head: { sha: string } }>(
        "POST",
        `/repos/${repo}/pulls`,
        { head, base, title, body },
      );
      return { number: pr.number, url: pr.html_url, headSha: pr.head.sha };
    },
    async requestCopilotReview(repo, n) {
      await call("POST", `/repos/${repo}/pulls/${n}/requested_reviewers`, {
        reviewers: [opts.copilotReviewer ?? "copilot-pull-request-reviewer[bot]"],
      });
    },
    async listReviews(repo, n) {
      const reviews = await call<
        { id: number; user: { login: string }; state: string; body: string | null }[]
      >("GET", `/repos/${repo}/pulls/${n}/reviews`);
      const comments = await call<
        {
          id: number;
          pull_request_review_id: number;
          path?: string;
          line?: number | null;
          body: string;
        }[]
      >("GET", `/repos/${repo}/pulls/${n}/comments`);
      return reviews.map<RemoteReview>((r) => ({
        id: String(r.id),
        reviewer: r.user.login,
        state: r.state,
        body: r.body ?? "",
        comments: comments
          .filter((c) => c.pull_request_review_id === r.id)
          .map((c) => ({
            id: String(c.id),
            ...(c.path ? { path: c.path } : {}),
            ...(c.line ? { line: c.line } : {}),
            body: c.body,
          })),
      }));
    },
    async getCiState(repo, sha) {
      const res = await call<{ check_runs: { status: string; conclusion: string | null }[] }>(
        "GET",
        `/repos/${repo}/commits/${sha}/check-runs`,
      );
      if (res.check_runs.length === 0) {
        return "unknown";
      }
      if (res.check_runs.some((c) => c.status !== "completed")) {
        return "pending";
      }
      const bad = res.check_runs.some(
        (c) => c.conclusion && !["success", "neutral", "skipped"].includes(c.conclusion),
      );
      return (bad ? "failing" : "passing") satisfies CiState;
    },
  };
}
