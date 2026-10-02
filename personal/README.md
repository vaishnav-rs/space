# Personal assistant layer

Built into the core (`src/personal/`), not a plugin:

| Capability | Tools |
|---|---|
| Gmail | `gmail_search`, `gmail_read`, `gmail_draft`, `gmail_send` |
| Calendar | `calendar_list`, `calendar_create` |
| Google Tasks | `tasks_list`, `tasks_add`, `tasks_complete` |
| Email to you | `resend_email` |
| Dev analytics | `dev_pulse_profile`, `dev_pulse_estimate`, `dev_pulse_log_actual` |
| Outbound guard | wraps `message`, `conversations_send`, `conversations_turn`, `gmail_send`, `resend_email` |

Outbound guard: you and only you are contacted autonomously. Anyone else requires you to ask in that turn; heartbeat, cron, and third-party-triggered turns are blocked.

## Setup

1. `cp .env.example .env`, fill it in, export it for the gateway.
2. Merge `openclaw.personal.json5` into your config; copy `workspace/*` into your agent workspace.
3. Link WhatsApp (`openclaw onboard`). OpenClaw's WhatsApp channel uses WhatsApp Web, which is unofficial.
4. `./setup-automations.sh` (jobs run in the main session; the heartbeat delivers to `commands.ownerAllowFrom`)

## Today panel
The agent keeps a `today` dashboard tab (split view beside chat) via the built-in `dashboard` tool; see `workspace/SOUL.md`. No extra UI code.

## Not covered yet
`conversations_send` and `conversations_turn` are guarded (owner-requested turns only). `sessions_send` talks to your own agent sessions and is left open.
