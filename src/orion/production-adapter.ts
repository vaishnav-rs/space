import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { enforce, type Actor } from "./policy.js";
import { IntegrationUnavailableError, type ProductionPort } from "./ports.js";
import { redactSecrets, tail } from "./redact.js";
import type { WorkspaceManifest } from "./workspace.js";

const exec = promisify(execFile);

export type RemoteExec = (host: string, command: string) => Promise<string>;

/** Real transport: the system ssh client, key-based, non-interactive. Credentials stay in ssh config. */
export const sshExec: RemoteExec = async (host, command) => {
  const { stdout } = await exec(
    "ssh",
    [
      "-o",
      "BatchMode=yes",
      "-o",
      "ConnectTimeout=10",
      "-o",
      "StrictHostKeyChecking=yes",
      host,
      command,
    ],
    {
      maxBuffer: 8 * 1024 * 1024,
      timeout: 30_000,
    },
  );
  return stdout;
};

const SAFE_SINCE =
  /^[0-9]{1,4}\s?(s|m|h|d|min|hour|hours|day|days)( ago)?$|^[0-9]{4}-[0-9]{2}-[0-9]{2}( [0-9]{2}:[0-9]{2}(:[0-9]{2})?)?$/;
const SAFE_PATH = /^\/[A-Za-z0-9._/-]{1,200}$/;

/** Observation-only production port. Commands come from fixed templates over validated values. */
export function createProductionAdapter(
  workspace: WorkspaceManifest,
  remote: RemoteExec = sshExec,
  actor: Actor = { kind: "system", reason: "task-runner" },
): ProductionPort {
  const prod = workspace.production;
  if (!prod) {
    return {
      integration: "unavailable",
      name: "production",
      readLogs: () =>
        Promise.reject(
          new IntegrationUnavailableError(
            "production",
            "workspace has no production configuration",
          ),
        ),
      readProcesses: () =>
        Promise.reject(
          new IntegrationUnavailableError(
            "production",
            "workspace has no production configuration",
          ),
        ),
      readServiceStatus: () =>
        Promise.reject(
          new IntegrationUnavailableError(
            "production",
            "workspace has no production configuration",
          ),
        ),
    };
  }
  const run = async (command: string, maxChars = 12_000) =>
    redactSecrets(tail(await remote(prod.sshHost, command), maxChars));

  return {
    integration: "real",
    name: "production",
    async readLogs({ source, lines, since, grep }) {
      enforce({ workspace, actor, capability: "prod.read.logs", resource: source });
      const def = prod.logSources[source];
      if (!def) {
        throw new Error(
          `unknown log source "${source}"; configured: ${Object.keys(prod.logSources).join(", ")}`,
        );
      }
      const n = Math.max(1, Math.min(lines, 2000));
      let command: string;
      if (def.unit) {
        if (since !== undefined && !SAFE_SINCE.test(since)) {
          throw new Error("unsupported `since` value");
        }
        command = `journalctl -u ${def.unit} -n ${n} --no-pager${since ? ` --since '${since}'` : ""}`;
      } else if (def.file && SAFE_PATH.test(def.file)) {
        command = `tail -n ${n} ${def.file}`;
      } else {
        throw new Error(`log source ${source} is misconfigured`);
      }
      const text = await run(command);
      // Filtering happens locally so the pattern never reaches a remote shell.
      return grep
        ? text
            .split("\n")
            .filter((l) => l.toLowerCase().includes(grep.toLowerCase()))
            .join("\n")
        : text;
    },
    async readProcesses() {
      enforce({ workspace, actor, capability: "prod.read.processes" });
      return run("ps -eo pid,etime,pcpu,pmem,comm --sort=-pcpu | head -n 40");
    },
    async readServiceStatus(service) {
      enforce({ workspace, actor, capability: "prod.read.services", resource: service });
      if (!prod.services.includes(service)) {
        throw new Error(`service "${service}" is not in the workspace allowlist`);
      }
      return run(`systemctl status ${service} --no-pager -n 20`);
    },
  };
}
