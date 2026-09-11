export type BoxNumber = 1 | 2 | 3;
export type Strategy = "SWITCH" | "STAY";

export interface CreatedGame {
  gameId: string;
  commitment: string;
}

export interface ChoiceResult {
  selectedBox: BoxNumber;
  openedBox: BoxNumber;
  switchToBox: BoxNumber;
}

export interface CompletedGame {
  gameId: string;
  initialChoice: BoxNumber;
  openedBox: BoxNumber;
  finalChoice: BoxNumber;
  strategy: Strategy;
  keyBox: BoxNumber;
  won: boolean;
  nonce: string;
  commitment: string;
  competition?: import("../competition/types").CompetitionRun;
}

export type GameStateResponse =
  | (CreatedGame & { state: "CREATED" })
  | (CreatedGame & {
      state: "CHOICE_MADE";
      initialChoice: BoxNumber;
      openedBox: BoxNumber;
      switchToBox: BoxNumber;
    })
  | (CompletedGame & { state: "COMPLETED" });

export interface StrategyStats {
  games: number;
  wins: number;
  losses: number;
  winRate: number;
}

export interface PublicStats {
  totalCompletedGames: number;
  switch: StrategyStats;
  stay: StrategyStats;
  theoretical: {
    switchWinRate: number;
    stayWinRate: number;
  };
  updatedAt: string;
}
