#!/usr/bin/env bash
# Packs the Orion gateway for the on-device runtime: apps/android/app/src/main/assets/orion-runtime/gateway.bin
# Needs Node 24 and pnpm. Run scripts/fetch-termux-runtime.mjs as well, then build the app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="$ROOT/apps/android/app/src/main/assets/orion-runtime"
STAGE="$ROOT/apps/android/build/gateway-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/pkg" "$OUT"
cd "$ROOT"
pnpm install --no-frozen-lockfile
# The repository's own self-contained package command (builds, bundles @openclaw/ai, packs).
node scripts/package-openclaw-for-docker.mjs --allow-unreleased-changelog --output-dir "$STAGE"
tar -xzf "$STAGE"/openclaw-*.tgz -C "$STAGE/pkg" --strip-components=1
cd "$STAGE/pkg"
# Production dependencies only. Native add-ons prebuilt for desktop Linux are useless on Android, so
# no install scripts and no optional packages (sqlite-vec, platform PTY binaries).
npm install --omit=dev --omit=optional --ignore-scripts --legacy-peer-deps --no-audit --no-fund
mkdir -p personal
cp -r "$ROOT/personal/workspace" personal/workspace
cp "$ROOT/apps/android/runtime/android-shim.cjs" android-shim.cjs
# Smoke test the staged bundle with the build machine's Node before it is shipped.
node openclaw.mjs --version
# Start the gateway exactly as the phone does (same config shape, same environment variables, the Android
# shim preloaded) and require it to listen. A start-up failure here would also happen on the phone, and its
# log shows up in the CI output instead of on a device.
SMOKE="$STAGE/smoke"
rm -rf "$SMOKE"
mkdir -p "$SMOKE"/home "$SMOKE"/tmp "$SMOKE"/state "$SMOKE"/openclaw "$SMOKE"/workspaces "$SMOKE"/agent-workspace
sed "s#/data/orion/agent-workspace#$SMOKE/agent-workspace#" "$ROOT/apps/android/app/src/test/resources/gateway-config.sample.json" > "$SMOKE/openclaw.json"
echo '{"id":"personal","name":"Personal","kind":"personal","policy":{"grant":["filesystem.read","knowledge.read"],"requireApproval":[]}}' > "$SMOKE/workspaces/personal.json"
PORT=18789
env -i PATH="$PATH" HOME="$SMOKE/home" TMPDIR="$SMOKE/tmp" \
  NODE_OPTIONS="--require=$STAGE/pkg/android-shim.cjs" \
  OPENCLAW_STATE_DIR="$SMOKE/openclaw" OPENCLAW_CONFIG_PATH="$SMOKE/openclaw.json" OPENCLAW_GATEWAY_TOKEN=smoke-token \
  ORION_STATE_DIR="$SMOKE/state" ORION_WORKSPACES_DIR="$SMOKE/workspaces" ORION_VAULT_KEY="$(openssl rand -base64 32)" \
  ORION_DEFAULT_REQUESTER=owner OPENCLAW_DEBUG=1 \
  node openclaw.mjs gateway run --port "$PORT" > "$SMOKE/gateway.log" 2>&1 &
GW=$!
UP=0
for _ in $(seq 1 90); do
  if ! kill -0 "$GW" 2>/dev/null; then break; fi
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then UP=1; break; fi
  sleep 2
done
if [ "$UP" != 1 ]; then
  echo "::error::The gateway did not start listening in the smoke test. Log:"
  cat "$SMOKE/gateway.log"
  kill "$GW" 2>/dev/null || true
  exit 1
fi
echo "Gateway smoke test: listening on $PORT"
tail -n 20 "$SMOKE/gateway.log"
kill "$GW" 2>/dev/null || true
wait "$GW" 2>/dev/null || true
rm -rf "$SMOKE"
# Shrink: type declarations, source maps and desktop-only prebuilds are not used at runtime.
find node_modules \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' \) -type f -delete
if [ -d node_modules/koffi/build/koffi ]; then
  find node_modules/koffi/build/koffi -mindepth 1 -maxdepth 1 ! -name 'android*' -exec rm -rf {} +
fi
tar -czf "$OUT/gateway.bin" -C "$STAGE/pkg" .
sha256sum "$OUT/gateway.bin" | cut -c1-16 > "$OUT/gateway.version"
ls -lh "$OUT/gateway.bin"
