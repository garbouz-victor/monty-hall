package ru.joyhub.competition.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class CompetitionRules {

    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    public static final int RULES_VERSION = 1;

    private CompetitionRules() {
    }

    public static DayWindow dayAt(Instant instant) {
        ZonedDateTime inMoscow = instant.atZone(ZONE);
        LocalDate date = inMoscow.toLocalDate();
        return new DayWindow(date, date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    public record DayWindow(LocalDate date, Instant expiresAt) {
    }
}
