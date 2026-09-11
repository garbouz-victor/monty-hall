import type { MontyHallGameState } from "./useMontyHallGame";
import type { BoxNumber, Strategy } from "./types";
import { BoxCard, type BoxVisualState } from "./BoxCard";
import { FairnessProof } from "./FairnessProof";

interface GameBoardProps {
  state: MontyHallGameState;
  onSelectBox: (box: BoxNumber) => void;
  onDecide: (strategy: Strategy) => void;
  onRetry: () => void;
  onNewGame: () => void;
}

const boxes: BoxNumber[] = [1, 2, 3];

function visualState(box: BoxNumber, state: MontyHallGameState): BoxVisualState {
  if (state.result) {
    return box === state.result.keyBox ? "open-key" : "open-empty";
  }
  if (state.choice) {
    if (box === state.choice.openedBox) return "open-empty";
    if (box === state.choice.selectedBox) return "selected";
    return "disabled";
  }
  return "closed";
}

function stepLabel(state: MontyHallGameState): string {
  if (state.result) return "Результат";
  if (state.choice) return "Решающий ход";
  return "Первый ход";
}

export function GameBoard({ state, onSelectBox, onDecide, onRetry, onNewGame }: GameBoardProps) {
  const isBusy = [
    "starting",
    "choosing",
    "deciding",
    "recovering-choice",
    "recovering-decision",
  ].includes(state.phase);

  return (
    <section className="game-card" aria-labelledby="game-title">
      <div className="game-card__topline">
        <span className="step-label">{stepLabel(state)}</span>
        <span className="box-count" aria-label="Три ящика">● ● ●</span>
      </div>

      <div className="game-heading">
        <span className="title-key" aria-hidden="true">🔑</span>
        <div>
          <h1 id="game-title">Где ключи?</h1>
          <p>
            {state.result
              ? "Все ящики открыты"
              : state.choice
                ? "Один пустой ящик уже открыт"
                : "Выберите один из трёх ящиков"}
          </p>
        </div>
      </div>

      <div className={`boxes-grid ${state.result ? "boxes-grid--revealed" : ""}`} aria-label="Игровые ящики">
        {boxes.map((box) => (
          <BoxCard
            key={box}
            box={box}
            state={visualState(box, state)}
            interactive={state.phase === "ready"}
            busy={isBusy}
            finalChoice={state.result?.finalChoice === box}
            onSelect={onSelectBox}
          />
        ))}
      </div>

      <div className="game-response" aria-live="polite" aria-busy={isBusy}>
        {["ready", "starting", "choosing", "recovering-choice", "recovering-decision"].includes(state.phase) ? (
          <p className="game-prompt">
            {state.phase === "ready"
              ? "Нажмите на ящик — выбор сразу сохранится."
              : state.phase === "starting"
                ? "Начинаем игру и фиксируем честный результат…"
                : state.phase === "choosing"
                  ? "Ведущий открывает пустой ящик…"
                  : "Проверяем сохранённый ход…"}
          </p>
        ) : null}

        {state.choice && !state.result ? (
          <div className="decision-panel">
            <p className="decision-copy">
              В ящике №{state.choice.openedBox} ключей нет. Вы выбрали №{state.choice.selectedBox}.
              <strong> Поменять его на №{state.choice.switchToBox}?</strong>
            </p>
            {state.phase === "choice-made" ? (
              <div className="decision-actions">
                <button
                  className="button button--primary"
                  type="button"
                  onClick={() => onDecide("SWITCH")}
                >
                  <span aria-hidden="true">↻</span>
                  Поменять на ящик №{state.choice.switchToBox}
                </button>
                <button
                  className="button button--secondary"
                  type="button"
                  onClick={() => onDecide("STAY")}
                >
                  Оставить ящик №{state.choice.selectedBox}
                </button>
              </div>
            ) : null}
          </div>
        ) : null}

        {state.result ? (
          <div className={`result-panel ${state.result.won ? "result-panel--won" : "result-panel--lost"}`}>
            <p className="result-kicker">{state.result.won ? "🎉 Вы выиграли!" : "Не повезло 🙂"}</p>
            <p>
              {state.result.strategy === "SWITCH"
                ? `Вы поменяли выбор: №${state.result.initialChoice} → №${state.result.finalChoice}.`
                : `Вы оставили ящик №${state.result.initialChoice}.`}
            </p>
            <p>Ключи были в ящике №{state.result.keyBox}.</p>
            <button className="button button--new" type="button" disabled={isBusy} onClick={onNewGame}>
              Сыграть ещё раз
            </button>
            <FairnessProof status={state.fairness} />
          </div>
        ) : null}

        {state.error ? (
          <div className="inline-error" role="alert">
            <p>{state.error}</p>
            {state.retryAction ? (
              <button className="text-button" type="button" onClick={onRetry}>
                {state.retryLabel ?? "Повторить запрос"}
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}
