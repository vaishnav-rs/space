import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { promisify } from "node:util";
import { Type } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import type { PersonalConfig } from "./config.js";
import {
  buildProfile,
  estimateTimeline,
  parsePlan,
  type Commit,
  type HistoryEntry,
} from "./dev-pulse.js";
import { optStr, personalTool, reqStr, str, strList } from "./tool-kit.js";

const run = promisify(execFile);

async function readCommits(repo: string, weeks: number, signal?: AbortSignal): Promise<Commit[]> {
  const { stdout } = await run(
    "git",
    [
      "-C",
      repo,
      "log",
      `--since=${weeks} weeks ago`,
      "--no-merges",
      "--pretty=format:%ct",
      "--shortstat",
    ],
    { maxBuffer: 16 * 1024 * 1024, signal },
  );
  const commits: Commit[] = [];
  for (const line of stdout.split("\n")) {
    if (/^\d{9,}$/.test(line.trim())) {
      commits.push({ at: Number(line) * 1000, repo, files: 0 });
    }
  }
  return commits;
}

async function readHistory(dir: string): Promise<HistoryEntry[]> {
  try {
    return JSON.parse(
      await readFile(join(dir, "dev-pulse-history.json"), "utf8"),
    ) as HistoryEntry[];
  } catch {
    return [];
  }
}

export function createDevPulseTools(cfg: PersonalConfig): AnyAgentTool[] {
  const profileFor = async (repos: string[], weeks: number, signal?: AbortSignal) => {
    const all = (await Promise.all(repos.map((r) => readCommits(r, weeks, signal)))).flat();
    return buildProfile(all, weeks);
  };
  return [
    personalTool({
      name: "dev_pulse_profile",
      label: "Work profile",
      description:
        "Analyze how the owner works from git history: active days, focus hours per day, peak hours, usual work days. Defaults to the repos in PERSONAL_DEV_REPOS over the last 8 weeks.",
      parameters: Type.Object({
        repos: strList("Local repo paths; default PERSONAL_DEV_REPOS."),
        weeks: Type.Optional(Type.Integer({ minimum: 1, maximum: 52 })),
      }),
      run: async (p, signal) => {
        const repos = Array.isArray(p.repos) ? (p.repos as string[]) : cfg.devRepos;
        if (repos.length === 0) {
          throw new Error("No repos: pass `repos` or set PERSONAL_DEV_REPOS.");
        }
        return profileFor(repos, typeof p.weeks === "number" ? p.weeks : 8, signal);
      },
    }),
    personalTool({
      name: "dev_pulse_estimate",
      label: "Plan estimate",
      description:
        "Turn a project doc or action plan (markdown checklist; hours like '(3h)' or '[2d]' are honored) into a timeline calibrated to the owner's real pace and past estimate accuracy.",
      parameters: Type.Object({
        plan: str("Markdown containing the task list."),
        start: optStr("ISO start date, default today."),
      }),
      run: async (p, signal) => {
        const tasks = parsePlan(reqStr(p, "plan"));
        if (tasks.length === 0) {
          throw new Error("No list items found in the plan.");
        }
        const profile = cfg.devRepos.length
          ? await profileFor(cfg.devRepos, 8, signal)
          : { focusHoursPerActiveDay: 0, workDays: [] };
        const timeline = estimateTimeline(
          tasks,
          profile,
          await readHistory(cfg.dataDir),
          typeof p.start === "string" ? new Date(p.start) : new Date(),
        );
        return {
          tasks: tasks.length,
          unestimated: tasks.filter((t) => !t.explicit).length,
          ...timeline,
        };
      },
    }),
    personalTool({
      name: "dev_pulse_log_actual",
      label: "Log actual time",
      description:
        "Record estimate vs actual hours for finished work so future estimates self-calibrate.",
      parameters: Type.Object({
        estimateHours: Type.Number({ minimum: 0 }),
        actualHours: Type.Number({ minimum: 0 }),
      }),
      run: async (p) => {
        const history = await readHistory(cfg.dataDir);
        history.push({
          estimateHours: Number(p.estimateHours),
          actualHours: Number(p.actualHours),
        });
        await mkdir(cfg.dataDir, { recursive: true });
        await writeFile(
          join(cfg.dataDir, "dev-pulse-history.json"),
          JSON.stringify(history.slice(-200)),
        );
        return { entries: history.length };
      },
    }),
  ];
}
