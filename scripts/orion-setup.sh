#!/usr/bin/env bash
# One command: installs deps, provisions Orion, starts the gateway, creates the proactive jobs,
# and prints the app pairing code.  ./scripts/orion-setup.sh --owner you --email you@x.com --whatsapp +1555...
set -euo pipefail
cd "$(dirname "$0")/.."
command -v node >/dev/null || { echo "Node 24+ is required"; exit 1; }
command -v pnpm >/dev/null || corepack enable
[ -d node_modules ] || pnpm install
node --import ./scripts/tsx.mjs scripts/orion-setup.mts "$@"
HOME_DIR="${ORION_HOME:-$HOME/.orion}"
for ((i=1;i<=$#;i++)); do [ "${!i}" = "--home" ] && j=$((i+1)) && HOME_DIR="${!j}"; done
set -a; . "$HOME_DIR/orion.env"; set +a
nohup "$HOME_DIR/start.sh" >"$HOME_DIR/gateway.log" 2>&1 &
for _ in $(seq 1 60); do pnpm -s openclaw gateway health >/dev/null 2>&1 && break; sleep 2; done
./personal/setup-automations.sh || echo "automations: re-run personal/setup-automations.sh once the gateway is healthy"
pnpm -s openclaw qr || true
echo "Gateway log: $HOME_DIR/gateway.log   Restart: $HOME_DIR/start.sh"
