import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";

const gameId = "123e4567-e89b-12d3-a456-426614174000";
const nonce = "0011aaff";

const stats = {
  totalCompletedGames: 18_342,
  switch: { games: 10_152, wins: 6_781, losses: 3_371, winRate: 0.6679 },
  stay: { games: 8_190, wins: 2_719, losses: 5_471, winRate: 0.332 },
  theoretical: { switchWinRate: 2 / 3, stayWinRate: 1 / 3 },
  updatedAt: "2026-09-10T12:00:00Z",
};

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function installWorkingApi() {
  const commitment = await sha256(`v1:${gameId}:2:${nonce}`);
  const calls = { creates: 0, choiceBox: 0, strategy: "" };

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "https://joy-hub.ru").pathname;
    if (path === "/api/v1/health") {
      return jsonResponse({ status: "UP" });
    }
    if (path === "/api/v1/stats") {
      return jsonResponse(stats);
    }
    if (path === "/api/v1/games" && init?.method === "POST") {
      calls.creates += 1;
      return jsonResponse({ gameId, commitment }, 201);
    }
    if (path.endsWith("/choice")) {
      calls.choiceBox = JSON.parse(String(init?.body)).box;
      return jsonResponse({ selectedBox: 3, openedBox: 1, switchToBox: 2 });
    }
    if (path.endsWith("/decision")) {
      calls.strategy = JSON.parse(String(init?.body)).strategy;
      const switched = calls.strategy === "SWITCH";
      return jsonResponse({
        gameId,
        initialChoice: 3,
        openedBox: 1,
        finalChoice: switched ? 2 : 3,
        strategy: calls.strategy,
        keyBox: 2,
        won: switched,
        nonce,
        commitment,
      });
    }
    return jsonResponse({ detail: "not found" }, 404);
  }));

  return calls;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("Monty Hall application", () => {
  it("selects a box, reveals an empty box, switches and verifies the result", async () => {
    const calls = await installWorkingApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    expect(await screen.findByRole("button", { name: "Ящик 1: пусто" })).toBeDisabled();
    expect(screen.getByText(/В ящике №1 ключей нет/)).toBeInTheDocument();
    expect(calls.choiceBox).toBe(3);

    await user.click(screen.getByRole("button", { name: "Поменять на ящик №2" }));

    expect(await screen.findByText("🎉 Вы выиграли!")).toBeInTheDocument();
    expect(screen.getByText("Вы поменяли выбор: №3 → №2.")).toBeInTheDocument();
    expect(calls.strategy).toBe("SWITCH");
    expect(await screen.findByText("Честность игры проверена")).toBeInTheDocument();
  });

  it("sends STAY and displays a losing result", async () => {
    const calls = await installWorkingApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Оставить ящик №3" }));

    expect(await screen.findByText("Не повезло 🙂")).toBeInTheDocument();
    expect(screen.getByText("Вы оставили ящик №3.")).toBeInTheDocument();
    expect(screen.getByText("Ключи были в ящике №2.")).toBeInTheDocument();
    expect(calls.strategy).toBe("STAY");
  });

  it("shows public per-strategy statistics", async () => {
    await installWorkingApi();
    render(<App />);

    expect(await screen.findByText((_, node) => node?.classList.contains("total-games") ?? false)).toHaveTextContent("18 342");
    expect(screen.getByText("66,8%")).toBeInTheDocument();
    expect(screen.getByText("33,2%")).toBeInTheDocument();
    expect(screen.getByText("Статистика по завершённым играм. Считаются партии, а не уникальные игроки.")).toBeInTheDocument();
  });

  it("shows a finite unavailable state and retries", async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError("offline"));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Игровой сервер временно недоступен" })).toBeInTheDocument();

    const commitment = await sha256(`v1:${gameId}:2:${nonce}`);
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), "https://joy-hub.ru").pathname;
      if (path === "/api/v1/health") return jsonResponse({ status: "UP" });
      if (path === "/api/v1/games") return jsonResponse({ gameId, commitment }, 201);
      return jsonResponse(stats);
    });
    await user.click(screen.getByRole("button", { name: "Попробовать снова" }));

    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
  });

  it("starts another game without reloading the page", async () => {
    const calls = await installWorkingApi();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Выбрать ящик 3" }));
    await user.click(await screen.findByRole("button", { name: "Поменять на ящик №2" }));
    await user.click(await screen.findByRole("button", { name: "Сыграть ещё раз" }));

    await waitFor(() => expect(calls.creates).toBe(2));
    expect(await screen.findByRole("button", { name: "Выбрать ящик 1" })).toBeEnabled();
  });
});
