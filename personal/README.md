# Personal assistant layer

Built into the core (`src/personal/`), not a plugin:

| Capability | Tools |
|---|---|
| Gmail | `gmail_search`, `gmail_read`, `gmail_draft`, `gmail_send` |
| Calendar | `calendar_list`, `calendar_create` |
| Google Tasks | `tasks_list`, `tasks_add`, `tasks_complete` |
| Email to you | `resend_email` |
| Dev analytics | `dev_pulse_profile`, `dev_pulse_estimate`, `dev_pulse_log_actual` |
| Outbound guard | wraps `message`, `gmail_send`, `resend_email` |

Outbound guard: you and only you are contacted autonomously. Anyone else requires you to ask in that turn; heartbeat, cron, and third-party-triggered turns are blocked.

## Setup

1. `cp .env.example .env`, fill it in, export it for the gateway.
2. Merge `openclaw.personal.json5` into your config; copy `workspace/*` into your agent workspace.
3. Link WhatsApp (`openclaw onboard`). OpenClaw's WhatsApp channel uses WhatsApp Web, which is unofficial.
4. `OWNER_WHATSAPP=+1... ./setup-automations.sh`

## Not covered yet
`sessions_send` and `conversations_send` can also reach other agents/people and are not behind the guard.
