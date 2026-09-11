export function victoryNoun(value: number): string {
  const mod100 = value % 100;
  const mod10 = value % 10;
  if (mod100 >= 11 && mod100 <= 14) return "побед";
  if (mod10 === 1) return "победа";
  if (mod10 >= 2 && mod10 <= 4) return "победы";
  return "побед";
}

export function streak(value: number): string {
  return `${value} ${victoryNoun(value)} подряд`;
}
