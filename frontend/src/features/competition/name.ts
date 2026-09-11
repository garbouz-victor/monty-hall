export function normalizeCompetitionName(raw: string): string {
  if (/[\p{Cc}\p{Cf}]/u.test(raw)) throw new Error("invalid competition name");
  const name = raw.normalize("NFC").trim().replace(/\s+/gu, " ");
  const length = [...name].length;
  if (length < 2 || length > 20 || !/^[\p{L}\p{N} _-]+$/u.test(name)) {
    throw new Error("invalid competition name");
  }
  return name;
}
