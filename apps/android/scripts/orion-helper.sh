#!/data/data/com.termux/files/usr/bin/bash
# `orion`: start, stop and inspect the Orion gateway (and the local model, when installed) on this phone.
set -uo pipefail
ORION_HOME="${ORION_HOME:-$HOME/.orion}"
APP_DIR="${ORION_APP_DIR:-$HOME/orion}"
PORT="${ORION_PORT:-18790}"
MODEL_PORT="${ORION_MODEL_PORT:-18791}"
GW_PID="$ORION_HOME/gateway.pid"
LLM_PID="$ORION_HOME/model.pid"

load_env() {
  export OPENCLAW_STATE_DIR="$ORION_HOME/state"
  export OPENCLAW_CONFIG_PATH="$ORION_HOME/openclaw.json"
  export OPENCLAW_GATEWAY_TOKEN="$(cat "$ORION_HOME/token")"
  export ORION_STATE_DIR="$ORION_HOME/orion-state"
  export ORION_WORKSPACES_DIR="$ORION_HOME/workspaces"
  [ -f "$ORION_HOME/env" ] && . "$ORION_HOME/env"
}
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
healthy() { node -e "fetch('http://127.0.0.1:$PORT/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" 2>/dev/null; }

start() {
  [ -f "$ORION_HOME/token" ] || { echo "Not installed. Run the Orion setup command first."; exit 1; }
  load_env
  command -v termux-wake-lock >/dev/null && termux-wake-lock
  if [ -f "$ORION_HOME/model.path" ] && ! alive "$LLM_PID"; then
    echo "Starting the local model…"
    nohup llama-server -m "$(cat "$ORION_HOME/model.path")" --alias "$(cat "$ORION_HOME/model.id")" \
      --host 127.0.0.1 --port "$MODEL_PORT" -c "${ORION_LOCAL_CTX:-32768}" --jinja -t "${ORION_LOCAL_THREADS:-4}" \
      >"$ORION_HOME/model.log" 2>&1 &
    echo $! >"$LLM_PID"
    sleep 3
    if ! alive "$LLM_PID"; then
      rm -f "$LLM_PID"
      echo "The local model failed to start. Its log:"; tail -n 15 "$ORION_HOME/model.log"
      echo "(Orion still starts; replies will fail until the model runs. Fix it, then: orion restart)"
    fi
  fi
  if alive "$GW_PID"; then echo "The gateway is already running."; else
    mkdir -p "$ORION_HOME"
    # cd first and background node itself, so the recorded pid is the gateway and not a wrapper shell.
    (cd "$APP_DIR" || exit 1; nohup node openclaw.mjs gateway run --port "$PORT" >"$ORION_HOME/gateway.log" 2>&1 & echo $! >"$GW_PID")
  fi
  printf "Waiting for the gateway"
  for _ in $(seq 1 90); do
    if healthy; then echo; echo "Orion is running on 127.0.0.1:$PORT"; return 0; fi
    alive "$GW_PID" || { echo; echo "The gateway stopped. Last log lines:"; tail -n 25 "$ORION_HOME/gateway.log"; return 1; }
    printf "."; sleep 2
  done
  echo; echo "Still starting after 3 minutes. Check: orion logs"
}

stop() {
  for pidfile in "$GW_PID" "$LLM_PID"; do
    if alive "$pidfile"; then
      pid="$(cat "$pidfile")"; kill "$pid" 2>/dev/null
      for _ in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -9 "$pid" 2>/dev/null
    fi
    rm -f "$pidfile"
  done
  pkill -f sqlite-readonly-location.worker 2>/dev/null
  echo "Stopped."
}

status() {
  if alive "$GW_PID"; then echo "gateway: running (pid $(cat "$GW_PID"))"; else echo "gateway: stopped"; fi
  if healthy; then echo "health:  ok (http://127.0.0.1:$PORT)"; else echo "health:  not answering"; fi
  if [ -f "$ORION_HOME/model.path" ]; then
    if alive "$LLM_PID"; then echo "model:   running ($(cat "$ORION_HOME/model.id"))"; else echo "model:   stopped"; fi
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  token) cat "$ORION_HOME/token" ;;
  logs) tail -n "${3:-60}" -f "$ORION_HOME/${2:-gateway}.log" ;;
  cli) shift; load_env; exec node "$APP_DIR/openclaw.mjs" "$@" ;;
  boot)
    case "${2:-}" in
      on) mkdir -p "$HOME/.termux/boot"; printf '#!/data/data/com.termux/files/usr/bin/sh\nexec orion start\n' >"$HOME/.termux/boot/orion"; chmod +x "$HOME/.termux/boot/orion"; echo "Orion will start after a reboot (needs the Termux:Boot app from F-Droid, opened once)." ;;
      off) rm -f "$HOME/.termux/boot/orion"; echo "Boot start removed." ;;
      *) echo "usage: orion boot on|off" ;;
    esac ;;
  *) echo "usage: orion start|stop|restart|status|token|logs [gateway|model]|cli <openclaw args>|boot on|off" ;;
esac
