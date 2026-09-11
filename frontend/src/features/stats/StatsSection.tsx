import type { PublicStats, StrategyStats } from "../monty-hall/types";

interface StatsSectionProps {
  stats: PublicStats | null;
  loading: boolean;
  reveal: boolean;
  onRetry: () => void;
}

const numberFormat = new Intl.NumberFormat("ru-RU");

function percent(value: number): string {
  return new Intl.NumberFormat("ru-RU", {
    style: "percent",
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(value).replace(/\s%$/, "%");
}

function StrategyCard({
  title,
  stats,
  theory,
  accent,
}: {
  title: string;
  stats: StrategyStats;
  theory: number;
  accent: "switch" | "stay";
}) {
  const winRate = Math.max(0, Math.min(stats.winRate, 1));
  return (
    <article className={`stats-card stats-card--${accent}`}>
      <p className="stats-card__label">{title}</p>
      <p className="stats-card__rate">{percent(stats.winRate)}</p>
      <progress
        className="rate-bar"
        aria-label={`${title}: ${percent(stats.winRate)} побед`}
        max={1}
        value={winRate}
      />
      <dl className="stats-list">
        <div><dt>Игры</dt><dd>{numberFormat.format(stats.games)}</dd></div>
        <div><dt>Победы</dt><dd>{numberFormat.format(stats.wins)}</dd></div>
        <div><dt>Поражения</dt><dd>{numberFormat.format(stats.losses)}</dd></div>
      </dl>
      <p className="theory-line">Теория: <strong>{percent(theory)}</strong></p>
    </article>
  );
}

export function StatsSection({ stats, loading, reveal, onRetry }: StatsSectionProps) {
  return (
    <section className="stats-section" aria-labelledby="stats-title">
      <details open={reveal || undefined}>
        <summary>
          <span>
            <span className="section-kicker">Живой эксперимент</span>
            <strong id="stats-title">Общая статистика</strong>
          </span>
          <span className="summary-arrow" aria-hidden="true">↓</span>
        </summary>

        <div className="stats-content">
          {loading && !stats ? <p className="stats-state" role="status">Загружаем статистику…</p> : null}
          {!loading && !stats ? (
            <div className="stats-state">
              <p>Статистика сейчас недоступна.</p>
              <button className="text-button" type="button" onClick={onRetry}>Обновить</button>
            </div>
          ) : null}
          {stats ? (
            <>
              <p className="total-games">Всего завершено: <strong>{numberFormat.format(stats.totalCompletedGames)}</strong></p>
              <div className="stats-grid">
                <StrategyCard
                  title="Поменяли выбор"
                  stats={stats.switch}
                  theory={stats.theoretical.switchWinRate}
                  accent="switch"
                />
                <StrategyCard
                  title="Оставили выбор"
                  stats={stats.stay}
                  theory={stats.theoretical.stayWinRate}
                  accent="stay"
                />
              </div>
              <p className="stats-note">Статистика по завершённым играм. Считаются партии, а не уникальные игроки.</p>
              <details className="explanation">
                <summary>Почему смена выбора выгоднее?</summary>
                <div>
                  <p>С первого раза ключи выбирают с вероятностью 1/3, а пустой ящик — с вероятностью 2/3.</p>
                  <p>
                    Ведущий знает ответ и убирает один заведомо пустой вариант. Поэтому смена выигрывает во всех тех
                    случаях, когда первый выбор был пустым: примерно в двух играх из трёх.
                  </p>
                </div>
              </details>
            </>
          ) : null}
        </div>
      </details>
    </section>
  );
}
