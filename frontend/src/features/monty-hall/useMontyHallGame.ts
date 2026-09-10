import { useCallback, useEffect, useState } from "react";
import {
  checkHealth,
  createGame,
  getStats,
  makeChoice,
  makeDecision,
} from "./api";
import { verifyCommitment, type FairnessStatus } from "./fairness";
import type {
  BoxNumber,
  ChoiceResult,
  CompletedGame,
  CreatedGame,
  PublicStats,
  Strategy,
} from "./types";

export type GamePhase =
  | "booting"
  | "starting"
  | "ready"
  | "choosing"
  | "choice-made"
  | "deciding"
  | "completed"
  | "unavailable";

type RetryAction =
  | { type: "choice"; box: BoxNumber }
  | { type: "decision"; strategy: Strategy }
  | { type: "start" };

export interface MontyHallGameState {
  phase: GamePhase;
  game: CreatedGame | null;
  choice: ChoiceResult | null;
  result: CompletedGame | null;
  fairness: FairnessStatus | null;
  stats: PublicStats | null;
  statsLoading: boolean;
  error: string | null;
  retryAction: RetryAction | null;
}

const initialState: MontyHallGameState = {
  phase: "booting",
  game: null,
  choice: null,
  result: null,
  fairness: null,
  stats: null,
  statsLoading: true,
  error: null,
  retryAction: null,
};

export function useMontyHallGame() {
  const [state, setState] = useState(initialState);

  const refreshStats = useCallback(async () => {
    setState((current) => ({ ...current, statsLoading: true }));
    try {
      const stats = await getStats();
      setState((current) => ({ ...current, stats, statsLoading: false }));
    } catch {
      setState((current) => ({ ...current, statsLoading: false }));
    }
  }, []);

  const startNewGame = useCallback(async (checkServer = false) => {
    setState((current) => ({
      ...current,
      phase: current.phase === "booting" ? "booting" : "starting",
      game: null,
      choice: null,
      result: null,
      fairness: null,
      error: null,
      retryAction: null,
    }));

    try {
      if (checkServer) {
        await checkHealth();
      }
      const game = await createGame();
      setState((current) => ({ ...current, game, phase: "ready" }));
    } catch {
      setState((current) => ({
        ...current,
        phase: "unavailable",
        error: "Игровой сервер сейчас временно недоступен.",
        retryAction: { type: "start" },
      }));
    }
  }, []);

  useEffect(() => {
    let active = true;

    async function boot() {
      try {
        await checkHealth();
        if (!active) return;
        const [game, stats] = await Promise.all([
          createGame(),
          getStats().catch(() => null),
        ]);
        if (!active) return;
        setState((current) => ({
          ...current,
          phase: "ready",
          game,
          stats,
          statsLoading: false,
        }));
      } catch {
        if (!active) return;
        setState((current) => ({
          ...current,
          phase: "unavailable",
          statsLoading: false,
          error: "Игровой сервер сейчас временно недоступен.",
          retryAction: { type: "start" },
        }));
      }
    }

    void boot();
    return () => {
      active = false;
    };
  }, []);

  const selectBox = useCallback(async (box: BoxNumber) => {
    if (!state.game || (state.phase !== "ready" && state.phase !== "choosing")) return;
    setState((current) => ({ ...current, phase: "choosing", error: null, retryAction: null }));
    try {
      const choice = await makeChoice(state.game.gameId, box);
      setState((current) => ({ ...current, phase: "choice-made", choice }));
    } catch {
      setState((current) => ({
        ...current,
        phase: "ready",
        error: "Не удалось сохранить выбор. Проверьте соединение и повторите.",
        retryAction: { type: "choice", box },
      }));
    }
  }, [state.game, state.phase]);

  const decide = useCallback(async (strategy: Strategy) => {
    if (!state.game || !state.choice || (state.phase !== "choice-made" && state.phase !== "deciding")) return;
    setState((current) => ({ ...current, phase: "deciding", error: null, retryAction: null }));
    try {
      const result = await makeDecision(state.game.gameId, strategy);
      setState((current) => ({
        ...current,
        phase: "completed",
        result,
        fairness: "checking",
      }));
      const fairness = await verifyCommitment(result, state.game);
      setState((current) => ({ ...current, fairness }));
      void refreshStats();
    } catch {
      setState((current) => ({
        ...current,
        phase: "choice-made",
        error: "Не удалось завершить партию. Повторите решение.",
        retryAction: { type: "decision", strategy },
      }));
    }
  }, [refreshStats, state.choice, state.game, state.phase]);

  const retry = useCallback(() => {
    const action = state.retryAction;
    if (!action) return;
    if (action.type === "choice") void selectBox(action.box);
    if (action.type === "decision") void decide(action.strategy);
    if (action.type === "start") void startNewGame(true);
  }, [decide, selectBox, startNewGame, state.retryAction]);

  return {
    state,
    selectBox,
    decide,
    retry,
    startNewGame: () => startNewGame(true),
    refreshStats,
  };
}
