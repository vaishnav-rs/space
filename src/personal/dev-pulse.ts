/** Work-pattern analysis and plan-to-timeline estimation. Pure functions; I/O lives in dev-pulse-tools.ts. */

export type Commit = { at: number; repo: string; files: number };

export type WorkProfile = {
  commits: number;
  activeDays: number;
  commitsPerActiveDay: number;
  /** Average focused hours on a day with activity, from session clustering. */
  focusHoursPerActiveDay: number;
  /** Hours of day (0-23, local) ordered by commit count, busiest first. */
  peakHours: number[];
  /** Weekdays (0=Sun) where work happens at least once per 4 weeks on average. */
  workDays: number[];
};

const SESSION_GAP_MS = 90 * 60_000;
const SESSION_LEAD_IN_MS = 30 * 60_000;

export function buildProfile(commits: Commit[], weeks: number): WorkProfile {
  const sorted = [...commits].sort((a, b) => a.at - b.at);
  const days = new Map<string, number[]>();
  const hourCounts = Array.from({ length: 24 }, () => 0);
  const weekdayDays = Array.from({ length: 7 }, () => new Set<string>());
  for (const c of sorted) {
    const d = new Date(c.at);
    const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    days.set(key, [...(days.get(key) ?? []), c.at]);
    hourCounts[d.getHours()] = (hourCounts[d.getHours()] ?? 0) + 1;
    weekdayDays[d.getDay()]?.add(key);
  }
  let focusMs = 0;
  for (const times of days.values()) {
    const [first, ...rest] = times;
    if (first === undefined) {
      continue;
    }
    let start = first;
    let prev = first;
    for (const t of rest) {
      if (t - prev > SESSION_GAP_MS) {
        focusMs += prev - start + SESSION_LEAD_IN_MS;
        start = t;
      }
      prev = t;
    }
    focusMs += prev - start + SESSION_LEAD_IN_MS;
  }
  const activeDays = days.size;
  const w = Math.max(weeks, 1);
  return {
    commits: sorted.length,
    activeDays,
    commitsPerActiveDay: activeDays ? sorted.length / activeDays : 0,
    focusHoursPerActiveDay: activeDays ? focusMs / 3_600_000 / activeDays : 0,
    peakHours: hourCounts
      .map((n, h) => ({ n, h }))
      .filter((x) => x.n > 0)
      .sort((a, b) => b.n - a.n)
      .slice(0, 3)
      .map((x) => x.h),
    workDays: weekdayDays
      .map((s, i) => ({ i, rate: s.size / w }))
      .filter((x) => x.rate >= 0.25)
      .map((x) => x.i),
  };
}

export type PlanTask = { title: string; hours: number; explicit: boolean; done: boolean };

const EXPLICIT = /[([]\s*~?(\d+(?:\.\d+)?)\s*(h|hr|hrs|hours?|d|days?|m|min|mins)\s*[)\]]/i;
const DEFAULT_HOURS = 3;

export function parsePlan(markdown: string): PlanTask[] {
  const tasks: PlanTask[] = [];
  for (const line of markdown.split(/\r?\n/)) {
    const m = /^\s*(?:[-*+]|\d+[.)])\s+(?:\[( |x|X)\]\s+)?(.+)$/.exec(line);
    if (!m) {
      continue;
    }
    const raw = (m[2] ?? "").trim();
    const e = EXPLICIT.exec(raw);
    let hours = DEFAULT_HOURS;
    if (e) {
      const n = Number(e[1]);
      const unit = (e[2] ?? "h").toLowerCase();
      hours = unit.startsWith("d") ? n * 6 : unit.startsWith("m") ? n / 60 : n;
    }
    tasks.push({
      title: raw.replace(EXPLICIT, "").trim(),
      hours,
      explicit: Boolean(e),
      done: m[1] === "x" || m[1] === "X",
    });
  }
  return tasks;
}

export type HistoryEntry = { estimateHours: number; actualHours: number };

/** Median actual/estimate ratio; falls back to a planning-fallacy default until there is data. */
export function calibrationFactor(history: HistoryEntry[]): number {
  const ratios = history
    .filter((h) => h.estimateHours > 0 && h.actualHours > 0)
    .map((h) => h.actualHours / h.estimateHours)
    .sort((a, b) => a - b);
  if (ratios.length < 3) {
    return 1.4;
  }
  const mid = Math.floor(ratios.length / 2);
  const hi = ratios[mid] ?? 1.4;
  return ratios.length % 2 ? hi : ((ratios[mid - 1] ?? hi) + hi) / 2;
}

export type Timeline = {
  remainingHours: number;
  calibration: number;
  hoursPerDay: number;
  likely: string;
  conservative: string;
  workDaysLikely: number;
};

function addWorkDays(start: Date, n: number, workDays: number[]): Date {
  const d = new Date(start);
  let left = Math.ceil(n);
  const allowed = workDays.length ? workDays : [1, 2, 3, 4, 5];
  while (left > 0) {
    d.setDate(d.getDate() + 1);
    if (allowed.includes(d.getDay())) {
      left -= 1;
    }
  }
  return d;
}

export function estimateTimeline(
  tasks: PlanTask[],
  profile: Pick<WorkProfile, "focusHoursPerActiveDay" | "workDays">,
  history: HistoryEntry[],
  start: Date = new Date(),
): Timeline {
  const remaining = tasks.filter((t) => !t.done).reduce((s, t) => s + t.hours, 0);
  const calibration = calibrationFactor(history);
  const hoursPerDay = profile.focusHoursPerActiveDay > 0 ? profile.focusHoursPerActiveDay : 4;
  const likelyDays = (remaining * calibration) / hoursPerDay;
  const conservativeDays = likelyDays * 1.35;
  return {
    remainingHours: round(remaining),
    calibration: round(calibration),
    hoursPerDay: round(hoursPerDay),
    likely: addWorkDays(start, likelyDays, profile.workDays).toISOString().slice(0, 10),
    conservative: addWorkDays(start, conservativeDays, profile.workDays).toISOString().slice(0, 10),
    workDaysLikely: Math.ceil(likelyDays),
  };
}

const round = (n: number) => Math.round(n * 10) / 10;
