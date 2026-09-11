import { useCallback, useEffect, useState } from "react";
import { getLeaderboard } from "./api";
import type { LeaderboardPeriod, LeaderboardResponse } from "./types";

interface Props {
  refreshToken: number;
  personal: { todayBest: number; allTimeBest: number; todayRank?: number; allTimeRank?: number } | null;
}

export function Leaderboard({ refreshToken, personal }: Props) {
  const [period, setPeriod] = useState<LeaderboardPeriod>("TODAY");
  const [board, setBoard] = useState<LeaderboardResponse | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    try {
      setBoard(await getLeaderboard(period));
      setError(false);
    } catch { setError(true); }
  }, [period]);

  useEffect(() => { void load(); }, [load, refreshToken]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void load();
    }, 30_000);
    const onVisibility = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onVisibility);
    return () => { window.clearInterval(timer); document.removeEventListener("visibilitychange", onVisibility); };
  }, [load]);

  const entries = board?.entries.slice(0, expanded ? 10 : 5) ?? [];
  return (
    <section className="leaderboard" id="competition-leaders" aria-labelledby="leaders-title">
      <div className="leaderboard__heading">
        <div><span className="section-kicker">Соревнование</span><h2 id="leaders-title">Лидеры</h2></div>
        <span className="leaderboard__updated">МСК · {board ? new Date(board.serverTime).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) : "—"}</span>
      </div>
      <div className="period-tabs" role="tablist" aria-label="Период рейтинга">
        {(["TODAY", "ALL_TIME"] as const).map((value) => (
          <button key={value} type="button" role="tab" aria-selected={period === value} data-period={value}
            onClick={() => { setPeriod(value); setExpanded(false); }}
            onKeyDown={(event) => {
              let next: LeaderboardPeriod | null = null;
              if (event.key === "ArrowLeft" || event.key === "ArrowRight") next = value === "TODAY" ? "ALL_TIME" : "TODAY";
              if (event.key === "Home") next = "TODAY";
              if (event.key === "End") next = "ALL_TIME";
              if (!next) return;
              event.preventDefault();
              setPeriod(next);
              setExpanded(false);
              const button = event.currentTarget.parentElement?.querySelector<HTMLButtonElement>(`[data-period="${next}"]`);
              button?.focus();
            }}>
            {value === "TODAY" ? "Сегодня" : "За всё время"}
          </button>
        ))}
      </div>
      {error ? (
        <div className="leaderboard__state" role="status">Рейтинг временно недоступен. <button type="button" className="text-button" onClick={load}>Обновить</button></div>
      ) : null}
      {!error && board && entries.length === 0 ? (
        <p className="leaderboard__state">{period === "TODAY" ? "Сегодня ещё нет результатов" : "Результатов пока нет"}. Первая победа откроет таблицу.</p>
      ) : null}
      {entries.length > 0 ? (
        <ol className="leaderboard__list" aria-label="Рейтинг серий">
          {entries.map((entry) => (
            <li key={entry.publicPlayerId}>
              <span className="leaderboard__rank">{entry.rank}</span>
              <span className="leaderboard__person"><strong>{entry.displayName}</strong><small>#{entry.publicTag}</small></span>
              <strong className="leaderboard__score">🔥 {entry.bestStreak}</strong>
            </li>
          ))}
        </ol>
      ) : null}
      {board?.entries && board.entries.length > 5 && !expanded ? (
        <button type="button" className="button button--secondary leaderboard__more" onClick={() => setExpanded(true)}>Показать 10</button>
      ) : null}
      {expanded && board?.entries.length === 10 ? (
        <p className="leaderboard__tie-note">Показаны первые 10 участников. Равные серии занимают одинаковое место.</p>
      ) : null}
      {board && personal ? (
        <div className="leaderboard__me"><span>Ваш результат</span><strong>{(period === "TODAY" ? personal.todayRank : personal.allTimeRank)
          ? `место ${period === "TODAY" ? personal.todayRank : personal.allTimeRank} · 🔥 ${period === "TODAY" ? personal.todayBest : personal.allTimeBest}`
          : "Пока без места"}</strong></div>
      ) : null}
      {board ? <p className="leaderboard__meta">Профилей: {board.registeredProfiles} · с результатом: {board.participantsWithResult}</p> : null}
      {period === "ALL_TIME" ? <p className="leaderboard__tie-note">Доска рекордов: более долгая история участия даёт больше шансов установить серию.</p> : null}
    </section>
  );
}
