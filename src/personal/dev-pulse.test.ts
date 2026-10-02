import { describe, expect, it } from "vitest";
import { buildProfile, calibrationFactor, estimateTimeline, parsePlan } from "./dev-pulse.js";

const at = (iso: string) => new Date(iso).getTime();

describe("dev-pulse", () => {
  it("parses checklists with explicit estimates", () => {
    const tasks = parsePlan("- [x] Setup repo (1h)\n- [ ] Build API [2d]\n* Write docs\n");
    expect(tasks).toEqual([
      { title: "Setup repo", hours: 1, explicit: true, done: true },
      { title: "Build API", hours: 12, explicit: true, done: false },
      { title: "Write docs", hours: 3, explicit: false, done: false },
    ]);
  });

  it("clusters commits into focus sessions", () => {
    const profile = buildProfile(
      [
        { at: at("2026-03-02T09:00:00"), repo: "a", files: 1 },
        { at: at("2026-03-02T10:00:00"), repo: "a", files: 1 },
        { at: at("2026-03-02T15:00:00"), repo: "a", files: 1 },
      ],
      1,
    );
    expect(profile.activeDays).toBe(1);
    expect(profile.focusHoursPerActiveDay).toBeCloseTo(2, 5); // (1h+30m) + 30m
  });

  it("uses a planning-fallacy default, then learns from history", () => {
    expect(calibrationFactor([])).toBe(1.4);
    expect(
      calibrationFactor([
        { estimateHours: 2, actualHours: 4 },
        { estimateHours: 1, actualHours: 2 },
        { estimateHours: 3, actualHours: 6 },
      ]),
    ).toBe(2);
  });

  it("skips non-work days when projecting dates", () => {
    const t = estimateTimeline(
      parsePlan("- a (8h)"),
      { focusHoursPerActiveDay: 4, workDays: [1, 2, 3, 4, 5] },
      [],
      new Date("2026-03-06T12:00:00Z"), // Friday
    );
    expect(t.workDaysLikely).toBe(3); // 8h * 1.4 / 4h
    expect(t.likely).toBe("2026-03-11");
  });
});
