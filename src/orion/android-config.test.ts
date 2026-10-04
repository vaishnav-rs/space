import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { validateConfigObjectRaw } from "../config/validation-core.js";

// The Android app's unit test asserts that OnDevicePlan.configJson matches this sample, so the
// config the phone writes is exactly what the gateway's own validator accepts here.
const sample = JSON.parse(
  readFileSync(
    new URL("../../apps/android/app/src/test/resources/gateway-config.sample.json", import.meta.url),
    "utf8",
  ),
);

describe("on-device gateway config", () => {
  it.each(["loopback", "lan"])("is accepted by the gateway validator (bind=%s)", (bind) => {
    const result = validateConfigObjectRaw({ ...sample, gateway: { ...sample.gateway, bind } });
    expect(result.ok, JSON.stringify(result.ok ? [] : result.issues)).toBe(true);
  });
});
