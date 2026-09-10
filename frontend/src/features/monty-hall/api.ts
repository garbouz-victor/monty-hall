import type {
  BoxNumber,
  ChoiceResult,
  CompletedGame,
  CreatedGame,
  PublicStats,
  Strategy,
} from "./types";

const REQUEST_TIMEOUT_MS = 7_000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(path, {
      ...init,
      credentials: "same-origin",
      headers: init?.body
        ? { "Content-Type": "application/json", ...init.headers }
        : init?.headers,
      signal: controller.signal,
    });

    if (!response.ok) {
      const problem = await response.json().catch(() => null) as { detail?: string; code?: string } | null;
      throw new ApiError(problem?.detail ?? "Запрос не выполнен", response.status, problem?.code);
    }
    return await response.json() as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ApiError("Сервер отвечает слишком долго. Попробуйте ещё раз.");
    }
    throw new ApiError("Не удалось связаться с игровым сервером.");
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function checkHealth(): Promise<void> {
  const health = await request<{ status: string }>("/api/v1/health");
  if (health.status !== "UP") {
    throw new ApiError("Игровой сервер временно недоступен.");
  }
}

export function createGame(): Promise<CreatedGame> {
  return request<CreatedGame>("/api/v1/games", { method: "POST" });
}

export function makeChoice(gameId: string, box: BoxNumber): Promise<ChoiceResult> {
  return request<ChoiceResult>(`/api/v1/games/${gameId}/choice`, {
    method: "POST",
    body: JSON.stringify({ box }),
  });
}

export function makeDecision(gameId: string, strategy: Strategy): Promise<CompletedGame> {
  return request<CompletedGame>(`/api/v1/games/${gameId}/decision`, {
    method: "POST",
    body: JSON.stringify({ strategy }),
  });
}

export function getStats(): Promise<PublicStats> {
  return request<PublicStats>("/api/v1/stats");
}

