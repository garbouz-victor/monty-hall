import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { PENDING_CREATION_STORAGE_KEY } from "../features/monty-hall/useMontyHallGame";

const gameId = "123e4567-e89b-12d3-a456-426614174000";
const nonce = "0011aaff";

const stats = {
  totalCompletedGames: 18_342,
  switch: { games: 10_152, wins: 6_781, losses: 3_371, winRate: 0.6679 },
  stay: { games: 8_190, wins: 2_719, losses: 5_471, winRate: 0.332 },
  theoretical: { switchWinRate: 2 / 3, stayWinRate: 1 / 3 },
  updatedAt: "2026-09-10T12:00:00Z",
};

type RecoveryMode = "created" | "choice-made" | "completed" | "offline" | "missing" | null;

interface ApiOptions {
  loseChoiceResponse?: boolean;
  loseDecisionResponse?: boolean;
  conflictChoiceResponse?: boolean;
  loseCreateResponseOnce?: boolean;
  recovery?: RecoveryMode;
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function installApi(options: ApiOptions = {}) {
  const commitment = await sha256(`v1:${gameId}:2:${nonce}`);
  const calls = {
    creates: 0,
    createKeys: [] as string[],
    choices: [] as number[],
    choiceGameIds: [] as string[],
    strategies: [] as string[],
    recoveries: 0,
  };

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "https://joy-hub.ru").pathname;
    if (path === "/api/v1/health") return jsonResponse({ status: "UP" });
    if (path === "/api/v1/stats") return jsonResponse(stats);

    if (path === "/api/v1/games" && init?.method === "POST") {
      calls.creates += 1;
      calls.createKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
      if (options.loseCreateResponseOnce && calls.creates === 1) {
        throw new TypeError("create response lost after commit");
      }
      return jsonResponse({ gameId, commitment }, 201);
    }
    if (path.endsWith("/choice")) {
      calls.choices.push(JSON.parse(String(init?.body)).box);
      calls.choiceGameIds.push(path.split("/")[4] ?? "");
      if (options.loseChoiceResponse && calls.choices.length === 1) {
        throw new DOMException("choice response timed out", "AbortError");
      }
      if (options.conflictChoiceResponse && calls.choices.length === 1) {
        return jsonResponse({ detail: "already saved", code: "INVALID_GAME_STATE" }, 409);
      }
      return jsonResponse({ selectedBox: 3, openedBox: 1, switchToBox: 2 });
    }
    if (path.endsWith("/decision")) {
      const strategy = JSON.parse(String(init?.body)).strategy as "SWITCH" | "STAY";
      calls.strategies.push(strategy);
      if (options.loseDecisionResponse && calls.strategies.length === 1) {
        throw new TypeError("decision response lost");
      }
      return jsonResponse(completedGame(strategy, commitment));
    }
    if (path === `/api/v1/games/${gameId}` && (!init?.method || init.method === "GET")) {
      calls.recoveries += 1;
      if (options.recovery === "offline") throw new TypeError("recovery unavailable");
      if (options.recovery === "missing") return jsonResponse({ detail: "not found" }, 404);
      if (options.recovery === "created") {
        return jsonResponse({ gameId, state: "CREATED", commitment });
      }
      if (options.recovery === "choice-made") {
        return jsonResponse({
          gameId,
          state: "CHOICE_MADE",
          commitment,
          initialChoice: 3,
          openedBox: 1,
          switchToBox: 2,
        });
      }
      if (options.recovery === "completed") {
        return jsonResponse({
          ...completedGame("SWITCH", commitment),
          state: "COMPLETED",
        });
      }
    }
    return jsonResponse({ detail: "not found" }, 404);
  }));

  return calls;
}

function completedGame(strategy: "SWITCH" | "STAY", commitment: string) {
  return {
    gameId,
    initialChoice: 3,
    openedBox: 1,
    finalChoice: strategy === "SWITCH" ? 2 : 3,
    strategy,
    keyBox: 2,
    won: strategy === "SWITCH",
    nonce,
    commitment,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("Monty Hall application", () => {
  it("does not create a server game until the first box is selected", async () => {
    const calls = await installApi();
    render(<App />);

    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(calls.creates).toBe(0);
    expect(calls.createKeys).toEqual([]);
    expect(calls.choices).toEqual([]);
  });

  it("creates a game, selects a box, switches and verifies the result", async () => {
    const calls = await installApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    expect(await screen.findByRole("button", { name: "Ящик 1: пусто" })).toBeDisabled();
    expect(screen.getByText(/В ящике №1 ключей нет/)).toBeInTheDocument();
    expect(calls.creates).toBe(1);
    expect(calls.createKeys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls.choices).toEqual([3]);

    await user.click(screen.getByRole("button", { name: "Поменять на ящик №2" }));

    expect(await screen.findByText("🎉 Вы выиграли!")).toBeInTheDocument();
    expect(screen.getByText("Вы поменяли выбор: №3 → №2.")).toBeInTheDocument();
    expect(calls.strategies).toEqual(["SWITCH"]);
    expect(await screen.findByText("Честность игры проверена")).toBeInTheDocument();
  });

  it("sends STAY and displays a losing result", async () => {
    const calls = await installApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №3" }));

    expect(await screen.findByText("Не повезло 🙂")).toBeInTheDocument();
    expect(screen.getByText("Вы оставили ящик №3.")).toBeInTheDocument();
    expect(screen.getByText("Ключи были в ящике №2.")).toBeInTheDocument();
    expect(calls.strategies).toEqual(["STAY"]);
  });

  it("restores CHOICE_MADE when the choice response is lost", async () => {
    const calls = await installApi({ loseChoiceResponse: true, recovery: "choice-made" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));

    expect(await screen.findByRole("button", { name: "Поменять на ящик №2" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 2" })).toBeDisabled();
    expect(calls.choices).toEqual([3]);
    expect(calls.recoveries).toBe(1);
  });

  it("reconciles a 409 choice response with server state", async () => {
    const calls = await installApi({ conflictChoiceResponse: true, recovery: "choice-made" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));

    expect(await screen.findByRole("button", { name: "Поменять на ящик №2" })).toBeEnabled();
    expect(calls.choices).toEqual([3]);
    expect(calls.recoveries).toBe(1);
  });

  it("repeats only the original box when recovery still reports CREATED", async () => {
    const calls = await installApi({ loseChoiceResponse: true, recovery: "created" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    expect(await screen.findByRole("button", { name: "Повторить выбор ящика №3" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 1" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 2" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Повторить выбор ящика №3" }));
    expect(await screen.findByRole("button", { name: "Поменять на ящик №2" })).toBeEnabled();
    expect(calls.choices).toEqual([3, 3]);
  });

  it("retries a lost create response with the same key and original box", async () => {
    const calls = await installApi({ loseCreateResponseOnce: true });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    expect(await screen.findByText("Не удалось подтвердить начало игры. Ваш выбор ящика №3 сохранён.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Выбрать ящик 1" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Повторить выбор ящика №3" }));
    expect(await screen.findByRole("button", { name: "Поменять на ящик №2" })).toBeEnabled();
    expect(calls.creates).toBe(2);
    expect(calls.createKeys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls.createKeys[1]).toBe(calls.createKeys[0]);
    expect(calls.choices).toEqual([3]);
    expect(calls.choiceGameIds).toEqual([gameId]);
    expect(sessionStorage.getItem(PENDING_CREATION_STORAGE_KEY)).toBeNull();
  });

  it("restores a pending create from sessionStorage after a tab reload", async () => {
    const creationRequestId = "04f1986d-e89b-42d3-a456-426614174999";
    sessionStorage.setItem(PENDING_CREATION_STORAGE_KEY, JSON.stringify({ creationRequestId, box: 3 }));
    const calls = await installApi();
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("Не удалось подтвердить начало игры. Ваш выбор ящика №3 сохранён.")).toBeInTheDocument();
    expect(calls.creates).toBe(0);
    expect(screen.getByRole("button", { name: "Выбрать ящик 1" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Повторить выбор ящика №3" }));
    expect(await screen.findByRole("button", { name: "Поменять на ящик №2" })).toBeEnabled();
    expect(calls.createKeys).toEqual([creationRequestId]);
    expect(calls.choices).toEqual([3]);
    expect(sessionStorage.getItem(PENDING_CREATION_STORAGE_KEY)).toBeNull();
  });

  it("restores COMPLETED and verifies fairness when a SWITCH response is lost", async () => {
    const calls = await installApi({ loseDecisionResponse: true, recovery: "completed" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Поменять на ящик №2" }));

    expect(await screen.findByText("🎉 Вы выиграли!")).toBeInTheDocument();
    expect(screen.getByText("Вы поменяли выбор: №3 → №2.")).toBeInTheDocument();
    expect(await screen.findByText("Честность игры проверена")).toBeInTheDocument();
    expect(calls.strategies).toEqual(["SWITCH"]);
    expect(calls.recoveries).toBe(1);
    expect(screen.queryByRole("button", { name: "Оставить ящик №3" })).not.toBeInTheDocument();
  });

  it("hides both strategy buttons when decision recovery is unavailable", async () => {
    const calls = await installApi({ loseDecisionResponse: true, recovery: "offline" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Поменять на ящик №2" }));

    expect(await screen.findByText("Не удалось проверить, был ли ваш ход сохранён.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Повторить проверку" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Поменять на ящик №2" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Оставить ящик №3" })).not.toBeInTheDocument();
    expect(calls.strategies).toEqual(["SWITCH"]);
  });

  it("keeps the original choice locked when mutation and recovery are unavailable", async () => {
    const calls = await installApi({ loseChoiceResponse: true, recovery: "offline" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));

    expect(await screen.findByText("Не удалось проверить, был ли ваш ход сохранён.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Повторить проверку" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 1" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 2" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Выбрать ящик 3" })).toBeDisabled();
    expect(calls.choices).toEqual([3]);
  });

  it("offers a clean restart when recovery returns 404", async () => {
    await installApi({ loseChoiceResponse: true, recovery: "missing" });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    expect(await screen.findByText("Эту партию не удалось восстановить. Начните новую игру.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Начать заново" }));
    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
  });

  it("shows public per-strategy statistics", async () => {
    await installApi();
    render(<App />);

    expect(await screen.findByText((_, node) => node?.classList.contains("total-games") ?? false)).toHaveTextContent("18 342");
    expect(screen.getByText("66,8%")).toBeInTheDocument();
    expect(screen.getByText("33,2%")).toBeInTheDocument();
    expect(screen.getByText("Статистика по завершённым играм. Считаются партии, а не уникальные игроки.")).toBeInTheDocument();
  });

  it("shows a finite unavailable state and retries without creating a game", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(jsonResponse(stats));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Игровой сервер временно недоступен" })).toBeInTheDocument();

    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), "https://joy-hub.ru").pathname;
      if (path === "/api/v1/health") return jsonResponse({ status: "UP" });
      if (path === "/api/v1/stats") return jsonResponse(stats);
      return jsonResponse({ detail: "unexpected request" }, 500);
    });
    await user.click(screen.getByRole("button", { name: "Попробовать снова" }));

    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(fetchMock).not.toHaveBeenCalledWith("/api/v1/games", expect.anything());
  });

  it("resets locally and creates the next game only after another box selection", async () => {
    const calls = await installApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Поменять на ящик №2" }));
    await user.click(await screen.findByRole("button", { name: "Сыграть ещё раз" }));

    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
    expect(calls.creates).toBe(1);

    await user.click(screen.getByRole("button", { name: "Выбрать ящик 3" }));
    await waitFor(() => expect(calls.creates).toBe(2));
    expect(calls.createKeys[1]).not.toBe(calls.createKeys[0]);
  });
});
