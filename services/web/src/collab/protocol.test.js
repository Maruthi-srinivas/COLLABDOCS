import { describe, expect, it } from "vitest";
import { planWelcome } from "./protocol.js";

describe("welcome application", () => {
  it("does not reset a document that is already on the same epoch", () => {
    const plan = planWelcome(1, new Set([1, 2]), {
      epoch: 1,
      snapshotBase64: "AAAA",
      updates: [{ seq: 2, payloadBase64: "AA==" }, { seq: 3, payloadBase64: "AQ==" }],
    });
    expect(plan.reset).toBe(false);
    expect(plan.applySnapshot).toBe(false);
    expect(plan.updates.map((update) => update.seq)).toEqual([3]);
  });
});
