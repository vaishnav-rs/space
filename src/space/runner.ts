import type { AgentPort } from "./agent-port.js";
import { buildContext, type ContextResult } from "./context-engine.js";
import { agentBranchName } from "./git-adapter.js";
import { enforce, PolicyDeniedError, type Actor } from "./policy.js";
import {
  IntegrationUnavailableError,
  type GitHubPort,
  type GitPort,
  type KnowledgePort,
  type ProductionPort,
  type ShellPort,
} from "./ports.js";
import { redactSecrets, tail } from "./redact.js";
import {
  decideAfterClassification,
  findingsFromReviews,
  type FindingClassifier,
} from "./review-loop.js";
import type { TaskService } from "./task-service.js";
import {
  HUMAN_WAIT_STATUSES,
  TERMINAL_STATUSES,
  type MaintenanceTask,
  type TaskStatus,
  type ValidationResult,
} from "./task-types.js";
import { wrapUntrusted } from "./untrusted.js";
import type { WorkspaceManifest } from "./workspace.js";

export type RunnerPorts = {
  git: GitPort;
  github: GitHubPort;
  shell: ShellPort;
  agent: AgentPort;
  classifier: FindingClassifier;
  knowledge: KnowledgePort[];
  production?: ProductionPort;
};

export type RunnerOptions = {
  maxFixAttempts: number;
  contextBudgetChars: number;
  /** Time to wait for an external review before escalating. */
  reviewTimeoutMs: number;
  now: () => number;
  /** Tests may use mock ports; production wiring must not. */
  allowNonRealPorts: boolean;
};

export const DEFAULT_RUNNER_OPTIONS: RunnerOptions = {
  maxFixAttempts: 3,
  contextBudgetChars: 24_000,
  reviewTimeoutMs: 30 * 60_000,
  now: Date.now,
  allowNonRealPorts: false,
};

export type StepResult = { status: TaskStatus; progressed: boolean; waiting?: string };

const ACTOR: Actor = { kind: "system", reason: "task-runner" };

export class MaintenanceRunner {
  constructor(
    private readonly tasks: TaskService,
    private readonly workspace: WorkspaceManifest,
    private readonly ports: RunnerPorts,
    private readonly options: RunnerOptions = DEFAULT_RUNNER_OPTIONS,
  ) {
    if (!options.allowNonRealPorts) {
      const fake = [
        ports.git,
        ports.github,
        ports.shell,
        ports.agent,
        ...ports.knowledge,
        ...(ports.production ? [ports.production] : []),
      ].filter((p) => p.integration === "mock" || p.integration === "stub");
      if (fake.length > 0) {
        throw new Error(
          `refusing to run with non-real integrations: ${fake.map((p) => `${p.name}(${p.integration})`).join(", ")}`,
        );
      }
    }
  }

  private need(
    capability: Parameters<typeof enforce>[0]["capability"],
    resource?: string,
    task?: MaintenanceTask,
  ): void {
    enforce({
      workspace: this.workspace,
      actor: ACTOR,
      capability,
      ...(resource ? { resource } : {}),
      ...(task
        ? { approvedCapabilities: task.approvals.map((a) => a.capability as typeof capability) }
        : {}),
    });
  }

  /** Advances at most one transition. Safe to call after any restart: it reads persisted state only. */
  async step(taskId: string): Promise<StepResult> {
    const task = this.tasks.get(taskId);
    if (TERMINAL_STATUSES.has(task.status) || HUMAN_WAIT_STATUSES.has(task.status)) {
      return {
        status: task.status,
        progressed: false,
        waiting: "waiting for a person or finished",
      };
    }
    try {
      return await this.dispatch(task);
    } catch (err) {
      return this.failStep(task, err);
    }
  }

  async runUntilIdle(taskId: string, maxSteps = 40): Promise<StepResult> {
    let last: StepResult = { status: this.tasks.get(taskId).status, progressed: false };
    for (let i = 0; i < maxSteps; i++) {
      last = await this.step(taskId);
      if (!last.progressed) {
        break;
      }
    }
    return last;
  }

  private failStep(task: MaintenanceTask, err: unknown): StepResult {
    if (err instanceof PolicyDeniedError) {
      const t =
        err.decision.effect === "needs_approval"
          ? this.tasks.handoff(
              task.id,
              "needs_approval",
              `Needs approval for ${err.capability}.`,
              `Approve ${err.capability} for this task.`,
            )
          : this.tasks.handoff(
              task.id,
              "blocked",
              `Policy denied ${err.capability}: ${err.decision.reason}`,
              `Grant ${err.capability} in the workspace policy, or handle this step manually.`,
            );
      return { status: t.status, progressed: true };
    }
    if (err instanceof IntegrationUnavailableError) {
      const t = this.tasks.handoff(
        task.id,
        "blocked",
        `I cannot continue: ${err.message}.`,
        err.message,
      );
      return { status: t.status, progressed: true };
    }
    const message = redactSecrets(err instanceof Error ? err.message : String(err));
    const recorded = this.tasks.recordError(task.id, message, true);
    if (recorded.stepAttempts >= this.options.maxFixAttempts) {
      const t = this.tasks.handoff(
        task.id,
        "failed",
        `Step ${task.status} failed ${recorded.stepAttempts} times: ${message}`,
      );
      return { status: t.status, progressed: true };
    }
    return { status: recorded.status, progressed: false, waiting: `retryable error: ${message}` };
  }

  private async gatherContext(task: MaintenanceTask): Promise<ContextResult> {
    this.need("knowledge.read", undefined, task);
    const ctx = await buildContext({
      report: task.report,
      sources: this.ports.knowledge,
      budgetChars: this.options.contextBudgetChars,
    });
    this.tasks.apply(task.id, () => {}, {
      type: "context.retrieved",
      message: "Context retrieved",
      data: {
        explanation: ctx.explanation,
        contradictions: ctx.contradictions.length,
        suspicious: ctx.suspicious.length,
      },
    });
    for (const c of ctx.contradictions) {
      this.tasks.addEvidence(task.id, {
        kind: "doc",
        summary: `Sources disagree on ${c.subject} ${c.predicate}: ${c.values.map((v) => `${v.source}="${v.value}"`).join(" vs ")}`,
        ref: c.values.map((v) => v.ref).join(" | "),
      });
    }
    return ctx;
  }

  private async observe(
    task: MaintenanceTask,
  ): Promise<{ logs?: string; processes?: string } | undefined> {
    const prod = this.ports.production;
    if (!prod || prod.integration === "unavailable" || !this.workspace.production) {
      return undefined;
    }
    const out: { logs?: string; processes?: string } = {};
    try {
      this.need("prod.read.logs", undefined, task);
      const first = Object.keys(this.workspace.production.logSources)[0];
      if (first) {
        out.logs = await prod.readLogs({ source: first, lines: 300 });
      }
    } catch (err) {
      if (!(err instanceof PolicyDeniedError)) {
        throw err;
      }
    }
    if (out.logs) {
      const wrapped = wrapUntrusted("log", "production", out.logs);
      if (wrapped.suspicious.length) {
        this.tasks.apply(task.id, () => {}, {
          type: "security.suspicious",
          message: "Instruction-like text found in production logs (treated as data)",
          data: { patterns: wrapped.suspicious },
        });
      }
      this.tasks.addEvidence(task.id, {
        kind: "production",
        summary: `Read ${out.logs.split("\n").length} production log lines`,
        ref: "prod:logs",
      });
    }
    return out.logs || out.processes ? out : undefined;
  }

  private async note(task: MaintenanceTask, message: string): Promise<void> {
    if (task.source.kind !== "github-issue" || this.ports.github.integration === "unavailable") {
      return;
    }
    try {
      this.need("github.issue.comment", undefined, task);
      await this.ports.github.commentOnIssue(task.source.repo, task.source.issueNumber, message);
    } catch (err) {
      // A failed progress comment must never fail the engineering task.
      this.tasks.apply(task.id, () => {}, {
        type: "notify.failed",
        message: `Could not post update: ${err instanceof Error ? err.message : String(err)}`,
      });
    }
  }

  private async dispatch(task: MaintenanceTask): Promise<StepResult> {
    const id = task.id;
    const go = (status: TaskStatus, waiting?: string): StepResult => ({
      status,
      progressed: !waiting,
      ...(waiting ? { waiting } : {}),
    });
    const repo = this.workspace.repository;

    switch (task.status) {
      case "REPORTED": {
        const wrapped = wrapUntrusted("issue", "report", task.report);
        if (wrapped.suspicious.length) {
          this.tasks.apply(id, () => {}, {
            type: "security.suspicious",
            message: "Report contains instruction-like text; treated as data only",
            data: { patterns: wrapped.suspicious },
          });
        }
        await this.note(task, "Investigating…");
        return go(
          this.tasks.transition(id, "INVESTIGATING", "Workspace identified, starting investigation")
            .status,
        );
      }
      case "TRIAGING":
        return go(this.tasks.transition(id, "INVESTIGATING", "Triage complete").status);

      case "INVESTIGATING": {
        this.need("github.issue.read", undefined, task);
        const context = await this.gatherContext(task);
        let git;
        if (repo) {
          this.need("git.read", undefined, task);
          git = await this.ports.git.inspect(repo.root);
        }
        const production = await this.observe(task);
        const result = await this.ports.agent.investigate({ task, context, git, production });
        for (const e of result.evidence) {
          this.tasks.addEvidence(id, e);
        }
        this.tasks.apply(
          id,
          (t) => {
            t.investigation.summary = result.summary;
            if (result.area) t.investigation.area = result.area;
            t.investigation.hypotheses = result.hypotheses.map((h, i) => ({
              ...h,
              id: `h${i + 1}`,
            }));
          },
          { type: "investigation.recorded", message: `Understood: ${result.summary}` },
        );
        if (result.needsInformation) {
          return go(
            this.tasks.handoff(
              id,
              "needs_information",
              result.needsInformation.message,
              result.needsInformation.needed,
            ).status,
          );
        }
        return go(
          this.tasks.transition(
            id,
            result.tryReproduce ? "REPRODUCING" : "DIAGNOSING",
            result.tryReproduce ? "Attempting reproduction" : "Diagnosing from evidence",
          ).status,
        );
      }

      case "REPRODUCING": {
        const context = await this.gatherContext(task);
        const r = await this.ports.agent.reproduce({
          task,
          context,
          git: undefined,
          production: undefined,
        });
        for (const e of r.evidence) {
          this.tasks.addEvidence(id, e);
        }
        this.tasks.apply(id, (t) => void (t.investigation.reproduction = r.status), {
          type: "reproduction.recorded",
          message: `Reproduction: ${r.status}`,
        });
        return go(this.tasks.transition(id, "DIAGNOSING", "Diagnosing").status);
      }

      case "DIAGNOSING": {
        const context = await this.gatherContext(task);
        const d = await this.ports.agent.diagnose({
          task,
          context,
          git: undefined,
          production: undefined,
        });
        for (const e of d.evidence ?? []) {
          this.tasks.addEvidence(id, e);
        }
        this.tasks.apply(
          id,
          (t) =>
            void (t.investigation.diagnosis = { rootCause: d.rootCause, confidence: d.confidence }),
          { type: "diagnosis.recorded", message: `Diagnosis (${d.confidence}): ${d.rootCause}` },
        );
        if (d.confidence === "insufficient") {
          return go(
            this.tasks.handoff(
              id,
              "needs_information",
              `I could not determine the cause: ${d.rootCause}`,
              "More detail on how to reproduce, or access to the failing environment.",
            ).status,
          );
        }
        return go(
          this.tasks.transition(id, "FIXING", "Starting fix in an isolated worktree").status,
        );
      }

      case "FIXING":
      case "ADDRESSING_REVIEW": {
        if (!repo) throw new Error("workspace has no repository");
        let worktree = task.changes.worktree;
        if (!worktree) {
          this.need("git.read", undefined, task);
          this.need("git.write", undefined, task);
          const issue =
            task.source.kind === "github-issue"
              ? task.source.issueNumber
              : Number.parseInt(task.id.slice(0, 6), 16);
          const branch = agentBranchName(repo.branchPrefix, this.workspace.id, issue);
          const wt = await this.ports.git.createWorktree({
            repoRoot: repo.root,
            worktreesDir: repo.worktreesDir,
            branch,
            base: repo.defaultBranch,
          });
          this.tasks.apply(
            id,
            (t) =>
              void (t.changes = {
                ...t.changes,
                branch,
                worktree: wt.worktree,
                baseSha: wt.baseSha,
              }),
            {
              type: "worktree.created",
              message: `Branch ${branch} in an isolated worktree`,
              data: { branch },
            },
          );
          worktree = wt.worktree;
        }
        this.need("filesystem.write", worktree, task);
        const fresh = this.tasks.get(id);
        const reason =
          task.status === "ADDRESSING_REVIEW"
            ? ({
                kind: "review",
                findings: fresh.review.findings.filter(
                  (f) => !f.resolved && f.classification === "VALID",
                ),
              } as const)
            : fresh.validation.some((v) => !v.passed)
              ? ({
                  kind: "validation",
                  failures: fresh.validation.filter((v) => !v.passed).slice(-3),
                } as const)
              : fresh.ci?.state === "failing"
                ? ({ kind: "ci", detail: "CI failed on the pushed commit" } as const)
                : ({ kind: "initial" } as const);
        const fix = await this.ports.agent.fix({
          task: fresh,
          worktree,
          context: undefined,
          reason,
        });
        this.tasks.apply(id, (t) => void (t.changes.pendingCommitMessage = fix.commitMessage), {
          type: "fix.applied",
          message: `Fix applied: ${fix.summary}`,
        });
        return go(this.tasks.transition(id, "TESTING", "Running validation").status);
      }

      case "TESTING": {
        const worktree = task.changes.worktree;
        if (!worktree) throw new Error("no worktree to test");
        this.need("shell.execute", worktree, task);
        const diff = await this.ports.git.diffStat(worktree, task.changes.baseSha ?? "HEAD");
        const cmds = this.workspace.commands;
        const testFiles = diff.files.filter((f) =>
          /(^|\/)(test|tests|__tests__)\/|\.(test|spec)\./.test(f),
        );
        const plan: { kind: ValidationResult["kind"]; command: string }[] = [];
        const targeted =
          cmds.testTargeted && testFiles.length
            ? cmds.testTargeted.replace(
                "{files}",
                testFiles.map((f) => `'${f.replaceAll("'", "")}'`).join(" "),
              )
            : cmds.test;
        if (targeted) plan.push({ kind: "targeted", command: targeted });
        if (cmds.lint) plan.push({ kind: "lint", command: cmds.lint });
        if (cmds.typecheck) plan.push({ kind: "typecheck", command: cmds.typecheck });
        if (cmds.build) plan.push({ kind: "build", command: cmds.build });
        if (cmds.test) plan.push({ kind: "regression", command: cmds.test });
        let failed = false;
        for (const step of plan) {
          const r = await this.ports.shell.run({
            cwd: worktree,
            command: step.command,
            timeoutMs: 15 * 60_000,
          });
          this.tasks.recordValidation(id, {
            kind: step.kind,
            command: step.command,
            passed: r.exitCode === 0,
            exitCode: r.exitCode,
            outputTail: tail(redactSecrets(r.output), 2000),
          });
          if (r.exitCode !== 0) {
            failed = true;
            break; // fail fast: layered validation stops at the first failing layer
          }
        }
        if (failed) {
          const attempts = this.tasks.get(id).errors.filter((e) => e.stage === "TESTING").length;
          this.tasks.recordError(id, "validation failed", true);
          if (attempts + 1 >= this.options.maxFixAttempts) {
            return go(
              this.tasks.handoff(
                id,
                "blocked",
                "Validation keeps failing after repeated fix attempts.",
                "A human needs to look at the failing validation output.",
              ).status,
            );
          }
          return go(this.tasks.transition(id, "FIXING", "Validation failed; revising").status);
        }
        return go(this.tasks.transition(id, "SELF_REVIEW", "Validation passed").status);
      }

      case "SELF_REVIEW": {
        const worktree = task.changes.worktree;
        const branch = task.changes.branch;
        if (!worktree || !branch || !repo) throw new Error("no worktree to review");
        const diff = await this.ports.git.diffStat(worktree, task.changes.baseSha ?? "HEAD");
        const review = await this.ports.agent.selfReview({
          task,
          files: diff.files,
          patch: diff.patch,
        });
        this.tasks.apply(id, (t) => void (t.selfReview = review), {
          type: "self-review",
          message: `Self-review ${review.passed ? "passed" : "found problems"}: ${review.notes.join("; ")}`,
        });
        if (!review.passed || !review.answeredSolved || review.unrelatedChanges) {
          this.tasks.recordError(id, "self-review rejected the change", true);
          return go(this.tasks.transition(id, "FIXING", "Self-review rejected the change").status);
        }
        const message =
          task.changes.pendingCommitMessage ??
          `fix: ${task.investigation.summary ?? "maintenance fix"}`;
        this.need("git.commit", worktree, task);
        const { sha } = await this.ports.git.commit({ worktree, message });
        this.tasks.apply(id, (t) => void t.changes.commits.push(sha), {
          type: "commit.created",
          message: `Committed ${sha.slice(0, 8)}`,
          data: { sha },
        });
        this.need("git.push", branch, task);
        await this.ports.git.push({ worktree, branch });
        const repoSlug = this.workspace.github?.repo ?? "";
        if (task.pullRequest) {
          // Follow-up commit on an open PR: record it, mark the valid findings it fixed, wait on CI again.
          this.tasks.apply(
            id,
            (t) => {
              if (t.pullRequest) t.pullRequest.headSha = sha;
              for (const f of t.review.findings) {
                if (!f.resolved && f.classification === "VALID") {
                  f.resolved = true;
                  f.fixedIn = sha;
                }
              }
              t.ci = { state: "pending", checkedAt: new Date(this.options.now()).toISOString() };
            },
            {
              type: "pr.updated",
              message: `Pushed ${sha.slice(0, 8)} to PR #${task.pullRequest.number}`,
            },
            "CI_RUNNING",
          );
          await this.note(
            this.tasks.get(id),
            `Pushed a follow-up commit to PR #${task.pullRequest.number} addressing review findings.`,
          );
          return go("CI_RUNNING");
        }
        this.need("github.pr.create", branch, task);
        const text = await this.ports.agent.describePullRequest({
          task: this.tasks.get(id),
          files: diff.files,
        });
        const pr = await this.ports.github.createPullRequest({
          repo: repoSlug,
          head: branch,
          base: repo.defaultBranch,
          title: text.title,
          body: redactSecrets(text.body),
        });
        // Record the PR and move on in one write: a crash here must not leave a PR the task does not know about.
        this.tasks.apply(
          id,
          (t) => void (t.pullRequest = { number: pr.number, url: pr.url, headSha: sha }),
          { type: "pr.created", message: `Opened PR #${pr.number}`, data: { url: pr.url } },
          "PR_OPEN",
        );
        await this.note(
          this.tasks.get(id),
          `Fix implemented. Tests pass. PR #${pr.number} is open: ${pr.url}`,
        );
        return go("PR_OPEN");
      }

      case "PR_OPEN": {
        if (this.workspace.review.copilot && this.ports.github.integration !== "unavailable") {
          this.need("github.pr.comment", undefined, task);
          await this.ports.github.requestCopilotReview(
            this.workspace.github?.repo ?? "",
            task.pullRequest?.number ?? 0,
          );
          this.tasks.apply(
            id,
            (t) => void (t.review.requestedAt = new Date(this.options.now()).toISOString()),
            { type: "review.requested", message: "Requested Copilot review" },
          );
          return go(
            this.tasks.transition(id, "COPILOT_REVIEW_PENDING", "Waiting for Copilot review")
              .status,
          );
        }
        return go(this.tasks.transition(id, "CI_RUNNING", "Waiting for CI").status);
      }

      case "CI_RUNNING": {
        this.need("github.ci.read", undefined, task);
        const sha = task.pullRequest?.headSha;
        if (!sha) throw new Error("PR has no head sha");
        const state = await this.ports.github.getCiState(this.workspace.github?.repo ?? "", sha);
        this.tasks.apply(
          id,
          (t) => void (t.ci = { state, checkedAt: new Date(this.options.now()).toISOString() }),
          { type: "ci.checked", message: `CI ${state}` },
        );
        if (state === "pending" || state === "unknown") {
          return { status: task.status, progressed: false, waiting: `CI ${state}` };
        }
        if (state === "failing") {
          return go(this.tasks.transition(id, "FIXING", "CI failed; revising").status);
        }
        return go(await this.finishOrReview(id));
      }

      case "COPILOT_REVIEW_PENDING": {
        this.need("github.pr.review.read", undefined, task);
        const reviews = await this.ports.github.listReviews(
          this.workspace.github?.repo ?? "",
          task.pullRequest?.number ?? 0,
        );
        const incoming = reviews.filter(
          (r) => /copilot/i.test(r.reviewer) && !task.review.seenReviewIds.includes(r.id),
        );
        if (incoming.length === 0) {
          const requested = Date.parse(task.review.requestedAt ?? "") || this.options.now();
          if (this.options.now() - requested > this.options.reviewTimeoutMs) {
            return go(
              this.tasks.handoff(
                id,
                "blocked",
                "Copilot review did not arrive in time.",
                "Request the review manually or approve without it.",
              ).status,
            );
          }
          return { status: task.status, progressed: false, waiting: "Copilot review pending" };
        }
        const iteration = task.review.iteration + 1;
        const fresh = findingsFromReviews(incoming, iteration, task.review.findings);
        this.tasks.apply(
          id,
          (t) => {
            t.review.findings.push(...fresh);
            t.review.seenReviewIds.push(...incoming.map((r) => r.id));
            t.review.receivedAt = new Date(this.options.now()).toISOString();
            t.review.iteration = iteration;
          },
          {
            type: "review.received",
            message: `Copilot review received (${fresh.length} new finding(s))`,
          },
        );
        return go(this.tasks.transition(id, "COPILOT_REVIEW_RECEIVED", "Evaluating review").status);
      }

      case "COPILOT_REVIEW_RECEIVED": {
        const unclassified = task.review.findings.filter((f) => !f.resolved && !f.classification);
        if (unclassified.length > 0) {
          const results = await this.ports.classifier.classify({ task, findings: unclassified });
          this.tasks.apply(
            id,
            (t) => {
              for (const r of results) {
                const f = t.review.findings.find((x) => x.id === r.findingId);
                if (f) {
                  f.classification = r.classification;
                  f.rationale = r.rationale;
                  if (r.classification !== "VALID" && r.classification !== "UNCERTAIN")
                    f.resolved = true;
                }
              }
            },
            {
              type: "review.classified",
              message: `Classified ${results.length} finding(s): ${results.map((r) => r.classification).join(", ")}`,
            },
          );
        }
        const decision = decideAfterClassification(
          this.tasks.get(id),
          this.workspace.review.maxReviewIterations,
        );
        if (decision.action === "escalate") {
          return go(
            this.tasks.handoff(
              id,
              "blocked",
              decision.reason,
              "A human needs to decide on the flagged review findings.",
            ).status,
          );
        }
        if (decision.action === "fix") {
          return go(
            this.tasks.transition(
              id,
              "ADDRESSING_REVIEW",
              `Addressing ${decision.findings.length} valid finding(s)`,
            ).status,
          );
        }
        return go(this.tasks.transition(id, "CI_RUNNING", "Review handled; confirming CI").status);
      }

      case "READY_FOR_HUMAN":
      case "COMPLETED":
      case "FAILED":
      case "BLOCKED":
      case "NEEDS_INFORMATION":
      case "NEEDS_APPROVAL":
        return { status: task.status, progressed: false };
    }
  }

  /** After CI passes: re-request review if fixes followed the last one, otherwise hand to a human. */
  private async finishOrReview(id: string): Promise<TaskStatus> {
    const t = this.tasks.get(id);
    const copilot = this.workspace.review.copilot;
    const firstReviewOwed = copilot && !t.review.receivedAt;
    const fixedSinceReview = t.review.findings.some(
      (f) => f.fixedIn && f.iteration === t.review.iteration,
    );
    if (
      copilot &&
      !firstReviewOwed &&
      fixedSinceReview &&
      t.review.iteration < this.workspace.review.maxReviewIterations
    ) {
      this.need("github.pr.comment", undefined, t);
      await this.ports.github.requestCopilotReview(
        this.workspace.github?.repo ?? "",
        t.pullRequest?.number ?? 0,
      );
      this.tasks.apply(
        id,
        (x) => void (x.review.requestedAt = new Date(this.options.now()).toISOString()),
        { type: "review.requested", message: "Re-requested review after fixes" },
      );
      return this.tasks.transition(id, "COPILOT_REVIEW_PENDING", "Fixes pushed; awaiting re-review")
        .status;
    }
    if (firstReviewOwed) {
      return this.tasks.transition(id, "COPILOT_REVIEW_PENDING", "CI passed; awaiting review")
        .status;
    }
    const done = this.tasks.handoff(id, "ready", "Ready for human review.");
    await this.note(
      done,
      `Ready for human review: PR #${t.pullRequest?.number ?? "?"}. CI passing; review findings handled.`,
    );
    return done.status;
  }
}
