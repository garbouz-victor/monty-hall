import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  checkHealth,
  createGame,
  getGameState,
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
  GameStateResponse,
  PublicStats,
  Strategy,
} from "./types";

export type GamePhase =
  | "booting"
  | "ready"
  | "starting"
  | "start-failed"
  | "choosing"
  | "choice-made"
  | "deciding"
  | "recovering-choice"
  | "recovering-decision"
  | "recovery-blocked"
  | "completed"
  | "game-missing"
  | "unavailable";

export type PendingMutation =
  | { type: "choice"; box: BoxNumber; creationRequestId: string }
  | { type: "decision"; strategy: Strategy }
  | null;

type RetryAction = "boot" | "repeat-pending" | "recover" | "reset" | null;

export interface MontyHallGameState {
  phase: GamePhase;
  game: CreatedGame | null;
  choice: ChoiceResult | null;
  result: CompletedGame | null;
  pendingMutation: PendingMutation;
  fairness: FairnessStatus | null;
  stats: PublicStats | null;
  statsLoading: boolean;
  error: string | null;
  retryAction: RetryAction;
  retryLabel: string | null;
}

const initialState: MontyHallGameState = {
  phase: "booting",
  game: null,
  choice: null,
  result: null,
  pendingMutation: null,
  fairness: null,
  stats: null,
  statsLoading: true,
  error: null,
  retryAction: null,
  retryLabel: null,
};

export const PENDING_CREATION_STORAGE_KEY = "joyhub.pending-game-creation.v1";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type PendingChoice = Extract<Exclude<PendingMutation, null>, { type: "choice" }>;

function loadPendingCreation(): PendingChoice | null {
  try {
    const raw = window.sessionStorage.getItem(PENDING_CREATION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { creationRequestId?: unknown; box?: unknown };
    if (
      typeof parsed.creationRequestId !== "string"
      || !UUID_PATTERN.test(parsed.creationRequestId)
      || ![1, 2, 3].includes(parsed.box as number)
    ) {
      window.sessionStorage.removeItem(PENDING_CREATION_STORAGE_KEY);
      return null;
    }
    return {
      type: "choice",
      box: parsed.box as BoxNumber,
      creationRequestId: parsed.creationRequestId,
    };
  } catch {
    return null;
  }
}

function savePendingCreation(pending: PendingChoice): void {
  try {
    window.sessionStorage.setItem(PENDING_CREATION_STORAGE_KEY, JSON.stringify({
      creationRequestId: pending.creationRequestId,
      box: pending.box,
    }));
  } catch {
    // The in-memory state still preserves the request when storage is unavailable.
  }
}

function clearPendingCreation(creationRequestId?: string): void {
  try {
    if (creationRequestId) {
      const pending = loadPendingCreation();
      if (pending && pending.creationRequestId !== creationRequestId) return;
    }
    window.sessionStorage.removeItem(PENDING_CREATION_STORAGE_KEY);
  } catch {
    // Storage availability must not block gameplay.
  }
}

function choiceFromSnapshot(snapshot: Extract<GameStateResponse, { state: "CHOICE_MADE" }>): ChoiceResult {
  return {
    selectedBox: snapshot.initialChoice,
    openedBox: snapshot.openedBox,
    switchToBox: snapshot.switchToBox,
  };
}

function resultFromSnapshot(snapshot: Extract<GameStateResponse, { state: "COMPLETED" }>): CompletedGame {
  return {
    gameId: snapshot.gameId,
    initialChoice: snapshot.initialChoice,
    openedBox: snapshot.openedBox,
    finalChoice: snapshot.finalChoice,
    strategy: snapshot.strategy,
    keyBox: snapshot.keyBox,
    won: snapshot.won,
    nonce: snapshot.nonce,
    commitment: snapshot.commitment,
  };
}

function shouldRecover(error: unknown): boolean {
  return error instanceof ApiError
    && ["NETWORK", "TIMEOUT", "CONFLICT", "SERVER"].includes(error.kind);
}

function pendingActionLabel(pending: Exclude<PendingMutation, null>): string {
  return pending.type === "choice"
    ? `Повторить выбор ящика №${pending.box}`
    : pending.strategy === "SWITCH"
      ? "Повторить смену выбора"
      : "Повторить прежний выбор";
}

export function useMontyHallGame() {
  const [state, setState] = useState(initialState);
  const mutationInFlight = useRef(false);
  const recoveryInFlight = useRef(false);

  const refreshStats = useCallback(async () => {
    setState((current) => ({ ...current, statsLoading: true }));
    try {
      const stats = await getStats();
      setState((current) => ({ ...current, stats, statsLoading: false }));
    } catch {
      setState((current) => ({ ...current, statsLoading: false }));
    }
  }, []);

  const boot = useCallback(async () => {
    setState((current) => ({
      ...current,
      phase: "booting",
      error: null,
      retryAction: null,
      retryLabel: null,
      statsLoading: true,
    }));

    const [health, stats] = await Promise.allSettled([checkHealth(), getStats()]);
    const pendingCreation = loadPendingCreation();
    const backendAvailable = health.status === "fulfilled";
    setState((current) => ({
      ...current,
      phase: backendAvailable
        ? pendingCreation ? "start-failed" : "ready"
        : "unavailable",
      game: null,
      choice: null,
      result: null,
      pendingMutation: pendingCreation,
      fairness: null,
      stats: stats.status === "fulfilled" ? stats.value : current.stats,
      statsLoading: false,
      error: backendAvailable
        ? pendingCreation
          ? `Не удалось подтвердить начало игры. Ваш выбор ящика №${pendingCreation.box} сохранён.`
          : null
        : "Игровой сервер сейчас временно недоступен.",
      retryAction: backendAvailable
        ? pendingCreation ? "repeat-pending" : null
        : "boot",
      retryLabel: backendAvailable && pendingCreation
        ? `Повторить выбор ящика №${pendingCreation.box}`
        : backendAvailable ? null : "Попробовать снова",
    }));
  }, []);

  useEffect(() => {
    void boot();
  }, [boot]);

  const showCompleted = useCallback((game: CreatedGame, result: CompletedGame) => {
    setState((current) => ({
      ...current,
      phase: "completed",
      result,
      pendingMutation: null,
      fairness: "checking",
      error: null,
      retryAction: null,
      retryLabel: null,
    }));

    void verifyCommitment(result, game).then((fairness) => {
      setState((current) => current.result?.gameId === result.gameId
        ? { ...current, fairness }
        : current);
    });
    void refreshStats();
  }, [refreshStats]);

  const markGameMissing = useCallback(() => {
    setState((current) => ({
      ...current,
      phase: "game-missing",
      pendingMutation: null,
      error: "Эту партию не удалось восстановить. Начните новую игру.",
      retryAction: "reset",
      retryLabel: "Начать заново",
    }));
  }, []);

  const recover = useCallback(async (game: CreatedGame, pending: Exclude<PendingMutation, null>) => {
    if (recoveryInFlight.current) return;
    recoveryInFlight.current = true;
    setState((current) => ({
      ...current,
      phase: pending.type === "choice" ? "recovering-choice" : "recovering-decision",
      error: null,
      retryAction: null,
      retryLabel: null,
    }));

    try {
      const snapshot = await getGameState(game.gameId);
      if (snapshot.gameId !== game.gameId || snapshot.commitment.toLowerCase() !== game.commitment.toLowerCase()) {
        markGameMissing();
        return;
      }

      if (snapshot.state === "COMPLETED") {
        showCompleted(game, resultFromSnapshot(snapshot));
        return;
      }

      if (snapshot.state === "CHOICE_MADE" && pending.type === "choice") {
        if (snapshot.initialChoice !== pending.box) {
          markGameMissing();
          return;
        }
        setState((current) => ({
          ...current,
          phase: "choice-made",
          choice: choiceFromSnapshot(snapshot),
          pendingMutation: null,
          error: null,
          retryAction: null,
          retryLabel: null,
        }));
        return;
      }

      setState((current) => ({
        ...current,
        phase: "recovery-blocked",
        pendingMutation: pending,
        error: "Сервер ещё не подтвердил сохранение хода. Можно безопасно повторить только прежнее действие.",
        retryAction: "repeat-pending",
        retryLabel: pendingActionLabel(pending),
      }));
    } catch (error) {
      if (error instanceof ApiError && error.kind === "NOT_FOUND") {
        markGameMissing();
        return;
      }
      setState((current) => ({
        ...current,
        phase: "recovery-blocked",
        pendingMutation: pending,
        error: "Не удалось проверить, был ли ваш ход сохранён.",
        retryAction: "recover",
        retryLabel: "Повторить проверку",
      }));
    } finally {
      recoveryInFlight.current = false;
    }
  }, [markGameMissing, showCompleted]);

  const handleMutationFailure = useCallback(async (
    error: unknown,
    game: CreatedGame,
    pending: Exclude<PendingMutation, null>,
  ) => {
    if (shouldRecover(error)) {
      await recover(game, pending);
      return;
    }
    if (error instanceof ApiError && error.kind === "NOT_FOUND") {
      markGameMissing();
      return;
    }

    const rateLimited = error instanceof ApiError && error.kind === "RATE_LIMIT";
    setState((current) => ({
      ...current,
      phase: "recovery-blocked",
      pendingMutation: pending,
      error: rateLimited
        ? "Слишком много запросов. Подождите немного и повторите прежний ход."
        : "Ход не принят. Можно безопасно повторить только прежнее действие.",
      retryAction: "repeat-pending",
      retryLabel: pendingActionLabel(pending),
    }));
  }, [markGameMissing, recover]);

  const performChoice = useCallback(async (
    game: CreatedGame,
    pending: Extract<Exclude<PendingMutation, null>, { type: "choice" }>,
  ) => {
    setState((current) => ({
      ...current,
      game,
      phase: "choosing",
      pendingMutation: pending,
      error: null,
      retryAction: null,
      retryLabel: null,
    }));
    try {
      const choice = await makeChoice(game.gameId, pending.box);
      setState((current) => ({
        ...current,
        phase: "choice-made",
        choice,
        pendingMutation: null,
      }));
    } catch (error) {
      await handleMutationFailure(error, game, pending);
    }
  }, [handleMutationFailure]);

  const performDecision = useCallback(async (
    game: CreatedGame,
    pending: Extract<Exclude<PendingMutation, null>, { type: "decision" }>,
  ) => {
    setState((current) => ({
      ...current,
      phase: "deciding",
      pendingMutation: pending,
      error: null,
      retryAction: null,
      retryLabel: null,
    }));
    try {
      const result = await makeDecision(game.gameId, pending.strategy);
      showCompleted(game, result);
    } catch (error) {
      await handleMutationFailure(error, game, pending);
    }
  }, [handleMutationFailure, showCompleted]);

  const selectBox = useCallback(async (box: BoxNumber) => {
    if (state.phase !== "ready" || mutationInFlight.current) return;
    mutationInFlight.current = true;
    const pending = {
      type: "choice",
      box,
      creationRequestId: crypto.randomUUID(),
    } as const;
    savePendingCreation(pending);
    setState((current) => ({
      ...current,
      phase: "starting",
      pendingMutation: pending,
      error: null,
      retryAction: null,
      retryLabel: null,
    }));

    try {
      const game = await createGame(pending.creationRequestId);
      clearPendingCreation(pending.creationRequestId);
      await performChoice(game, pending);
    } catch (error) {
      if (error instanceof ApiError && error.code === "IDEMPOTENCY_KEY_CONFLICT") {
        clearPendingCreation(pending.creationRequestId);
        setState((current) => ({
          ...current,
          phase: "start-failed",
          game: null,
          pendingMutation: null,
          error: "Эту попытку начала игры нельзя восстановить. Начните новую игру.",
          retryAction: "reset",
          retryLabel: "Начать заново",
        }));
        return;
      }
      const rateLimited = error instanceof ApiError && error.kind === "RATE_LIMIT";
      setState((current) => ({
        ...current,
        phase: "start-failed",
        game: null,
        pendingMutation: pending,
        error: rateLimited
          ? "Слишком много начатых партий. Завершите текущие партии или попробуйте позже."
          : `Не удалось подтвердить начало игры. Ваш выбор ящика №${box} сохранён.`,
        retryAction: "repeat-pending",
        retryLabel: `Повторить выбор ящика №${box}`,
      }));
    } finally {
      mutationInFlight.current = false;
    }
  }, [performChoice, state.phase]);

  const decide = useCallback(async (strategy: Strategy) => {
    if (state.phase !== "choice-made" || !state.game || !state.choice || mutationInFlight.current) return;
    mutationInFlight.current = true;
    try {
      await performDecision(state.game, { type: "decision", strategy });
    } finally {
      mutationInFlight.current = false;
    }
  }, [performDecision, state.choice, state.game, state.phase]);

  const resetRound = useCallback(() => {
    mutationInFlight.current = false;
    recoveryInFlight.current = false;
    clearPendingCreation();
    setState((current) => ({
      ...current,
      phase: "ready",
      game: null,
      choice: null,
      result: null,
      pendingMutation: null,
      fairness: null,
      error: null,
      retryAction: null,
      retryLabel: null,
    }));
  }, []);

  const retry = useCallback(async () => {
    if (mutationInFlight.current || recoveryInFlight.current) return;
    if (state.retryAction === "boot") {
      await boot();
      return;
    }
    if (state.retryAction === "reset") {
      resetRound();
      return;
    }
    if (!state.pendingMutation) return;

    if (state.retryAction === "recover" && state.game) {
      await recover(state.game, state.pendingMutation);
      return;
    }

    if (state.retryAction !== "repeat-pending") return;
    mutationInFlight.current = true;
    try {
      if (state.pendingMutation.type === "choice") {
        if (state.game) {
          await performChoice(state.game, state.pendingMutation);
        } else {
          const game = await createGame(state.pendingMutation.creationRequestId);
          clearPendingCreation(state.pendingMutation.creationRequestId);
          await performChoice(game, state.pendingMutation);
        }
      } else if (state.game) {
        await performDecision(state.game, state.pendingMutation);
      }
    } catch (error) {
      const pending = state.pendingMutation;
      if (
        pending.type === "choice"
        && error instanceof ApiError
        && error.code === "IDEMPOTENCY_KEY_CONFLICT"
      ) {
        clearPendingCreation(pending.creationRequestId);
        setState((current) => ({
          ...current,
          phase: "start-failed",
          pendingMutation: null,
          error: "Эту попытку начала игры нельзя восстановить. Начните новую игру.",
          retryAction: "reset",
          retryLabel: "Начать заново",
        }));
        return;
      }
      setState((current) => ({
        ...current,
        phase: "start-failed",
        error: error instanceof ApiError && error.kind === "RATE_LIMIT"
          ? "Слишком много начатых партий. Попробуйте позже."
          : pending.type === "choice"
            ? `Не удалось подтвердить начало игры. Ваш выбор ящика №${pending.box} сохранён.`
            : "Не удалось выполнить действие. Повторите прежний ход.",
        retryAction: "repeat-pending",
        retryLabel: pendingActionLabel(pending),
      }));
    } finally {
      mutationInFlight.current = false;
    }
  }, [boot, performChoice, performDecision, recover, resetRound, state.game, state.pendingMutation, state.retryAction]);

  return {
    state,
    selectBox,
    decide,
    retry,
    startNewGame: resetRound,
    refreshStats,
  };
}
