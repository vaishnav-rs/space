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

# The phone's Tailscale IPv4 (100.64.0.0/10), read by Node. Empty when Tailscale is not connected.
tailscale_ip() {
  node -e 'for (const list of Object.values(require("os").networkInterfaces() || {})) for (const a of list || []) { const p = a.address.split("."); if (a.family === "IPv4" && p[0] === "100" && p[1] >= 64 && p[1] <= 127) { console.log(a.address); process.exit(0) } }' 2>/dev/null
}
set_bind() { # mode [custom-host]
  node -e '
const fs = require("fs"); const f = process.argv[1]; const c = JSON.parse(fs.readFileSync(f, "utf8"));
c.gateway = { ...c.gateway, bind: process.argv[2] };
if (process.argv[3]) c.gateway.customBindHost = process.argv[3]; else delete c.gateway.customBindHost;
fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");' "$ORION_HOME/openclaw.json" "$1" "${2:-}"
}
reachable() { node -e "fetch('http://$1:$PORT/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" 2>/dev/null; }

tailscale_cmd() {
  case "${1:-status}" in
    on)
      ip="${2:-$(tailscale_ip)}"
      if [ -z "$ip" ]; then
        echo "No Tailscale address found. Open the Tailscale app, connect it, then run: orion tailscale on"
        echo "(If it is connected, give the address from the app: orion tailscale on 100.x.y.z)"; return 1
      fi
      if [ -n "${2:-}" ]; then set_bind custom "$ip"; else set_bind tailnet; fi
      stop; start || return 1
      if reachable "$ip"; then
        echo; echo "Orion is reachable over Tailscale."
        echo "On your laptop or other phone (Tailscale connected), use:"
        echo "  host: $ip   port: $PORT   token: $(cat "$ORION_HOME/token")   (TLS off: Tailscale already encrypts it)"
        echo "Laptop browser: http://$ip:$PORT"
      else
        echo; echo "The gateway is up but does not answer on $ip. If Tailscale connected after the gateway started, run: orion restart"
        echo "Otherwise bind it to the address explicitly: orion tailscale on $ip"; return 1
      fi ;;
    off) set_bind loopback; stop; start; echo "Back to this phone only (127.0.0.1)." ;;
    status)
      ip="$(tailscale_ip)"
      echo "bind: $(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).gateway?.bind ?? "loopback")' "$ORION_HOME/openclaw.json")"
      echo "tailscale address: ${ip:-not connected}"
      if [ -n "$ip" ]; then if reachable "$ip"; then echo "reachable at $ip:$PORT"; else echo "not reachable at $ip:$PORT"; fi; fi ;;
    *) echo "usage: orion tailscale on [100.x.y.z] | off | status" ;;
  esac
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  token) cat "$ORION_HOME/token" ;;
  logs) tail -n "${3:-60}" -f "$ORION_HOME/${2:-gateway}.log" ;;
  cli) shift; load_env; exec node "$APP_DIR/openclaw.mjs" "$@" ;;
  tailscale) shift; tailscale_cmd "$@" ;;
  boot)
    case "${2:-}" in
      on) mkdir -p "$HOME/.termux/boot"; printf '#!/data/data/com.termux/files/usr/bin/sh\nexec orion start\n' >"$HOME/.termux/boot/orion"; chmod +x "$HOME/.termux/boot/orion"; echo "Orion will start after a reboot (needs the Termux:Boot app from F-Droid, opened once)." ;;
      off) rm -f "$HOME/.termux/boot/orion"; echo "Boot start removed." ;;
      *) echo "usage: orion boot on|off" ;;
    esac ;;
  *) echo "usage: orion start|stop|restart|status|token|logs [gateway|model]|cli <openclaw args>|tailscale on|off|status|boot on|off" ;;
esac
