import { spawn } from "node:child_process";
import type { ShellPort } from "./ports.js";
import { redactSecrets, tail } from "./redact.js";

/** Only these variables reach project commands; tokens, SSH agents and cloud creds do not. */
const ENV_ALLOWLIST = [
  "PATH",
  "HOME",
  "LANG",
  "LC_ALL",
  "TERM",
  "TMPDIR",
  "CI",
  "NODE_ENV",
  "SHELL",
  "USER",
];

function scrubbedEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { CI: "1" };
  for (const k of ENV_ALLOWLIST) {
    const v = process.env[k];
    if (v !== undefined) {
      env[k] = v;
    }
  }
  return env;
}

export function createShellAdapter(): ShellPort {
  return {
    integration: "real",
    name: "shell",
    run({ cwd, command, timeoutMs }) {
      return new Promise((resolve) => {
        const child = spawn("sh", ["-c", command], {
          cwd,
          env: scrubbedEnv(),
          stdio: ["ignore", "pipe", "pipe"],
        });
        let out = "";
        const onData = (d: Buffer) => {
          out = tail(out + d.toString("utf8"), 64_000);
        };
        child.stdout.on("data", onData);
        child.stderr.on("data", onData);
        const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
        child.on("close", (code, signal) => {
          clearTimeout(timer);
          resolve({ exitCode: signal ? 124 : (code ?? 1), output: redactSecrets(tail(out)) });
        });
        child.on("error", (err) => {
          clearTimeout(timer);
          resolve({ exitCode: 127, output: redactSecrets(String(err)) });
        });
      });
    },
  };
}
