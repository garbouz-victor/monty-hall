package ru.joyhub.competition.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompetitionNameValidatorTest {
    private final CompetitionNameValidator validator = new CompetitionNameValidator();

    @Test
    void normalizesNfcWhitespaceAndCountsUnicodeCodePoints() {
        assertThat(validator.normalize("  Виктор   7  ")).isEqualTo("Виктор 7");
        assertThat(validator.normalize("И\u0306ога")).isEqualTo("Йога");
        assertThat(validator.normalize("𐐀".repeat(20))).isEqualTo("𐐀".repeat(20));
    }

    @Test
    void rejectsControlsMarkupUrlsBidiAndInvalidLengths() {
        for (String value : new String[]{
                "A", "https://joy-hub.ru", "<b>имя</b>", "Игрок\n1", "Игрок\u202E1", "a".repeat(21)
        }) {
            assertThatThrownBy(() -> validator.normalize(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
