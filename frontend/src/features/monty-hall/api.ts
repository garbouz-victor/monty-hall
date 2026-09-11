import type {
  BoxNumber,
  ChoiceResult,
  CompletedGame,
  CreatedGame,
  GameStateResponse,
  PublicStats,
  Strategy,
} from "./types";

const REQUEST_TIMEOUT_MS = 7_000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly kind: ApiErrorKind,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export type ApiErrorKind =
  | "NETWORK"
  | "TIMEOUT"
  | "CONFLICT"
  | "NOT_FOUND"
  | "RATE_LIMIT"
  | "SERVER"
  | "CLIENT";

function httpErrorKind(status: number): ApiErrorKind {
  if (status === 404) return "NOT_FOUND";
  if (status === 409) return "CONFLICT";
  if (status === 429) return "RATE_LIMIT";
  if (status >= 500) return "SERVER";
  return "CLIENT";
}

export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
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
      throw new ApiError(
        problem?.detail ?? "Запрос не выполнен",
        httpErrorKind(response.status),
        response.status,
        problem?.code,
      );
    }
    return await response.json() as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ApiError("Сервер отвечает слишком долго. Попробуйте ещё раз.", "TIMEOUT");
    }
    throw new ApiError("Не удалось связаться с игровым сервером.", "NETWORK");
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function checkHealth(): Promise<void> {
  const health = await apiRequest<{ status: string }>("/api/v1/health");
  if (health.status !== "UP") {
    throw new ApiError("Игровой сервер временно недоступен.", "SERVER", 503);
  }
}

export function createGame(creationRequestId: string): Promise<CreatedGame> {
  return apiRequest<CreatedGame>("/api/v1/games", {
    method: "POST",
    headers: { "Idempotency-Key": creationRequestId },
  });
}

export function makeChoice(gameId: string, box: BoxNumber): Promise<ChoiceResult> {
  return apiRequest<ChoiceResult>(`/api/v1/games/${gameId}/choice`, {
    method: "POST",
    headers: { "X-JoyHub-CSRF": "1" },
    body: JSON.stringify({ box }),
  });
}

export function makeDecision(gameId: string, strategy: Strategy): Promise<CompletedGame> {
  return apiRequest<CompletedGame>(`/api/v1/games/${gameId}/decision`, {
    method: "POST",
    headers: { "X-JoyHub-CSRF": "1" },
    body: JSON.stringify({ strategy }),
  });
}

export function getGameState(gameId: string): Promise<GameStateResponse> {
  return apiRequest<GameStateResponse>(`/api/v1/games/${gameId}`);
}

export function getStats(): Promise<PublicStats> {
  return apiRequest<PublicStats>("/api/v1/stats");
}
