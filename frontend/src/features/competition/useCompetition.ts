import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../monty-hall/api";
import { verifyCommitment, type FairnessStatus } from "../monty-hall/fairness";
import type { BoxNumber, ChoiceResult, CompletedGame, CreatedGame, Strategy } from "../monty-hall/types";
import {
  abandonCompetitionRun, createCompetitionRound, getCompetitionMe,
  makeCompetitionChoice, makeCompetitionDecision, saveCompetitionProfile,
  startCompetitionRun,
} from "./api";
import type { CompetitionMe, CompetitionRun } from "./types";
import { normalizeCompetitionName } from "./name";
import { streak } from "./format";

export const COMPETITION_START_STORAGE_KEY = "joyhub.competition.pending-start.v1";
export const COMPETITION_COMMAND_STORAGE_KEY = "joyhub.competition.pending-command.v1";

type Phase = "loading" | "profile" | "lobby" | "starting" | "ready" | "creating-round"
  | "choosing" | "choice-made" | "deciding" | "round-completed" | "run-ended"
  | "blocked" | "session-lost";

interface CommandJournal {
  version: 1;
  runId: string;
  roundNumber: number;
  creationRequestId: string;
  gameId?: string;
  commitment?: string;
  box: BoxNumber;
  strategy?: Strategy;
}

export interface CompetitionState {
  phase: Phase;
  me: CompetitionMe | null;
  run: CompetitionRun | null;
  game: CreatedGame | null;
  choice: ChoiceResult | null;
  result: CompletedGame | null;
  fairness: FairnessStatus | "original-missing" | null;
  error: string | null;
  fallbackShare: string | null;
  newRecord: boolean;
}

const initial: CompetitionState = {
  phase: "loading", me: null, run: null, game: null, choice: null,
  result: null, fairness: null, error: null, fallbackShare: null,
  newRecord: false,
};

function readJournal(): CommandJournal | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(COMPETITION_COMMAND_STORAGE_KEY) ?? "null") as CommandJournal | null;
    if (!value || value.version !== 1 || !value.runId || !value.creationRequestId
      || ![1, 2, 3].includes(value.box) || value.roundNumber < 1) return null;
    return value;
  } catch { return null; }
}

function writeJournal(value: CommandJournal): void {
  sessionStorage.setItem(COMPETITION_COMMAND_STORAGE_KEY, JSON.stringify(value));
}

function clearJournal(): void {
  sessionStorage.removeItem(COMPETITION_COMMAND_STORAGE_KEY);
}

export function useCompetition(enabled: boolean) {
  const [state, setState] = useState<CompetitionState>(initial);
  const busy = useRef(false);
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  const applyMe = useCallback((me: CompetitionMe) => {
    if (!me.authenticated) {
      const hadPrivateState = readJournal() !== null
        || sessionStorage.getItem(COMPETITION_START_STORAGE_KEY) !== null;
      setState({
        ...initial,
        phase: hadPrivateState ? "session-lost" : "profile",
        me,
        error: hadPrivateState
          ? "Сессия участника потеряна. Старый рекорд нельзя присвоить новому профилю."
          : null,
      });
      return;
    }
    const run = me.run ?? null;
    const round = me.round;
    if (!run) {
      const pendingStart = sessionStorage.getItem(COMPETITION_START_STORAGE_KEY);
      setState({
        ...initial,
        phase: "lobby",
        me,
        error: pendingStart ? "Начало попытки не подтверждено. Повтор использует тот же безопасный ключ." : null,
      });
      return;
    }
    if (run.status !== "ACTIVE") {
      const completed = round?.game.state === "COMPLETED" ? round.game : null;
      const journal = readJournal();
      const original = completed && journal?.gameId === completed.gameId && journal.commitment
        ? { gameId: journal.gameId, commitment: journal.commitment } : null;
      setState((current) => ({
        ...initial, phase: "run-ended", me, run,
        game: completed ? { gameId: completed.gameId, commitment: completed.commitment } : null,
        result: completed ?? current.result,
        fairness: completed ? original ? "checking" : "original-missing" : null,
      }));
      clearJournal();
      if (completed && original) {
        void verifyCommitment(completed, original).then((fairness) => setState((current) => ({ ...current, fairness })));
      }
      return;
    }
    if (!round) {
      setState({ ...initial, phase: "ready", me, run });
      return;
    }
    const snapshot = round.game;
    const game = { gameId: snapshot.gameId, commitment: snapshot.commitment };
    if (snapshot.state === "CREATED") {
      const journal = readJournal();
      setState({
        ...initial, me, run, game,
        phase: journal?.runId === run.runId && journal.roundNumber === round.roundNumber ? "blocked" : "ready",
        error: journal ? `Ваш выбор ящика №${journal.box} сохранён. Повторите прежний ход.` : null,
      });
      return;
    }
    if (snapshot.state === "CHOICE_MADE") {
      setState({
        ...initial, phase: "choice-made", me, run, game,
        choice: {
          selectedBox: snapshot.initialChoice,
          openedBox: snapshot.openedBox,
          switchToBox: snapshot.switchToBox,
        },
      });
      return;
    }
    const journal = readJournal();
    const original = journal?.gameId === snapshot.gameId && journal.commitment
      ? { gameId: journal.gameId, commitment: journal.commitment } : null;
    setState({
      ...initial, phase: "round-completed", me, run, game, result: snapshot,
      fairness: original ? "checking" : "original-missing",
    });
    clearJournal();
    if (original) {
      void verifyCommitment(snapshot, original).then((fairness) => setState((current) => ({ ...current, fairness })));
    }
  }, []);

  const refresh = useCallback(async () => {
    if (!enabledRef.current) return null;
    try {
      const me = await getCompetitionMe();
      if (me.run) sessionStorage.removeItem(COMPETITION_START_STORAGE_KEY);
      applyMe(me);
      return me;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setState({ ...initial, phase: "session-lost", error: "Сессия участника потеряна. Старый рекорд нельзя присвоить новому профилю." });
      } else {
        setState((current) => ({ ...current, phase: "blocked", error: "Не удалось восстановить состояние соревнования." }));
      }
      return null;
    }
  }, [applyMe]);

  useEffect(() => {
    if (!enabled) return;
    setState((current) => ({ ...current, phase: "loading", error: null }));
    void refresh();
  }, [enabled, refresh]);

  const saveProfile = useCallback(async (displayName: string) => {
    if (busy.current) return;
    busy.current = true;
    setState((current) => ({ ...current, error: null }));
    try {
      await saveCompetitionProfile(normalizeCompetitionName(displayName));
      await refresh();
    } catch (error) {
      setState((current) => ({ ...current, error: error instanceof ApiError && error.kind === "CLIENT"
        ? "Имя: 2–20 букв, цифр, пробелов, дефисов или подчёркиваний."
        : "Не удалось сохранить имя. Попробуйте ещё раз." }));
    } finally { busy.current = false; }
  }, [refresh]);

  const startRun = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    let requestId = sessionStorage.getItem(COMPETITION_START_STORAGE_KEY);
    if (!requestId) {
      requestId = crypto.randomUUID();
      sessionStorage.setItem(COMPETITION_START_STORAGE_KEY, requestId);
    }
    setState((current) => ({ ...current, phase: "starting", error: null }));
    try {
      const response = await startCompetitionRun(requestId);
      sessionStorage.removeItem(COMPETITION_START_STORAGE_KEY);
      clearJournal();
      setState((current) => ({ ...current, phase: "ready", run: response.run, result: null, choice: null, game: null }));
    } catch (error) {
      setState((current) => ({ ...current, phase: "lobby", error: error instanceof ApiError && error.code === "COMPETITION_DAILY_LIMIT"
        ? "Все попытки на сегодня использованы. Новые попытки появятся в 00:00 МСК."
        : "Не удалось подтвердить начало попытки. Повтор использует тот же безопасный ключ." }));
    } finally { busy.current = false; }
  }, []);

  const recoverMutation = useCallback(async () => {
    const me = await refresh();
    return me;
  }, [refresh]);

  const performChoice = useCallback(async (journal: CommandJournal, game: CreatedGame) => {
    setState((current) => ({ ...current, phase: "choosing", game, error: null }));
    try {
      const choice = await makeCompetitionChoice(game.gameId, journal.box);
      setState((current) => ({ ...current, phase: "choice-made", game, choice, error: null }));
    } catch {
      const recovered = await recoverMutation();
      if (!recovered || recovered.round?.game.state === "CREATED") {
        setState((current) => ({ ...current, phase: "blocked", game,
          error: `Не удалось подтвердить выбор. Можно повторить только ящик №${journal.box}.` }));
      }
    }
  }, [recoverMutation]);

  const selectBox = useCallback(async (box: BoxNumber) => {
    if (busy.current || state.phase !== "ready" || !state.run) return;
    busy.current = true;
    const existingCreated = state.me?.run?.runId === state.run.runId
      && state.me.round?.game.state === "CREATED" ? state.me.round : null;
    const journal: CommandJournal = {
      version: 1, runId: state.run.runId,
      roundNumber: existingCreated?.roundNumber ?? state.run.score + 1,
      creationRequestId: crypto.randomUUID(), box,
      gameId: existingCreated?.game.gameId,
      commitment: existingCreated?.game.commitment,
    };
    writeJournal(journal);
    setState((current) => ({ ...current, phase: "creating-round", error: null }));
    try {
      let game: CreatedGame;
      if (journal.gameId && journal.commitment) {
        game = { gameId: journal.gameId, commitment: journal.commitment };
      } else {
        const created = await createCompetitionRound(
          journal.runId, journal.creationRequestId, journal.roundNumber,
        );
        game = { gameId: created.gameId, commitment: created.commitment };
        writeJournal({ ...journal, gameId: game.gameId, commitment: game.commitment });
      }
      await performChoice({ ...journal, gameId: game.gameId, commitment: game.commitment }, game);
    } catch {
      const recovered = await recoverMutation();
      if (!recovered?.round) {
        setState((current) => ({ ...current, phase: "blocked",
          error: `Не удалось подтвердить раунд. Ваш выбор ящика №${box} сохранён.` }));
      }
    } finally { busy.current = false; }
  }, [performChoice, recoverMutation, state.me?.round, state.phase, state.run]);

  const retry = useCallback(async () => {
    const journal = readJournal();
    if (!journal || busy.current) {
      await refresh();
      return;
    }
    busy.current = true;
    try {
      let game = journal.gameId && journal.commitment
        ? { gameId: journal.gameId, commitment: journal.commitment } : null;
      if (!game) {
        const created = await createCompetitionRound(journal.runId, journal.creationRequestId, journal.roundNumber);
        game = { gameId: created.gameId, commitment: created.commitment };
        writeJournal({ ...journal, gameId: game.gameId, commitment: game.commitment });
      }
      if (journal.strategy) {
        const result = await makeCompetitionDecision(game.gameId, journal.strategy);
        clearJournal();
        setState((current) => ({ ...current, phase: result.competition?.status === "LOST" ? "run-ended" : "round-completed",
          result, run: result.competition ?? current.run, game, error: null, fairness: "checking" }));
        void verifyCommitment(result, game).then((fairness) => setState((current) => ({ ...current, fairness })));
      } else {
        await performChoice(journal, game);
      }
    } catch {
      await recoverMutation();
    } finally { busy.current = false; }
  }, [performChoice, recoverMutation, refresh]);

  const decide = useCallback(async (strategy: Strategy) => {
    if (busy.current || state.phase !== "choice-made" || !state.game) return;
    busy.current = true;
    const journal = readJournal();
    if (journal) writeJournal({ ...journal, strategy });
    setState((current) => ({ ...current, phase: "deciding", error: null }));
    try {
      const result = await makeCompetitionDecision(state.game.gameId, strategy);
      const original = state.game;
      clearJournal();
      setState((current) => ({ ...current,
        phase: result.competition?.status === "LOST" ? "run-ended" : "round-completed",
        result, run: result.competition ?? current.run, fairness: "checking",
        newRecord: (result.competition?.allTimeBest ?? 0) > (current.run?.allTimeBest ?? current.me?.allTimeBest ?? 0),
      }));
      void verifyCommitment(result, original).then((fairness) => setState((current) => ({ ...current, fairness })));
    } catch {
      const recovered = await recoverMutation();
      if (!recovered?.round || recovered.round.game.state !== "COMPLETED") {
        setState((current) => ({ ...current, phase: "blocked",
          error: "Не удалось подтвердить решение. Альтернативная стратегия недоступна до восстановления." }));
      }
    } finally { busy.current = false; }
  }, [recoverMutation, state.game, state.phase]);

  const nextRound = useCallback(() => {
    clearJournal();
    setState((current) => ({ ...current, phase: "ready", game: null, choice: null, result: null, fairness: null, error: null }));
  }, []);

  const abandon = useCallback(async () => {
    if (!state.run || busy.current || !window.confirm("Завершить попытку? Использованный слот не вернётся.")) return;
    busy.current = true;
    try {
      const run = await abandonCompetitionRun(state.run.runId);
      clearJournal();
      setState((current) => ({ ...current, phase: "run-ended", run, error: null }));
    } catch { setState((current) => ({ ...current, error: "Не удалось завершить попытку." })); }
    finally { busy.current = false; }
  }, [state.run]);

  const share = useCallback(async () => {
    const score = Math.max(state.me?.allTimeBest ?? 0, state.run?.allTimeBest ?? 0);
    const text = `Моя серия на Joy Hub — ${streak(score)}. Попробуешь больше? https://joy-hub.ru`;
    try {
      if (navigator.share) {
        await navigator.share({ text, url: "https://joy-hub.ru" });
      } else if (navigator.clipboard) {
        await navigator.clipboard.writeText(text);
        setState((current) => ({ ...current, fallbackShare: "Результат скопирован" }));
      } else {
        setState((current) => ({ ...current, fallbackShare: text }));
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return;
      setState((current) => ({ ...current, fallbackShare: text }));
    }
  }, [state.me?.allTimeBest, state.run?.allTimeBest]);

  const resetProfile = useCallback(() => {
    clearJournal();
    sessionStorage.removeItem(COMPETITION_START_STORAGE_KEY);
    setState((current) => ({ ...current, phase: "profile", error: null }));
  }, []);

  return { state, refresh, saveProfile, startRun, selectBox, decide, retry, nextRound, abandon, share, resetProfile };
}
