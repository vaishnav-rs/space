// Preloaded into the on-device gateway (NODE_OPTIONS=--require). Android restricts a few system calls
// that Node exposes without a fallback; this makes them fail soft instead of crashing start-up.
// Every wrapper only changes behavior when the original call throws.
const os = require("node:os");

function soft(name, fallback) {
  const original = os[name];
  if (typeof original !== "function") return;
  os[name] = function (...args) {
    try {
      return original.apply(this, args);
    } catch {
      return typeof fallback === "function" ? fallback() : fallback;
    }
  };
}

// Android 11+ blocks the netlink call behind networkInterfaces().
soft("networkInterfaces", () => ({
  lo: [{ address: "127.0.0.1", netmask: "255.0.0.0", family: "IPv4", mac: "00:00:00:00:00:00", internal: true, cidr: "127.0.0.1/8" }],
}));

// App UIDs have no passwd entry on some devices.
soft("userInfo", () => ({
  uid: typeof process.getuid === "function" ? process.getuid() : 0,
  gid: typeof process.getgid === "function" ? process.getgid() : 0,
  username: process.env.USER || "orion",
  homedir: process.env.HOME || "/",
  shell: null,
}));

// /proc may be partly hidden; keep concurrency math sane.
soft("cpus", () => Array.from({ length: 4 }, () => ({ model: "arm64", speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } })));
soft("hostname", "orion-phone");
