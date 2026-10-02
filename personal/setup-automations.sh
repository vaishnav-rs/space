#!/usr/bin/env bash
# Creates the recurring proactive jobs. Needs a running gateway and OWNER_WHATSAPP (E.164).
set -euo pipefail
: "${OWNER_WHATSAPP:?set OWNER_WHATSAPP, e.g. +15550001111}"
deliver=(--announce --channel whatsapp --to "$OWNER_WHATSAPP")
job() { openclaw automations create "$1" "$2" --name "$3" "${deliver[@]}"; }

job "30 7 * * *"   "Morning brief: today's calendar, unread mail that matters, open PRs and CI, due tasks, and a realistic plan for the day built from my work profile (dev_pulse_profile). Also email it to me with resend_email." "Morning brief"
job "0 13 * * 1-5" "Midday check: what slipped from the morning plan, what to cut or move. Re-estimate remaining work with dev_pulse_estimate." "Midday replan"
job "30 18 * * 1-5" "End-of-day review: what shipped, what is stuck, tomorrow's first task. Log finished work with dev_pulse_log_actual." "Day review"
job "0 9 * * 1"    "Weekly review: how I actually worked last week (dev_pulse_profile), estimate accuracy, risks to current deadlines, and one suggestion to improve my week." "Weekly review"
job "*/20 8-22 * * *" "Calendar sweep: warn me about meetings in the next 30 minutes that I have not prepared for, and conflicts. NO_REPLY if nothing." "Calendar sweep"
