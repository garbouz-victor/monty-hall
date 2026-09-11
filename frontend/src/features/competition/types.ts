import type { GameStateResponse } from "../monty-hall/types";

export type CompetitionRunStatus = "ACTIVE" | "LOST" | "ABANDONED" | "EXPIRED";
export type LeaderboardPeriod = "TODAY" | "ALL_TIME";

export interface CompetitionPlayer {
  publicPlayerId: string;
  publicTag: string;
  displayName: string;
}

export interface CompetitionRun {
  runId: string;
  status: CompetitionRunStatus;
  score: number;
  attemptNumber: number;
  competitionDate: string;
  startedAt: string;
  expiresAt: string;
  endedAt?: string;
  rulesVersion: number;
  remainingAttempts: number;
  todayBest: number;
  allTimeBest: number;
  todayRank?: number;
  allTimeRank?: number;
}

export interface CompetitionRound {
  roundNumber: number;
  game: GameStateResponse;
}

export interface CompetitionMe {
  authenticated: boolean;
  serverTime: string;
  timezone: string;
  competitionDate: string;
  dailyAttemptLimit: number;
  remainingAttempts: number;
  player?: CompetitionPlayer;
  todayBest: number;
  allTimeBest: number;
  todayRank?: number;
  allTimeRank?: number;
  run?: CompetitionRun;
  round?: CompetitionRound;
}

export interface CreateRoundResponse {
  gameId: string;
  commitment: string;
  roundNumber: number;
  run: CompetitionRun;
  replayed: boolean;
}

export interface LeaderboardEntry {
  publicPlayerId: string;
  publicTag: string;
  displayName: string;
  rank: number;
  bestStreak: number;
  achievedAt: string;
}

export interface LeaderboardResponse {
  period: LeaderboardPeriod;
  competitionDate?: string;
  serverTime: string;
  timezone: string;
  registeredProfiles: number;
  participantsWithResult: number;
  entries: LeaderboardEntry[];
  me?: LeaderboardEntry;
}
