import { useState } from "react";
import { BoxCard, type BoxVisualState } from "../monty-hall/BoxCard";
import { FairnessProof } from "../monty-hall/FairnessProof";
import type { BoxNumber, Strategy } from "../monty-hall/types";
import type { CompetitionState } from "./useCompetition";
import { streak } from "./format";

const boxes: BoxNumber[] = [1, 2, 3];

interface Props {
  state: CompetitionState;
  onSaveProfile: (name: string) => void;
  onStart: () => void;
  onSelect: (box: BoxNumber) => void;
  onDecide: (strategy: Strategy) => void;
  onRetry: () => void;
  onNext: () => void;
  onAbandon: () => void;
  onCasual: () => void;
  onShare: () => void;
}

function boxState(box: BoxNumber, state: CompetitionState): BoxVisualState {
  if (state.result) return box === state.result.keyBox ? "open-key" : "open-empty";
  if (state.choice) {
    if (box === state.choice.openedBox) return "open-empty";
    if (box === state.choice.selectedBox) return "selected";
    return "disabled";
  }
  return "closed";
}

export function CompetitionBoard(props: Props) {
  const { state } = props;
  const [name, setName] = useState("");
  const busy = ["loading", "starting", "creating-round", "choosing", "deciding"].includes(state.phase);
  const active = state.run?.status === "ACTIVE";

  if (state.phase === "loading") {
    return <section className="competition-card status-card" aria-busy="true"><span className="loader" /><h1>Восстанавливаем соревнование</h1></section>;
  }

  if (state.phase === "profile" || state.phase === "session-lost") {
    return (
      <section className="competition-card profile-card" aria-labelledby="competition-title">
        <span className="section-kicker">Серия побед</span>
        <h1 id="competition-title">Соревноваться</h1>
        <p>Соберите самую длинную серию побед подряд.</p>
        {state.phase === "session-lost" ? <p className="inline-error" role="alert">{state.error}</p> : null}
        <form onSubmit={(event) => { event.preventDefault(); props.onSaveProfile(name); }}>
          <label htmlFor="competition-name">Публичное имя</label>
          <input id="competition-name" value={name} minLength={2} required autoComplete="nickname"
            onChange={(event) => setName(event.target.value)} placeholder="Например, Виктор" />
          <p className="profile-warning">Имя будет видно в таблице. Не указывайте личные данные.</p>
          <button className="button button--primary" type="submit" disabled={busy}>Сохранить профиль</button>
        </form>
        <ul className="competition-rules">
          <li>{state.me?.dailyAttemptLimit ?? 5} зачётных попыток в день по МСК</li>
          <li>Первое поражение завершает попытку</li>
          <li>Рекорд сохраняется; реальных призов нет</li>
          <li>Результат зависит от случайности</li>
        </ul>
        {state.error && state.phase !== "session-lost" ? <p className="inline-error" role="alert">{state.error}</p> : null}
        <button className="text-button" type="button" onClick={props.onCasual}>В обычную игру</button>
      </section>
    );
  }

  if (state.phase === "lobby" || (!active && state.phase === "run-ended")) {
    const ended = state.phase === "run-ended" && state.run;
    return (
      <section className="competition-card profile-card" aria-labelledby="competition-lobby-title">
        <span className="section-kicker">Серия побед</span>
        <h1 id="competition-lobby-title">{state.me?.player?.displayName} <small>#{state.me?.player?.publicTag}</small></h1>
        {ended ? <div className="run-finish"><strong>{state.run?.status === "LOST" ? "Попытка завершена" : "Попытка закрыта"}</strong><p>Результат: {streak(state.run?.score ?? 0)}. Ваш рекорд сохранён.</p></div> : null}
        {ended && state.result ? state.fairness === "original-missing"
          ? <p className="fairness-status">Исходный commitment не сохранился — независимая проверка недоступна.</p>
          : <FairnessProof status={state.fairness as Parameters<typeof FairnessProof>[0]["status"]} /> : null}
        <div className="record-row"><span>Сегодня: <strong>{state.run?.todayBest ?? state.me?.todayBest ?? 0}</strong></span><span>Личный рекорд: <strong>{state.run?.allTimeBest ?? state.me?.allTimeBest ?? 0}</strong></span></div>
        <p>Осталось новых попыток: <strong>{state.run?.remainingAttempts ?? state.me?.remainingAttempts}</strong> из {state.me?.dailyAttemptLimit}</p>
        <p className="timezone-note">День соревнования — по МСК.</p>
        {(state.run?.remainingAttempts ?? state.me?.remainingAttempts ?? 0) > 0 ? (
          <button className="button button--primary" type="button" onClick={props.onStart} disabled={busy}>{ended ? "Новая попытка" : "Начать попытку"}</button>
        ) : <p className="quota-note">Новые попытки появятся в 00:00 МСК. Обычная игра доступна без лимита.</p>}
        {state.error ? <p className="inline-error" role="alert">{state.error}</p> : null}
        {(state.run?.allTimeBest ?? state.me?.allTimeBest ?? 0) > 0 ? <button className="button button--secondary" type="button" onClick={props.onShare}>Поделиться</button> : null}
        {state.fallbackShare ? <output className="share-output">{state.fallbackShare}</output> : null}
        <button className="text-button" type="button" onClick={props.onCasual}>В обычную игру</button>
      </section>
    );
  }

  return (
    <section className="game-card competition-card" aria-labelledby="competition-game-title">
      <div className="competition-status">
        <span>{state.me?.player?.displayName} · попытка {state.run?.attemptNumber} из {state.me?.dailyAttemptLimit}</span>
        <a href="#competition-leaders">Лидеры</a>
      </div>
      <div className="streak-line" aria-live="polite">🔥 <strong>{state.run?.score ?? 0}</strong> {streak(state.run?.score ?? 0).replace(/^\d+ /, "")}</div>
      <div className="record-row"><span>Сегодня: {state.run?.todayBest ?? state.me?.todayBest}</span><span>Личный рекорд: {state.run?.allTimeBest ?? state.me?.allTimeBest}</span></div>
      <div className="game-heading competition-heading"><div><h1 id="competition-game-title">Где ключи?</h1><p>{state.choice ? "Решающий ход" : "Выберите один из трёх ящиков"}</p></div></div>
      <div className="boxes-grid" aria-label="Игровые ящики">
        {boxes.map((box) => <BoxCard key={box} box={box} state={boxState(box, state)}
          interactive={state.phase === "ready"} busy={busy} finalChoice={state.result?.finalChoice === box} onSelect={props.onSelect} />)}
      </div>
      <div className="game-response" aria-live="polite" aria-busy={busy}>
        {busy ? <p className="game-prompt">Подтверждаем ход на сервере…</p> : null}
        {state.choice && !state.result && state.phase === "choice-made" ? (
          <div className="decision-panel"><p className="decision-copy">В ящике №{state.choice.openedBox} ключей нет. Поменять №{state.choice.selectedBox} на №{state.choice.switchToBox}?</p>
            <div className="decision-actions"><button className="button button--primary" type="button" onClick={() => props.onDecide("SWITCH")}>Поменять на ящик №{state.choice.switchToBox}</button>
              <button className="button button--secondary" type="button" onClick={() => props.onDecide("STAY")}>Оставить ящик №{state.choice.selectedBox}</button></div></div>
        ) : null}
        {state.result ? <div className={`result-panel ${state.result.won ? "result-panel--won" : "result-panel--lost"}`}>
          <p className="result-kicker">{state.result.won ? `🔥 ${streak(state.run?.score ?? 0)}` : "Попытка завершена"}</p>
          <p>{state.result.won ? "Победа подтверждена сервером." : `Результат: ${streak(state.run?.score ?? 0)}. Ваш рекорд сохранён.`}</p>
          {state.newRecord ? <p className="new-record" role="status">Новый личный рекорд!</p> : null}
          {state.result.won && state.run?.status === "ACTIVE" ? <button className="button button--new" type="button" onClick={props.onNext}>Следующий раунд</button> : null}
          {state.fairness === "original-missing" ? <p className="fairness-status">Исходный commitment не сохранился — независимая проверка недоступна.</p>
            : <FairnessProof status={state.fairness as Parameters<typeof FairnessProof>[0]["status"]} />}
        </div> : null}
        {state.phase === "blocked" ? <div className="inline-error" role="alert"><p>{state.error}</p><button className="text-button" type="button" onClick={props.onRetry}>Повторить прежнее действие</button></div> : null}
      </div>
      {active ? <div className="competition-actions"><button className="text-button" type="button" onClick={props.onCasual}>В обычную игру</button><button className="text-button text-button--danger" type="button" onClick={props.onAbandon}>Завершить попытку</button></div> : null}
    </section>
  );
}
