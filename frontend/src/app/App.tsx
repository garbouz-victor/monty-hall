import { GameBoard } from "../features/monty-hall/GameBoard";
import { useMontyHallGame } from "../features/monty-hall/useMontyHallGame";
import { StatsSection } from "../features/stats/StatsSection";

export function App() {
  const { state, selectBox, decide, retry, startNewGame, refreshStats } = useMontyHallGame();
  const loading = state.phase === "booting";

  return (
    <div className="site-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="JOY HUB — главная">
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          <span>JOY HUB</span>
        </a>
        <span className="header-note">Игра без регистрации</span>
      </header>

      <main>
        {loading ? (
          <section className="status-card" aria-live="polite" aria-busy="true">
            <span className="loader" aria-hidden="true" />
            <h1>Проверяем игровой сервер</h1>
            <p>Загружаем состояние сервиса и общую статистику.</p>
          </section>
        ) : null}

        {state.phase === "unavailable" ? (
          <section className="status-card status-card--offline" role="alert">
            <span className="offline-symbol" aria-hidden="true">⌁</span>
            <h1>Игровой сервер временно недоступен</h1>
            <p>Сам сайт работает, но начать новую игру пока нельзя.</p>
            <button className="button button--primary" type="button" onClick={retry}>Попробовать снова</button>
          </section>
        ) : null}

        {!loading && state.phase !== "unavailable" ? (
          <GameBoard
            state={state}
            onSelectBox={selectBox}
            onDecide={decide}
            onRetry={retry}
            onNewGame={startNewGame}
          />
        ) : null}

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
