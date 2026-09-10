import type { BoxNumber } from "./types";

export type BoxVisualState = "closed" | "selected" | "open-empty" | "open-key" | "disabled";

interface BoxCardProps {
  box: BoxNumber;
  state: BoxVisualState;
  interactive: boolean;
  busy: boolean;
  finalChoice: boolean;
  onSelect: (box: BoxNumber) => void;
}

function ariaLabel(box: BoxNumber, state: BoxVisualState, finalChoice: boolean): string {
  if (state === "open-key") return `Ящик ${box}: здесь ключи${finalChoice ? ", ваш финальный выбор" : ""}`;
  if (state === "open-empty") return `Ящик ${box}: пусто${finalChoice ? ", ваш финальный выбор" : ""}`;
  if (state === "selected") return `Ящик ${box}: ваш выбор`;
  return `Выбрать ящик ${box}`;
}

export function BoxCard({ box, state, interactive, busy, finalChoice, onSelect }: BoxCardProps) {
  const isOpen = state === "open-empty" || state === "open-key";
  return (
    <div className={`box-slot box-slot--${state} ${finalChoice ? "box-slot--final" : ""}`}>
      <button
        className="box-card"
        type="button"
        aria-label={ariaLabel(box, state, finalChoice)}
        aria-pressed={state === "selected" || finalChoice}
        disabled={!interactive || busy}
        onClick={() => onSelect(box)}
      >
        <span className="box-lid" aria-hidden="true" />
        <span className="box-body" aria-hidden="true">
          <span className="box-content">
            {state === "open-key" ? <span className="key-symbol">🔑</span> : null}
            {state === "open-empty" ? <span className="empty-label">Пусто</span> : null}
            {!isOpen ? <span className="question-mark">?</span> : null}
          </span>
        </span>
      </button>
      <span className="box-number">Ящик {box}</span>
      {state === "selected" ? <span className="box-badge">Ваш выбор</span> : null}
      {finalChoice ? <span className="box-badge box-badge--final">Финал</span> : null}
    </div>
  );
}

