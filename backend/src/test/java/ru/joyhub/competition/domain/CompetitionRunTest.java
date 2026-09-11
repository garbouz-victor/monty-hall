package ru.joyhub.competition.domain;

import org.junit.jupiter.api.Test;
import ru.joyhub.competition.persistence.CompetitionRunEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionRunTest {
    private static final Instant START = Instant.parse("2026-09-10T18:00:00Z");

    @Test
    void twoWinsAndLossKeepScoreTwoAndFinishRun() {
        CompetitionRunEntity run = run();
        run.recordWin(START.plusSeconds(1));
        run.recordWin(START.plusSeconds(2));
        run.recordLoss(START.plusSeconds(3));

        assertThat(run.getScore()).isEqualTo(2);
        assertThat(run.getStatus()).isEqualTo(CompetitionRunStatus.LOST);
    }

    @Test
    void firstLossFinishesAtZeroAndNextRunStartsAtZero() {
        CompetitionRunEntity first = run();
        first.recordLoss(START.plusSeconds(1));
        CompetitionRunEntity next = run();

        assertThat(first.getScore()).isZero();
        assertThat(next.getScore()).isZero();
        assertThat(next.getStatus()).isEqualTo(CompetitionRunStatus.ACTIVE);
    }

    @Test
    void exactDeadlineIsExpired() {
        assertThat(run().isExpiredAt(START.plusSeconds(3600))).isTrue();
        assertThat(run().isExpiredAt(START.plusSeconds(3599))).isFalse();
    }

    @Test
    void moscowDayDoesNotDependOnJvmTimezone() {
        var before = CompetitionRules.dayAt(Instant.parse("2026-09-10T20:59:59Z"));
        var boundary = CompetitionRules.dayAt(Instant.parse("2026-09-10T21:00:00Z"));

        assertThat(before.date()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(before.expiresAt()).isEqualTo(Instant.parse("2026-09-10T21:00:00Z"));
        assertThat(boundary.date()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    private static CompetitionRunEntity run() {
        return CompetitionRunEntity.start(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 9, 10),
                1, START, START.plusSeconds(3600), CompetitionRules.RULES_VERSION
        );
    }
}
