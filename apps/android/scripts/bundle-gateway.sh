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
# Smoke test the staged bundle with the build machine's Node before it is shipped.
node openclaw.mjs --version
# Shrink: type declarations, source maps and desktop-only prebuilds are not used at runtime.
find node_modules \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' \) -type f -delete
if [ -d node_modules/koffi/build/koffi ]; then
  find node_modules/koffi/build/koffi -mindepth 1 -maxdepth 1 ! -name 'android*' -exec rm -rf {} +
fi
tar -czf "$OUT/gateway.bin" -C "$STAGE/pkg" .
sha256sum "$OUT/gateway.bin" | cut -c1-16 > "$OUT/gateway.version"
ls -lh "$OUT/gateway.bin"
