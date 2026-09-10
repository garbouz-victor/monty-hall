import { describe, expect, it } from "vitest";
import { verifyCommitment } from "./fairness";
import type { CompletedGame, CreatedGame } from "./types";

const original: CreatedGame = {
  gameId: "123e4567-e89b-12d3-a456-426614174000",
  commitment: "f4709a20f8efb43861af79dfdff4465a07968bcbffaa2159f370460f79ba7d02",
};

const completed: CompletedGame = {
  gameId: original.gameId,
  initialChoice: 3,
  openedBox: 1,
  finalChoice: 2,
  strategy: "SWITCH",
  keyBox: 2,
  won: true,
  nonce: "0011aaff",
  commitment: original.commitment,
};

describe("fairness verification", () => {
  it("verifies reveal against the commitment received before the choice", async () => {
    expect(await verifyCommitment(completed, original)).toBe("verified");
  });

  it("rejects a replaced final commitment even when it could match another reveal", async () => {
    expect(await verifyCommitment(
      { ...completed, commitment: "0".repeat(64) },
      original,
    )).toBe("mismatch");
  });
});

