import type { FairnessStatus } from "./fairness";

interface FairnessProofProps {
  status: FairnessStatus | null;
}

export function FairnessProof({ status }: FairnessProofProps) {
  if (!status) return null;

  const labels: Record<FairnessStatus, string> = {
    checking: "Проверяем честность партии…",
    verified: "Честность игры проверена",
    mismatch: "Проверка не пройдена: данные партии не совпали",
    unsupported: "Браузер не поддерживает автоматическую проверку",
  };

  return (
    <div className={`fairness fairness--${status}`} data-testid="fairness-status">
      <p className="fairness-status" role={status === "mismatch" ? "alert" : "status"}>
        <span aria-hidden="true">{status === "verified" ? "✓" : status === "mismatch" ? "!" : "◌"}</span>
        {labels[status]}
      </p>
      <details>
        <summary>Как это проверяется?</summary>
        <p>
          До вашего выбора сервер прислал SHA-256 commitment. После игры браузер получил номер ящика и
          случайный nonce, собрал строку <code>v1:gameId:keyBox:nonce</code> и сам пересчитал хеш. Совпадение
          подтверждает, что ключи не перекладывали после первого хода.
        </p>
      </details>
    </div>
  );
}

