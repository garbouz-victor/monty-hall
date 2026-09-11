import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../../app/App";

const stats = {
  totalCompletedGames: 0,
  switch: { games: 0, wins: 0, losses: 0, winRate: 0 },
  stay: { games: 0, wins: 0, losses: 0, winRate: 0 },
  theoretical: { switchWinRate: 2 / 3, stayWinRate: 1 / 3 },
  updatedAt: "2026-09-10T12:00:00Z",
};

async function digest(gameId: string, keyBox: number, nonce: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`v1:${gameId}:${keyBox}:${nonce}`));
  return Array.from(new Uint8Array(bytes), (value) => value.toString(16).padStart(2, "0")).join("");
}

async function installCompetitionApi(options: {
  loseDecision?: boolean;
  holdFirstChoice?: boolean;
  loseFirstStart?: boolean;
  leaderboardUnavailable?: boolean;
  quotaExhausted?: boolean;
  dailyLimit?: number;
} = {}) {
  let authenticated = false;
  let runStarted = false;
  let score = 0;
  let roundNumber = 0;
  let serverRound: Record<string, unknown> | null = null;
  let releaseChoice: (() => void) | null = null;
  const choiceGate = options.holdFirstChoice ? new Promise<void>((resolve) => { releaseChoice = resolve; }) : null;
  const calls = { profiles: 0, starts: 0, startKeys: [] as string[], rounds: 0, choices: 0, decisions: 0, leaderboard: 0,
    releaseChoice: () => releaseChoice?.() };
  const gameId = (round: number) => `123e4567-e89b-42d3-a456-42661417400${round}`;
  const nonce = "0011aaff";

  const run = (status = "ACTIVE") => ({
    runId: "223e4567-e89b-42d3-a456-426614174000", status, score,
    attemptNumber: 1, competitionDate: "2026-09-10", startedAt: "2026-09-10T12:00:00Z",
    expiresAt: "2026-09-10T21:00:00Z", rulesVersion: 1, remainingAttempts: options.quotaExhausted ? 0 : 4,
    todayBest: score, allTimeBest: score, todayRank: score ? 1 : undefined, allTimeRank: score ? 1 : undefined,
  });
  const me = () => ({
    authenticated, serverTime: "2026-09-10T12:00:00Z", timezone: "Europe/Moscow",
    competitionDate: "2026-09-10", dailyAttemptLimit: options.dailyLimit ?? 5,
    remainingAttempts: options.quotaExhausted ? 0 : (options.dailyLimit ?? 5),
    player: authenticated ? { publicPlayerId: "p1", publicTag: "JH-ABC123", displayName: "Виктор" } : undefined,
    todayBest: score, allTimeBest: score, todayRank: score ? 1 : undefined, allTimeRank: score ? 1 : undefined,
    run: runStarted ? run((serverRound as { won?: boolean } | null)?.won === false ? "LOST" : "ACTIVE") : undefined,
    round: serverRound ? { roundNumber, game: serverRound } : undefined,
  });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "https://joy-hub.ru");
    const path = url.pathname;
    if (path === "/api/v1/health") return response({ status: "UP" });
    if (path === "/api/v1/stats") return response(stats);
    if (path === "/api/v1/competition/me") return response(me());
    if (path === "/api/v1/competition/profile") {
      calls.profiles += 1; authenticated = true;
      return response({ player: { publicPlayerId: "p1", publicTag: "JH-ABC123", displayName: "Виктор" } });
    }
    if (path === "/api/v1/competition/runs") {
      calls.starts += 1;
      calls.startKeys.push(new Headers(init?.headers).get("Idempotency-Key") ?? "");
      runStarted = true; serverRound = null; score = 0;
      if (options.loseFirstStart && calls.starts === 1) throw new TypeError("response lost");
      return response({ run: run(), replayed: false });
    }
    if (path.endsWith("/rounds")) {
      calls.rounds += 1; roundNumber = JSON.parse(String(init?.body)).expectedRoundNumber;
      const id = gameId(roundNumber);
      const commitment = await digest(id, 1, nonce);
      serverRound = { gameId: id, state: "CREATED", commitment };
      return response({ gameId: id, commitment, roundNumber, run: run(), replayed: false }, 201);
    }
    if (path.endsWith("/choice")) {
      calls.choices += 1;
      if (choiceGate && calls.choices === 1) {
        await choiceGate;
        throw new TypeError("tab closed before choice response");
      }
      const id = gameId(roundNumber);
      const commitment = await digest(id, 1, nonce);
      serverRound = { gameId: id, state: "CHOICE_MADE", commitment, initialChoice: 1, openedBox: 2, switchToBox: 3 };
      return response({ selectedBox: 1, openedBox: 2, switchToBox: 3 });
    }
    if (path.endsWith("/decision")) {
      calls.decisions += 1;
      const won = roundNumber === 1;
      if (won) score += 1;
      const id = gameId(roundNumber);
      const commitment = await digest(id, 1, nonce);
      serverRound = {
        gameId: id, state: "COMPLETED", initialChoice: 1, openedBox: 2, finalChoice: 1,
        strategy: "STAY", keyBox: won ? 1 : 3, won, nonce, commitment,
      };
      if (options.loseDecision && calls.decisions === 1) throw new TypeError("response lost");
      return response({ ...serverRound, competition: run(won ? "ACTIVE" : "LOST") });
    }
    if (path === "/api/v1/competition/leaderboard") {
      calls.leaderboard += 1;
      if (options.leaderboardUnavailable) throw new TypeError("leaderboard offline");
      return response({ period: url.searchParams.get("period"), serverTime: "2026-09-10T12:00:00Z",
        timezone: "Europe/Moscow", registeredProfiles: 1, participantsWithResult: score ? 1 : 0,
        entries: score ? [{ publicPlayerId: "p1", publicTag: "JH-ABC123", displayName: "Виктор", rank: 1, bestStreak: score, achievedAt: "2026-09-10T12:00:01Z" }] : [] });
    }
    return response({ detail: "not found" }, 404);
  }));
  return calls;
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function enterCompetition(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Соревноваться" }));
  await user.type(await screen.findByLabelText("Публичное имя"), "Виктор");
  await user.click(screen.getByRole("button", { name: "Сохранить профиль" }));
  await screen.findByRole("button", { name: "Начать попытку" });
}

describe("competition flow", () => {
  it("shows the server-configured daily attempt limit before profile creation", async () => {
    await installCompetitionApi({ dailyLimit: 7 });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Соревноваться" }));
    expect(screen.getByText("7 зачётных попыток в день по МСК")).toBeInTheDocument();
  });

  it("is opt-in, starts explicitly, increments only from backend and ends on loss", async () => {
    const calls = await installCompetitionApi();
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByRole("button", { name: "Соревноваться" })).toBeEnabled();
    expect(calls.starts).toBe(0);
    await enterCompetition(user);
    expect(calls.starts).toBe(0);
    expect(calls.rounds).toBe(0);

    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(calls.starts).toBe(1);
    expect(calls.rounds).toBe(0);

    await user.click(screen.getByRole("button", { name: "Выбрать ящик 1" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №1" }));
    expect(await screen.findByText("🔥 1 победа подряд")).toBeInTheDocument();
    expect(calls.decisions).toBe(1);

    await user.click(screen.getByRole("button", { name: "Следующий раунд" }));
    await user.click(screen.getByRole("button", { name: "Выбрать ящик 1" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №1" }));
    expect(await screen.findByText("Попытка завершена")).toBeInTheDocument();
    expect(screen.getByText(/Результат: 1 победа подряд/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Новая попытка" })).toBeEnabled();
    expect(calls.rounds).toBe(2);

    const shareDescriptor = Object.getOwnPropertyDescriptor(navigator, "share");
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    Object.defineProperty(navigator, "share", { configurable: true, value: undefined });
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
    await user.click(screen.getByRole("button", { name: "Поделиться" }));
    expect(screen.getByText(/Моя серия на Joy Hub — 1 победа подряд/)).toBeInTheDocument();
    if (shareDescriptor) Object.defineProperty(navigator, "share", shareDescriptor);
    else Reflect.deleteProperty(navigator, "share");
    if (clipboardDescriptor) Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
    else Reflect.deleteProperty(navigator, "clipboard");
  });

  it("treats native share cancellation as a neutral action", async () => {
    const calls = await installCompetitionApi();
    const abort = new DOMException("cancelled", "AbortError");
    const share = vi.fn().mockRejectedValue(abort);
    const descriptor = Object.getOwnPropertyDescriptor(navigator, "share");
    Object.defineProperty(navigator, "share", { configurable: true, value: share });
    const user = userEvent.setup();
    render(<App />);
    await enterCompetition(user);
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 1" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №1" }));
    await user.click(screen.getByRole("button", { name: "Следующий раунд" }));
    await user.click(screen.getByRole("button", { name: "Выбрать ящик 1" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №1" }));
    await user.click(await screen.findByRole("button", { name: "Поделиться" }));
    expect(share).toHaveBeenCalledOnce();
    expect(screen.queryByText(/Моя серия на Joy Hub/)).not.toBeInTheDocument();
    expect(calls.decisions).toBe(2);
    if (descriptor) Object.defineProperty(navigator, "share", descriptor);
    else Reflect.deleteProperty(navigator, "share");
  });

  it("retries an ambiguously started run with the same idempotency key", async () => {
    const calls = await installCompetitionApi({ loseFirstStart: true });
    const user = userEvent.setup();
    render(<App />);
    await enterCompetition(user);

    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    expect(await screen.findByText(/Не удалось подтвердить начало попытки/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));

    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(calls.startKeys).toHaveLength(2);
    expect(calls.startKeys[1]).toBe(calls.startKeys[0]);
    expect(sessionStorage.getItem("joyhub.competition.pending-start.v1")).toBeNull();
  });

  it("shows exhausted quota without starting anything", async () => {
    const calls = await installCompetitionApi({ quotaExhausted: true });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Соревноваться" }));
    await user.type(await screen.findByLabelText("Публичное имя"), "Виктор");
    await user.click(screen.getByRole("button", { name: "Сохранить профиль" }));

    expect(await screen.findByText(/Новые попытки появятся в 00:00 МСК/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Начать попытку" })).not.toBeInTheDocument();
    expect(calls.starts).toBe(0);
    expect(calls.rounds).toBe(0);
  });

  it("keeps gameplay available when the public leaderboard is offline", async () => {
    const calls = await installCompetitionApi({ leaderboardUnavailable: true });
    const user = userEvent.setup();
    render(<App />);
    await enterCompetition(user);

    expect(await screen.findByText(/Рейтинг временно недоступен/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Виктор/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(calls.starts).toBe(1);
  });

  it("recovers a real server-completed win after the decision response is lost", async () => {
    const calls = await installCompetitionApi({ loseDecision: true });
    const user = userEvent.setup();
    render(<App />);
    await enterCompetition(user);
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 1" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №1" }));

    expect(await screen.findByText("🔥 1 победа подряд")).toBeInTheDocument();
    expect(await screen.findByText("Честность игры проверена")).toBeInTheDocument();
    expect(calls.decisions).toBe(1);
  });

  it("restores the same committed round and original box after reload between create and choice", async () => {
    const calls = await installCompetitionApi({ holdFirstChoice: true });
    const user = userEvent.setup();
    const first = render(<App />);
    await enterCompetition(user);
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 1" }));
    await waitFor(() => expect(calls.choices).toBe(1));
    const journal = JSON.parse(sessionStorage.getItem("joyhub.competition.pending-command.v1") ?? "null");
    expect(journal.box).toBe(1);
    expect(journal.gameId).toBe("123e4567-e89b-42d3-a456-426614174001");
    first.unmount();

    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Продолжить попытку" }));
    expect(await screen.findByText("Ваш выбор ящика №1 сохранён. Повторите прежний ход.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить прежнее действие" }));
    expect(await screen.findByRole("button", { name: "Оставить ящик №1" })).toBeEnabled();
    expect(calls.rounds).toBe(1);
    expect(calls.choices).toBe(2);
    calls.releaseChoice();
  });

  it("switches leaderboard periods and pauses without abandoning the active run", async () => {
    const calls = await installCompetitionApi();
    const user = userEvent.setup();
    render(<App />);
    await enterCompetition(user);
    await user.click(screen.getByRole("button", { name: "Начать попытку" }));
    const today = await screen.findByRole("tab", { name: "Сегодня" });
    today.focus();
    await user.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "За всё время" })).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(calls.leaderboard).toBeGreaterThanOrEqual(2));
    await user.click(screen.getByRole("button", { name: "В обычную игру" }));
    expect(await screen.findByRole("button", { name: "Продолжить попытку" })).toBeEnabled();
    expect(calls.starts).toBe(1);
  });
});
