---
summary: "Space architecture: one runtime, personal and project workspaces, durable maintenance tasks"
title: "Space architecture"
read_when:
  - Working on the Space maintenance engine, workspaces, or policy
---

Space is OpenClaw plus a personal layer (`src/personal/`) and an engineering layer (`src/space/`). One runtime serves both. The personal assistant is the default; a project workspace activates on an explicit selection, a GitHub event, or a project name in the request (`workspace-routing.ts`).

## Components

| Concern             | Owner                                                                                                       | Notes                                                                                                                                                                                                                                                                                                                                                                |
| ------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Workspace manifests | `workspace.ts`                                                                                              | Zod-validated. Project commands, knowledge sources, GitHub block, production block, review policy, capability grants. Nothing project-specific is hard-coded elsewhere.                                                                                                                                                                                              |
| Policy engine       | `capabilities.ts`, `policy.ts`                                                                              | Capability vocabulary; `decide()` returns allow / needs_approval / deny. Production observation (`prod.read.*`) and mutation (`prod.exec/restart/deploy/database.write`) are separate; mutation can never be granted outright, only approval-gated, and the manifest loader rejects a grant. Every adapter and runner step calls `enforce()` before the side effect. |
| Events              | `events.ts`, `router.ts`, `webhook.ts`                                                                      | Signed GitHub webhook → normalized `SpaceEvent` → router. Authorization happens before a task exists. Bots, quoted/code-fenced mentions, duplicate deliveries and unknown repos are ignored.                                                                                                                                                                         |
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

Not yet bound (reported as `unavailable`, tasks block with the exact reason rather than faking progress):

- **Agent runtime binding.** `AgentPort` (investigate, reproduce, diagnose, fix, self-review, PR text) and `FindingClassifier` need an implementation that runs OpenClaw agent turns inside the task's worktree. Until then `createUnavailableAgent()` / `createUnavailableClassifier()` are used.
- **Webhook mount.** `SpaceRuntime.webhook(rawBody, headers)` is transport-agnostic; it still needs to be mounted on a gateway HTTP route (or an existing hook mapping).
- **Scheduler.** Call `SpaceRuntime.tick()` on boot and from an automation job to resume and poll CI/review.
- **Graphify export format.** `createGraphKnowledge` assumes `{nodes, edges}` JSON; adjust to the real export.
- **Persistence location.** Tasks live in their own SQLite file (`space-tasks.sqlite`). The repository's storage policy prefers the shared state database via its migration owner; moving there needs a schema-version review.
- **Production mutation.** Not implemented. Only observation exists; mutation capabilities are approval-gated placeholders.

## Configuration

Environment: `SPACE_STATE_DIR`, `SPACE_WORKSPACES_DIR` (directory of manifest `*.json`), `SPACE_GITHUB_TOKEN`, `SPACE_GITHUB_WEBHOOK_SECRET`. See `personal/workspaces/hewar.example.json`.
