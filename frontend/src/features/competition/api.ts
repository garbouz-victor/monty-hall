import { apiRequest } from "../monty-hall/api";
import type { BoxNumber, ChoiceResult, CompletedGame, Strategy } from "../monty-hall/types";
import type {
  CompetitionMe, CompetitionPlayer, CompetitionRun, CreateRoundResponse,
  LeaderboardPeriod, LeaderboardResponse,
} from "./types";

const mutationHeaders = { "X-JoyHub-CSRF": "1" };

export function getCompetitionMe(): Promise<CompetitionMe> {
  return apiRequest<CompetitionMe>("/api/v1/competition/me");
}

export function saveCompetitionProfile(displayName: string): Promise<{ player: CompetitionPlayer }> {
  return apiRequest("/api/v1/competition/profile", {
    method: "PUT",
    headers: mutationHeaders,
    body: JSON.stringify({ displayName }),
  });
}

export function startCompetitionRun(requestId: string): Promise<{ run: CompetitionRun; replayed: boolean }> {
  return apiRequest("/api/v1/competition/runs", {
    method: "POST",
    headers: { ...mutationHeaders, "Idempotency-Key": requestId },
  });
}

export function createCompetitionRound(
  runId: string, requestId: string, expectedRoundNumber: number,
): Promise<CreateRoundResponse> {
  return apiRequest(`/api/v1/competition/runs/${runId}/rounds`, {
    method: "POST",
    headers: { ...mutationHeaders, "Idempotency-Key": requestId },
    body: JSON.stringify({ expectedRoundNumber }),
  });
}

export function makeCompetitionChoice(gameId: string, box: BoxNumber): Promise<ChoiceResult> {
  return apiRequest(`/api/v1/games/${gameId}/choice`, {
    method: "POST", headers: mutationHeaders, body: JSON.stringify({ box }),
  });
}

export function makeCompetitionDecision(gameId: string, strategy: Strategy): Promise<CompletedGame> {
  return apiRequest(`/api/v1/games/${gameId}/decision`, {
    method: "POST", headers: mutationHeaders, body: JSON.stringify({ strategy }),
  });
}

export function abandonCompetitionRun(runId: string): Promise<CompetitionRun> {
  return apiRequest(`/api/v1/competition/runs/${runId}/abandon`, {
    method: "POST", headers: mutationHeaders,
  });
}

export function getLeaderboard(period: LeaderboardPeriod): Promise<LeaderboardResponse> {
  return apiRequest(`/api/v1/competition/leaderboard?period=${period}&limit=10`);
}
