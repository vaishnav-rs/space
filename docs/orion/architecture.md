---
summary: "Orion architecture: one runtime, personal and project workspaces, durable maintenance tasks"
title: "Orion architecture"
read_when:
  - Working on the Orion maintenance engine, workspaces, or policy
---

Orion is OpenClaw plus a personal layer (`src/personal/`) and an engineering layer (`src/orion/`). One runtime serves both. The personal assistant is the default; a project workspace activates on an explicit selection, a GitHub event, or a project name in the request (`workspace-routing.ts`).

## Components

| Concern             | Owner                                                                                                       | Notes                                                                                                                                                                                                                                                                                                                                                                |
| ------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Workspace manifests | `workspace.ts`                                                                                              | Zod-validated. Project commands, knowledge sources, GitHub block, production block, review policy, capability grants. Nothing project-specific is hard-coded elsewhere.                                                                                                                                                                                              |
| Policy engine       | `capabilities.ts`, `policy.ts`                                                                              | Capability vocabulary; `decide()` returns allow / needs_approval / deny. Production observation (`prod.read.*`) and mutation (`prod.exec/restart/deploy/database.write`) are separate; mutation can never be granted outright, only approval-gated, and the manifest loader rejects a grant. Every adapter and runner step calls `enforce()` before the side effect. |
| Events              | `events.ts`, `router.ts`, `webhook.ts`                                                                      | Signed GitHub webhook → normalized `OrionEvent` → router. Authorization happens before a task exists. Bots, quoted/code-fenced mentions, duplicate deliveries and unknown repos are ignored.                                                                                                                                                                         |
| Task state          | `task-types.ts`, `task-machine.ts`, `task-store.ts`, `task-service.ts`                                      | `MaintenanceTask` snapshot plus append-only event log in SQLite; optimistic version check; one writer (`TaskService`). `READY_FOR_HUMAN` is only reachable when the completion policy is met.                                                                                                                                                                        |
| Runner              | `runner.ts`                                                                                                 | `step()` reads persisted state and advances one transition, so it resumes after any restart. Bounded retries, bounded fix attempts, bounded review iterations.                                                                                                                                                                                                       |
| Ports               | `ports.ts`                                                                                                  | Every external dependency declares `real`, `mock`, `stub` or `unavailable`. The runner refuses mock/stub ports outside tests.                                                                                                                                                                                                                                        |
| Adapters            | `git-adapter.ts`, `github-adapter.ts`, `production-adapter.ts`, `shell-adapter.ts`, `knowledge-adapters.ts` | Real. Git writes only inside agent worktrees, never to the default or a non-agent branch, never force-pushes, never reuses an existing branch. Production uses fixed command templates over validated values and redacts output. Shell commands come from the manifest, with a scrubbed environment.                                                                 |
| Context             | `context-engine.ts`                                                                                         | Graph first, then wiki/docs/code with terms widened by the graph. Budgeted, explained, contradiction-aware, every item wrapped as untrusted.                                                                                                                                                                                                                         |
| Untrusted content   | `untrusted.ts`                                                                                              | Reuses `src/security/external-content.ts` for issues, comments, reviews, docs, code and logs.                                                                                                                                                                                                                                                                        |
| Review loop         | `review-loop.ts`                                                                                            | Findings classified `VALID / FALSE_POSITIVE / ALREADY_ADDRESSED / OUT_OF_SCOPE / UNCERTAIN`. Uncertain or over-the-cap findings go to a human.                                                                                                                                                                                                                       |
| Memory scopes       | `memory-scope.ts`                                                                                           | personal / project / task / conversation partitions with explicit read rules.                                                                                                                                                                                                                                                                                        |

## Lifecycle

`REPORTED → INVESTIGATING → (REPRODUCING) → DIAGNOSING → FIXING → TESTING → SELF_REVIEW → PR_OPEN → COPILOT_REVIEW_PENDING → COPILOT_REVIEW_RECEIVED → (ADDRESSING_REVIEW → TESTING → …) → CI_RUNNING → READY_FOR_HUMAN`. `NEEDS_INFORMATION`, `NEEDS_APPROVAL`, `BLOCKED` and `FAILED` are reachable from any working state and always carry a message that says what is needed.

Definition of done (`evaluateCompletion`): report recorded, problem understood, reproduced or diagnosed, fix committed, targeted and regression tests pass, no unresolved failing validation, self-review passed, PR created, CI passing, external review evaluated with no open findings.

## What is real and what is not

Real and tested against a real git repository: workspaces, policy, task engine, runner, git/shell/production/GitHub/knowledge adapters, webhook handling, status and timeline, resume after restart.

Bound to OpenClaw but **not exercised live in this repository's tests** (the tests drive the same code with a scripted model turn):

- **Agent turns** (`openclaw-agent.ts`, `agent-turn-runner.ts`): each step is one system-ingress OpenClaw run in its own session, `cwd` = the task worktree, no message tool, not owner-authored, tool allowlist `group:fs` (+ `group:runtime` for steps that must run code; none for self-review and PR text). Output is JSON-validated with one repair attempt; certainty without evidence is downgraded; the review classifier escalates anything it skips.
- **Webhook**: `POST /orion/github/webhook` is a gateway HTTP stage (`http.ts`), authenticated by HMAC signature, inert unless Orion is configured.
- **Scheduler**: started at gateway startup (`startOrionScheduler`); resumes unfinished tasks immediately, then polls every 60 s for CI and review state.
- **Chat entry**: the assistant's `orion_task` tool (`create`, `list`, `status`, `timeline`, `stop`, `retry`, `approve`). Mutations require an owner-initiated turn.

Still incomplete or risky:

- **Agent shell environment.** `group:runtime` lets a model turn run shell commands in the worktree with the gateway's environment. Run Orion under a dedicated low-privilege account and use OpenClaw's sandbox for these runs before pointing it at production-connected credentials.
- **Live proof.** No run against a real model, the real GitHub API, Copilot review or a real SSH host has been made. Graphify's export format is an assumption.
- **Persistence location.** Tasks live in `orion-tasks.sqlite`; the repository's storage policy prefers the shared state database via its migration owner.
- **Production mutation.** Not implemented; only observation exists.
- **Stale worktrees.** Worktrees for tasks that end in `NEEDS_INFORMATION` are not garbage-collected yet.
- **OpenClaw-maintainer profile.** Classified in `openclaw-machinery-audit.md`, not yet switchable.

## Configuration

Environment: `ORION_STATE_DIR`, `ORION_WORKSPACES_DIR` (directory of manifest `*.json`), `ORION_GITHUB_TOKEN`, `ORION_GITHUB_WEBHOOK_SECRET`. See `personal/workspaces/hewar.example.json`.
