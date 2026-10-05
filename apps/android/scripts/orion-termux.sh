#!/data/data/com.termux/files/usr/bin/bash
# Orion on a phone, one command, inside Termux (the F-Droid build):
#
#   pkg upgrade -y && curl -fsSL https://raw.githubusercontent.com/vaishnav-rs/space/claude/new-work/apps/android/scripts/orion-termux.sh | bash
#   ... | bash -s -- --local-model        also installs and wires a local Gemma 4 E2B model (llama.cpp)
#
# Afterwards: `orion start|stop|status|logs|token|cli|boot`. Re-running updates in place and keeps your data.
set -euo pipefail
REPO="vaishnav-rs/space"
RAW="https://raw.githubusercontent.com/$REPO/claude/new-work/apps/android/scripts"
ORION_HOME="${ORION_HOME:-$HOME/.orion}"
APP_DIR="$HOME/orion"
LOCAL=0; MODEL_ID="gemma-4-e2b"; START=1
for arg in "$@"; do
  case "$arg" in
    --local-model|--local-mode|--local) LOCAL=1 ;;
    --local-model=gemma-4-e2b) LOCAL=1 ;;
    --local-model=*) echo "Unknown local model '${arg#*=}'. Supported: gemma-4-e2b"; exit 2 ;;
    --no-start) START=0 ;;
    *) echo "Unknown option: $arg. Options: --local-model (install Gemma 4 E2B), --no-start"; exit 2 ;;
  esac
done
step() { printf '\n==> %s\n' "$*"; }
[ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] || { echo "This must run inside Termux (install it from F-Droid, not Google Play)."; exit 1; }
[ "$(uname -m)" = "aarch64" ] || { echo "Needs a 64-bit ARM phone (aarch64); this is $(uname -m)."; exit 1; }

step "Installing Node, git and SSH"
pkg install -y nodejs-lts git openssh curl termux-api >/dev/null
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' || { echo "Node 24 or newer is needed (found $(node -v)). Run: pkg upgrade -y"; exit 1; }

step "Finding the latest Orion release"
read -r TGZ_URL TAG < <(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=10" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{for(const r of JSON.parse(s)){const a=(r.assets||[]).find(x=>/^openclaw-.*\.tgz$/.test(x.name));if(a){console.log(a.browser_download_url,r.tag_name);return}}process.exit(1)})') \
  || { echo "Could not find a published Orion release."; exit 1; }
echo "$TAG"

if [ "$(cat "$APP_DIR/.orion-tag" 2>/dev/null || true)" = "$TAG" ]; then
  step "Already on $TAG"
else
  step "Downloading and installing the gateway (a few minutes)"
  WORK="$HOME/.orion-install"; rm -rf "$WORK"; mkdir -p "$WORK/pkg"
  curl -fL --retry 3 -o "$WORK/gateway.tgz" "$TGZ_URL"
  tar -xzf "$WORK/gateway.tgz" -C "$WORK/pkg" --strip-components=1
  (cd "$WORK/pkg" && npm install --omit=dev --omit=optional --ignore-scripts --legacy-peer-deps --no-audit --no-fund >/dev/null)
  # Android refuses hard links; make the gateway's file-safety layer rename instead (see the script).
  curl -fsSL "$RAW/patch-fs-safe.mjs" -o "$WORK/patch-fs-safe.mjs"
  node "$WORK/patch-fs-safe.mjs" "$WORK/pkg"
  echo "$TAG" >"$WORK/pkg/.orion-tag"
  "$PREFIX/bin/orion" stop >/dev/null 2>&1 || true
  rm -rf "$APP_DIR.old"; [ -d "$APP_DIR" ] && mv "$APP_DIR" "$APP_DIR.old"
  mv "$WORK/pkg" "$APP_DIR"; rm -rf "$WORK" "$APP_DIR.old"
fi

step "Setting up your data folder"
mkdir -p "$ORION_HOME/state" "$ORION_HOME/workspaces/agent" "$ORION_HOME/orion-state"
chmod 700 "$ORION_HOME"
[ -f "$ORION_HOME/token" ] || { head -c 24 /dev/urandom | base64 | tr -d '/+=' >"$ORION_HOME/token"; chmod 600 "$ORION_HOME/token"; }
curl -fsSL "$RAW/termux-config.mjs" -o "$ORION_HOME/termux-config.mjs"
CFG_ARGS=()
if [ "$LOCAL" = 1 ]; then
  CTX="${ORION_LOCAL_CTX:-32768}"
  step "Installing the local model runtime (llama.cpp)"
  pkg install -y llama-cpp >/dev/null
  command -v llama-server >/dev/null || { echo "llama-server is missing after installing llama-cpp."; exit 1; }
  MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
  [ "$MEM_MB" -ge 6000 ] || echo "Warning: this phone has about ${MEM_MB} MB of RAM. A local model may be slow or get killed; 8 GB or more is recommended."
  MODEL_FILE="$ORION_HOME/models/gemma-4-E2B-it-Q4_0.gguf"
  mkdir -p "$ORION_HOME/models"
  if [ ! -s "$MODEL_FILE" ]; then
    step "Downloading Gemma 4 E2B (several GB; resumes if interrupted)"
    curl -fL -C - --retry 10 --retry-delay 3 -o "$MODEL_FILE.part" "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf"
    mv "$MODEL_FILE.part" "$MODEL_FILE"
  fi
  echo "$MODEL_FILE" >"$ORION_HOME/model.path"; echo "$MODEL_ID" >"$ORION_HOME/model.id"
  CFG_ARGS=(--local-model "$MODEL_ID" "Gemma 4 E2B (on this phone)" "$CTX")
fi
node "$ORION_HOME/termux-config.mjs" "$ORION_HOME/openclaw.json" "$ORION_HOME/workspaces/agent" "${CFG_ARGS[@]}"

step "Installing the orion command"
curl -fsSL "$RAW/orion-helper.sh" -o "$PREFIX/bin/orion"; chmod +x "$PREFIX/bin/orion"

if [ "$START" = 1 ]; then step "Starting Orion"; orion start; fi
cat <<MSG

Done. Connect the Orion app on this phone:
  Set up manually  ->  host 127.0.0.1   port 18790   TLS off
  token: $(cat "$ORION_HOME/token")

Commands:  orion status | stop | start | logs | token | cli <args> | boot on
$( [ "$LOCAL" = 1 ] && echo "Local model: Gemma 4 E2B is the default model. 'orion logs model' shows its log." || echo "Add an AI provider in the app (Settings -> Providers & Models), or re-run with --local-model." )
MSG
