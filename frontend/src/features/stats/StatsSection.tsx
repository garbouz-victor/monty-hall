import type { PublicStats, StrategyStats } from "../monty-hall/types";

interface StatsSectionProps {
  stats: PublicStats | null;
  loading: boolean;
  reveal: boolean;
  onRetry: () => void;
}

const numberFormat = new Intl.NumberFormat("ru-RU");
export const SMALL_SAMPLE_THRESHOLD = 100;

function percent(value: number): string {
  return new Intl.NumberFormat("ru-RU", {
    style: "percent",
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(value).replace(/\s%$/, "%");
}

function pluralize(value: number, one: string, few: string, many: string): string {
  const absolute = Math.abs(value);
  const lastTwoDigits = absolute % 100;
  if (lastTwoDigits >= 11 && lastTwoDigits <= 14) return many;

  const lastDigit = absolute % 10;
  if (lastDigit === 1) return one;
  if (lastDigit >= 2 && lastDigit <= 4) return few;
  return many;
}

function countWithNoun(value: number, one: string, few: string, many: string): string {
  return `${numberFormat.format(value)} ${pluralize(value, one, few, many)}`;
}

function strategySummary(stats: StrategyStats): string {
  return `${countWithNoun(stats.wins, "победа", "победы", "побед")} из ${countWithNoun(stats.games, "игры", "игр", "игр")}`;
}

function currentStrategyResult(stats: StrategyStats): string {
  return stats.games === 0 ? "пока нет завершённых игр" : strategySummary(stats);
}

function StrategyCard({
  title,
  stats,
  theory,
  accent,
  ariaGroup,
}: {
  title: string;
  stats: StrategyStats;
  theory: number;
  accent: "switch" | "stay";
  ariaGroup: string;
}) {
  const winRate = Math.max(0, Math.min(stats.winRate, 1));
  const hasGames = stats.games > 0;
  return (
    <article className={`stats-card stats-card--${accent}`}>
      <p className="stats-card__label">{title}</p>
      {hasGames ? (
        <>
          <p className="stats-card__rate"><strong>{percent(stats.winRate)}</strong><span>побед</span></p>
          <p className="stats-card__summary">{strategySummary(stats)}</p>
          <div className="rate-visual">
            <progress
              className="rate-bar"
              aria-label={`Доля побед среди игр ${ariaGroup}: ${percent(stats.winRate)}`}
              max={1}
              value={winRate}
            />
            <div className="rate-scale" aria-hidden="true"><span>0%</span><span>100%</span></div>
          </div>
        </>
      ) : (
        <p className="stats-card__empty">Пока нет игр</p>
      )}
      <p className="theory-line">Теоретическая вероятность: <strong>{percent(theory)}</strong></p>
      {hasGames ? (
        <dl className="stats-list">
          <div><dt>Игры</dt><dd>{numberFormat.format(stats.games)}</dd></div>
          <div><dt>Победы</dt><dd>{numberFormat.format(stats.wins)}</dd></div>
          <div><dt>Поражения</dt><dd>{numberFormat.format(stats.losses)}</dd></div>
        </dl>
      ) : null}
    </article>
  );
}

export function StatsSection({ stats, loading, reveal, onRetry }: StatsSectionProps) {
  const hasSmallSample = stats
    ? stats.switch.games < SMALL_SAMPLE_THRESHOLD || stats.stay.games < SMALL_SAMPLE_THRESHOLD
    : false;

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
              <p className="stats-method">Процент побед считается отдельно для каждой стратегии.</p>
              <p className="total-games">Всего завершено: <strong>{numberFormat.format(stats.totalCompletedGames)}</strong></p>
              <div className="stats-grid">
                <StrategyCard
                  title="Поменяли выбор"
                  stats={stats.switch}
                  theory={stats.theoretical.switchWinRate}
                  accent="switch"
                  ariaGroup="со сменой выбора"
                />
                <StrategyCard
                  title="Оставили выбор"
                  stats={stats.stay}
                  theory={stats.theoretical.stayWinRate}
                  accent="stay"
                  ariaGroup="без смены выбора"
                />
              </div>
              <p className="stats-insight">
                <span aria-hidden="true">ⓘ</span>
                <span>Проценты считаются отдельно для каждой стратегии и не обязаны складываться в 100%.</span>
              </p>
              {hasSmallSample ? (
                <p className="stats-insight stats-insight--sample">
                  <span aria-hidden="true">ⓘ</span>
                  <span>Пока игр немного, поэтому результаты могут заметно отличаться от теории.</span>
                </p>
              ) : null}
              <p className="stats-note">Статистика по завершённым играм обычного и соревновательного режимов. Считаются партии, а не уникальные игроки.</p>
              <details className="explanation">
                <summary>Почему смена выбора выгоднее?</summary>
                <div>
                  <p>Если первоначально выбран ящик с ключами, вероятность равна 1/3. Вероятность выбрать пустой ящик — 2/3.</p>
                  <p>
                    Ведущий знает ответ и убирает один заведомо пустой вариант. Поэтому смена выигрывает во всех тех
                    случаях, когда первый выбор был пустым: остаться — 1/3, поменять — 2/3.
                  </p>
                  <p>Реальные проценты сайта считаются отдельно среди игр со сменой выбора и среди игр без смены.</p>
                  <p className="explanation__current">
                    <strong>Сейчас:</strong> сменили выбор — {currentStrategyResult(stats.switch)}; оставили выбор — {currentStrategyResult(stats.stay)}.
                  </p>
                  <p>Это две разные выборки, поэтому их проценты не должны складываться в 100%.</p>
                </div>
              </details>
            </>
          ) : null}
        </div>
      </details>
    </section>
  );
}
