import { describe, expect, it } from "vitest";
import { guardEmailCall, guardMessageCall } from "./guard.js";

const owner = { ownerInitiated: true };
const auto = { ownerInitiated: false };

describe("personal outbound guard", () => {
  it("always allows messages to the owner", () => {
    const r = guardMessageCall(
      { action: "send", target: "whatsapp:+1 (555) 000-1111" },
      ["+15550001111"],
      auto,
    );
    expect(r.allow).toBe(true);
  });

  it("blocks autonomous sends to other people", () => {
    const r = guardMessageCall({ action: "send", target: "+15559998888" }, ["+15550001111"], auto);
    expect(r.allow).toBe(false);
  });

  it("allows owner-requested sends to other people", () => {
    expect(guardMessageCall({ action: "send", target: "+15559998888" }, [], owner).allow).toBe(
      true,
    );
  });

  it("blocks when any recipient is not the owner", () => {
    const r = guardMessageCall(
      { action: "send", targets: ["+15550001111", "+15559998888"] },
      ["+15550001111"],
      auto,
    );
    expect(r.allow).toBe(false);
  });

  it("ignores read-style actions", () => {
    expect(guardMessageCall({ action: "read", target: "+15559998888" }, [], auto).allow).toBe(true);
  });

  it("guards email recipients including cc", () => {
    expect(guardEmailCall({ to: "me@x.dev" }, ["me@x.dev"], auto).allow).toBe(true);
    expect(guardEmailCall({ to: "me@x.dev", cc: ["boss@y.com"] }, ["me@x.dev"], auto).allow).toBe(
      false,
    );
    expect(guardEmailCall({ to: "boss@y.com" }, ["me@x.dev"], owner).allow).toBe(true);
  });
});
