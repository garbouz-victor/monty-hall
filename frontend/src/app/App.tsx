import { useEffect, useState } from "react";
import { GameBoard } from "../features/monty-hall/GameBoard";
import { useMontyHallGame } from "../features/monty-hall/useMontyHallGame";
import { StatsSection } from "../features/stats/StatsSection";
import { CompetitionBoard } from "../features/competition/CompetitionBoard";
import { Leaderboard } from "../features/competition/Leaderboard";
import { useCompetition } from "../features/competition/useCompetition";

export function App() {
  const [mode, setMode] = useState<"casual" | "competition">("casual");
  const { state, selectBox, decide, retry, startNewGame, refreshStats } = useMontyHallGame();
  const competition = useCompetition(mode === "competition");

  useEffect(() => {
    if (competition.state.result) void refreshStats();
  }, [competition.state.result?.gameId, refreshStats]);

  return (
    <div className="site-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="JOY HUB — главная">
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          <span>JOY HUB</span>
        </a>
        <span className="header-note">Игра без регистрации</span>
      </header>

      <main className={mode === "competition" ? "main--competition" : undefined}>
        {mode === "casual" ? (
          <>
            <GameBoard state={state} onSelectBox={selectBox} onDecide={decide} onRetry={retry} onNewGame={startNewGame} />
            <section className="competition-invite" aria-labelledby="competition-invite-title">
              <div><span className="section-kicker">Новый режим</span><h2 id="competition-invite-title">Серия побед</h2><p>Соберите серию побед подряд и попадите в таблицу лидеров.</p></div>
              <button className="button button--primary" type="button" onClick={() => setMode("competition")}>
                {competition.state.run?.status === "ACTIVE" ? "Продолжить попытку" : "Соревноваться"}
              </button>
            </section>
          </>
        ) : (
          <div className="competition-layout">
            <CompetitionBoard state={competition.state} onSaveProfile={competition.saveProfile}
              onStart={competition.startRun} onSelect={competition.selectBox} onDecide={competition.decide}
              onRetry={competition.retry} onNext={competition.nextRound} onAbandon={competition.abandon}
              onShare={competition.share} onCasual={() => setMode("casual")} />
            <Leaderboard refreshToken={competition.state.run?.score ?? 0} personal={competition.state.me ? {
              todayBest: competition.state.run?.todayBest ?? competition.state.me.todayBest,
              allTimeBest: competition.state.run?.allTimeBest ?? competition.state.me.allTimeBest,
              todayRank: competition.state.run?.todayRank ?? competition.state.me.todayRank,
              allTimeRank: competition.state.run?.allTimeRank ?? competition.state.me.allTimeRank,
            } : null} />
          </div>
        )}

        <StatsSection
          stats={state.stats}
          loading={state.statsLoading}
          reveal={state.phase === "completed"}
          onRetry={refreshStats}
        />
      </main>

      <footer>
        <p>JOY HUB · задача Монти Холла</p>
      </footer>
    </div>
  );
}
