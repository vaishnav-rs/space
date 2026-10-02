---
summary: "Orion for teams: roles, private-by-default chats, explicit sharing, and org vs personal connectors"
title: "Orion for teams"
---

Orion builds on OpenClaw's multi-user gateway rather than replacing it. What already exists there, what Orion adds, and how they fit:

## Already in OpenClaw (use it as is)

- **People and sign-in.** Per-person gateway profiles via Tailscale, a trusted proxy, or GitHub identity ([Team setup](/start/teams), [Team server](/gateway/team-server)).
- **Named operator roles.** `gateway.roles` sets what each person may do to _other people's_ sessions (`none`, `view`, `suggest`, `write`), which agents they can use, their maximum scopes, and whether runs must be sandboxed.
- **Private chats and explicit sharing.** Set `sessions.others: "none"` and a chat is visible only to its creator and assigned owner. Sharing is a deliberate act in the session menu (Shared, Read-only, Suggest, Draft). A public link is a separate, creator-only opt-in.
- **Personal accounts.** Per-person model accounts and per-person GitHub connections, stored under that person's profile.

`personal/team/gateway-roles.example.json5` is a starting `gateway.roles` that matches Orion's roles and makes every chat private by default.

## What Orion adds

Enabled by setting `ORION_STATE_DIR` and `ORION_VAULT_KEY` (a base64 32-byte key). Without them Orion stays single-user and uses environment credentials.

**Roles** (`owner`, `admin`, `engineer`, `member`, `viewer`) control Orion's own capabilities: personal tools, starting and steering engineering tasks, approving gated capabilities, managing connectors and members. Only an owner can create admins; admins cannot touch owners or other admins; the last owner cannot be demoted; strangers default to `viewer`. Engineers and below reach only the workspaces they were added to; admins reach all.

**Connectors scoped to the organization or to one person.**

| Connector                       | Org-wide | Personal  | Notes                                                                                                                                                                                           |
| ------------------------------- | -------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| WhatsApp                        | yes      | **never** | Organization-only by design. The gateway links one WhatsApp account through its own channel setup; Orion records it as the organization's and refuses personal connections, even from an admin. |
| GitHub                          | yes      | yes       | Orion's engineering identity (a GitHub App or machine user) is org-level; people can also connect their own.                                                                                    |
| Google (Gmail, Calendar, Tasks) | yes      | yes       | Personal by default: a person's mailbox is never used for someone else, and never falls back to the org's unless an admin allows it.                                                            |
| Resend                          | yes      | yes       | Org sender by default; personal allowed.                                                                                                                                                        |

A person's own connection always wins. Admins can switch personal connections off for a connector and decide whether people without their own may use the organization's. Nobody, including admins, can connect a personal account on someone else's behalf; admins can revoke one (for example when someone leaves). Credentials are AES-256-GCM encrypted at rest and bound to their slot, so a copied ciphertext does not work under another user. Listings and the audit log never contain secrets.

**Each person acts as themselves.** The Gmail, Calendar, Tasks and Resend tools resolve the credentials of the signed-in person for that turn. The outbound guard's idea of "me" is that person's own addresses (`addresses` in their member record), so a proactive message can only ever go to the person it is for. Scheduled turns with no signed-in person use `ORION_DEFAULT_REQUESTER`.

**Tasks follow the same privacy.** A task started from chat is private to its creator until they share it (`orion_task share`); admins see all. A task that arrives from GitHub is team work: visible to members of that workspace who may see team tasks. A person without access gets the same "not found" as for a task that does not exist. GitHub mentions must map to a member (via their GitHub login) whose role can start tasks and who has the workspace, replacing the manifest's static `authorizedUsers` list.

## Administering it

### The admin screen

Open **Settings → Privacy & Security → Orion admin** (`/settings/orion`). The same page serves everyone; what you see follows your Orion role.

- **Overview**: who you are, what your role allows, health of the vault, GitHub identity, webhook secret and engineering runtime, and tasks that need attention.
- **People** (admins): change roles, workspaces, GitHub login and a person's own addresses; add or remove people. First visit on a new deployment offers _Claim ownership_.
- **Connections**: every connector with its organization and personal state. Admins connect or replace org connections, turn personal connections on or off per connector, decide whether people without their own may use the organization's, and revoke an individual's connection. Everyone connects or disconnects their own. Credential fields are password inputs that go straight to the gateway's vault and are never shown again. WhatsApp shows as organization-only.
- **Tasks**: the tasks you may see, with timeline summary and PR link; stop, retry, approve a gated capability, or share (according to role).
- **Workspaces** and **Activity** (admins): each workspace's grants, approval-gated capabilities and members; the connector audit log.

The page talks to two gateway methods, `orion.admin.overview` (operator.read) and `orion.admin.apply` (operator.write); Orion roles then decide what each person may actually do. The CLI below does the same things from the host.

```bash
export ORION_STATE_DIR=~/.orion ORION_VAULT_KEY=$(openssl rand -base64 32)   # keep the key safe
node --import ./scripts/tsx.mjs scripts/orion-admin.mts bootstrap-owner you
node --import ./scripts/tsx.mjs scripts/orion-admin.mts members set alice --as you --role engineer --workspaces hewar --github alice-gh --address alice@acme.com
node --import ./scripts/tsx.mjs scripts/orion-admin.mts connect whatsapp --scope org --as you          # prompts for the number label
node --import ./scripts/tsx.mjs scripts/orion-admin.mts connect google --scope user --as alice         # prompts for client id/secret/refresh token (hidden)
node --import ./scripts/tsx.mjs scripts/orion-admin.mts policy google --as you --org-fallback off
node --import ./scripts/tsx.mjs scripts/orion-admin.mts connectors --as you
node --import ./scripts/tsx.mjs scripts/orion-admin.mts audit
```

Secrets are only ever typed at a hidden prompt (or piped on stdin), never passed as arguments or through chat.

## Limits

- **Admin screen is English-only** and was checked against the mocked Control UI, not a live multi-person gateway. The CLI runs on the gateway host, so whoever holds the host and the vault key is the root of trust.
- **One trust domain.** As in OpenClaw, this is role-based access inside one gateway, not hostile multi-tenant isolation. Mutually untrusting organizations need separate gateways.
- **Gateway link.** Roles for Orion and `gateway.roles` are configured separately; keep them aligned (the example does).
- **Not tested live.** The access layer is covered by unit tests, including the CLI path; no real multi-person gateway run has been done.
