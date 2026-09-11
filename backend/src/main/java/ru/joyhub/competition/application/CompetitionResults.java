package ru.joyhub.competition.application;

import ru.joyhub.competition.domain.CompetitionRunStatus;
import ru.joyhub.montyhall.application.GameResults;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CompetitionResults {
    private CompetitionResults() {}

    public record Player(UUID publicPlayerId, String publicTag, String displayName) {}

    public record Run(
            UUID runId, CompetitionRunStatus status, int score, int attemptNumber,
            LocalDate competitionDate, Instant startedAt, Instant expiresAt, Instant endedAt,
            int rulesVersion, int remainingAttempts, int todayBest, int allTimeBest,
            Long todayRank, Long allTimeRank
    ) {}

    public record Round(int roundNumber, GameResults.GameSnapshot game) {}

    public record Me(
            boolean authenticated, Instant serverTime, String timezone, LocalDate competitionDate,
            int dailyAttemptLimit, int remainingAttempts, Player player, int todayBest,
            int allTimeBest, Long todayRank, Long allTimeRank, Run run, Round round,
            UUID visitorId
    ) {}

    public record ProfileResult(Player player, String newCredential) {}
    public record RunResult(Run run, boolean replayed) {}
    public record RoundResult(GameResults.Created game, Run run, int roundNumber, boolean replayed) {}

    public record LeaderboardEntry(
            UUID publicPlayerId, String publicTag, String displayName,
            long rank, int bestStreak, Instant achievedAt
    ) {}

    public record Leaderboard(
            String period, LocalDate competitionDate, Instant serverTime, String timezone,
            int registeredProfiles, int participantsWithResult, List<LeaderboardEntry> entries,
            LeaderboardEntry me
    ) {}
}
