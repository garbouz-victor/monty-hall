import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PublicStats, StrategyStats } from "../monty-hall/types";
import { StatsSection } from "./StatsSection";

function strategy(games: number, wins: number): StrategyStats {
  return {
    games,
    wins,
    losses: games - wins,
    winRate: games === 0 ? 0 : wins / games,
  };
}

function publicStats(switchStats: StrategyStats, stayStats: StrategyStats): PublicStats {
  return {
    totalCompletedGames: switchStats.games + stayStats.games,
    switch: switchStats,
    stay: stayStats,
    theoretical: { switchWinRate: 2 / 3, stayWinRate: 1 / 3 },
    updatedAt: "2026-09-11T12:00:00Z",
  };
}

function renderStats(stats: PublicStats): void {
  render(<StatsSection stats={stats} loading={false} reveal onRetry={vi.fn()} />);
}

describe("public strategy statistics", () => {
  it("shows win rates with their independent denominators and theory", () => {
    renderStats(publicStats(strategy(10, 8), strategy(28, 6)));

    expect(screen.getByText("80,0%")).toBeInTheDocument();
    expect(screen.getByText("8 побед из 10 игр")).toBeInTheDocument();
    expect(screen.getByText("21,4%")).toBeInTheDocument();
    expect(screen.getByText("6 побед из 28 игр")).toBeInTheDocument();
    const theoryLines = document.querySelectorAll(".theory-line");
    expect(theoryLines).toHaveLength(2);
    expect(theoryLines[0]).toHaveTextContent("Теоретическая вероятность: 66,7%");
    expect(theoryLines[1]).toHaveTextContent("Теоретическая вероятность: 33,3%");
    expect(document.body).not.toHaveTextContent("Теория при большой выборке");
    expect(screen.getByText(/не обязаны складываться в 100%/)).toBeInTheDocument();
    expect(screen.getByRole("progressbar", {
      name: "Доля побед среди игр со сменой выбора: 80,0%",
    })).toHaveAttribute("value", "0.8");
    expect(screen.getByRole("progressbar", {
      name: "Доля побед среди игр без смены выбора: 21,4%",
    })).toHaveAttribute("value", String(6 / 28));
    expect(screen.getByText(/сменили выбор — 8 побед из 10 игр; оставили выбор — 6 побед из 28 игр/))
      .toBeInTheDocument();
  });

  it("shows a small-sample hint when either strategy has fewer than 100 games", () => {
    renderStats(publicStats(strategy(10, 8), strategy(1_200, 400)));

    expect(screen.getByText(/Пока игр немного, поэтому результаты могут заметно отличаться от теории/))
      .toBeInTheDocument();
  });

  it("hides the small-sample hint when both strategies have enough games", () => {
    renderStats(publicStats(strategy(1_000, 667), strategy(1_200, 400)));

    expect(screen.queryByText(/Пока игр немного/)).not.toBeInTheDocument();
  });

  it("shows an honest empty state instead of a zero-percent estimate", () => {
    renderStats(publicStats(strategy(0, 0), strategy(0, 0)));

    expect(screen.getAllByText("Пока нет игр")).toHaveLength(2);
    expect(screen.queryByText("0,0%")).not.toBeInTheDocument();
    expect(screen.queryAllByRole("progressbar")).toHaveLength(0);
    expect(screen.getByText("66,7%")).toBeInTheDocument();
    expect(screen.getByText("33,3%")).toBeInTheDocument();
  });

  it("uses Russian noun forms in the prominent summaries", () => {
    renderStats(publicStats(strategy(1, 1), strategy(2, 2)));

    expect(screen.getByText("1 победа из 1 игры")).toBeInTheDocument();
    expect(screen.getByText("2 победы из 2 игр")).toBeInTheDocument();
  });
});
