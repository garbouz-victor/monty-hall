import type { CompletedGame, CreatedGame } from "./types";

export type FairnessStatus = "checking" | "verified" | "mismatch" | "unsupported";

export async function verifyCommitment(result: CompletedGame, original: CreatedGame): Promise<FairnessStatus> {
  if (!globalThis.crypto?.subtle) {
    return "unsupported";
  }

  if (result.gameId !== original.gameId || result.commitment.toLowerCase() !== original.commitment.toLowerCase()) {
    return "mismatch";
  }

  const canonical = `v1:${original.gameId}:${result.keyBox}:${result.nonce}`;
  const data = new TextEncoder().encode(canonical);
  const digest = await globalThis.crypto.subtle.digest("SHA-256", data);
  const actual = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  return actual === original.commitment.toLowerCase() ? "verified" : "mismatch";
}
