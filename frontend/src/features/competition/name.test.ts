import { describe, expect, it } from "vitest";
import { normalizeCompetitionName } from "./name";

describe("competition public name", () => {
  it("normalizes NFC, trim and repeated spaces", () => {
    expect(normalizeCompetitionName("  Виктор   7  ")).toBe("Виктор 7");
    expect(normalizeCompetitionName("И\u0306ога")).toBe("Йога");
  });

  it("rejects URLs, markup, controls, bidi controls and invalid lengths", () => {
    for (const value of ["A", "https://joy-hub.ru", "<b>имя</b>", "Игрок\n1", "Игрок\u202E1", "a".repeat(21)]) {
      expect(() => normalizeCompetitionName(value)).toThrow();
    }
  });

  it("counts Unicode code points rather than UTF-16 units", () => {
    expect(normalizeCompetitionName("𐐀".repeat(20))).toBe("𐐀".repeat(20));
  });
});
