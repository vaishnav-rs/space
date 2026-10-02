---
summary: "Which accounts and credentials Orion should have, and why they are separate from yours"
title: "Orion identity and access"
---

Yes: give Orion its own identity. Using your personal account means every commit, PR, comment and push is you, a leaked token is your whole account, and you cannot restrict it without restricting yourself.

## GitHub

Preferred: a **GitHub App** installed on the Hewar repository only. Otherwise a dedicated **machine user** (for example `hewar-agent`; this is also what makes the `@hewar-agent` mention real).

- Permissions: contents read/write, pull requests read/write, issues read/write, checks read. No admin, no workflows write, no secrets.
- Branch protection on `main`: require PR and review, block direct pushes. Orion only pushes `agent/*` branches (the adapter enforces this too, but the platform rule is the real boundary).
- `authorizedUsers` in the workspace manifest lists the humans allowed to invoke Orion; the agent account itself does not belong there.
- Webhook: `POST https://<gateway>/orion/github/webhook`, content type JSON, a random secret in `ORION_GITHUB_WEBHOOK_SECRET`, events: issues, issue comments, pull requests, pull request reviews, check runs.
- Token in `ORION_GITHUB_TOKEN`. Requesting a Copilot review through the API needs the requesting identity to be allowed to; this has not been verified against the live API.

## Production (SSH)

A dedicated, **read-only** user and key for Orion, never your own key.

- Member of `systemd-journal` (and similar read groups) so it can read logs and service status; no sudo.
- Key stored in the Orion host's ssh config under the `sshHost` alias used by the workspace manifest; host key pinned (`StrictHostKeyChecking=yes`).
- Production mutation capabilities stay approval-gated and unimplemented; do not give this user write access "just in case".

## The Orion host

Run the gateway under its own low-privilege OS account. The agent's shell tool inherits that account's environment, so it should hold only Orion's credentials, not yours. Prefer OpenClaw's sandbox for agent runs before connecting production-capable credentials.

## What stays yours

Your Gmail, Calendar and Tasks OAuth tokens (personal assistant) and WhatsApp session are separate from Orion's engineering identity. Engineering turns get no access to the personal tools.
