package ru.joyhub.competition.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import ru.joyhub.competition.application.CompetitionResults;
import ru.joyhub.competition.domain.CompetitionRunStatus;
import ru.joyhub.montyhall.api.GameApiModels;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CompetitionApiModels {
    private CompetitionApiModels() {}

    public record ProfileRequest(@NotBlank String displayName) {}
    public record RoundRequest(@Min(1) int expectedRoundNumber) {}

    public record PlayerResponse(UUID publicPlayerId, String publicTag, String displayName) {
        static PlayerResponse from(CompetitionResults.Player player) {
            return new PlayerResponse(player.publicPlayerId(), player.publicTag(), player.displayName());
        }
    }

    public record RunResponse(
            UUID runId, CompetitionRunStatus status, int score, int attemptNumber,
            LocalDate competitionDate, Instant startedAt, Instant expiresAt, Instant endedAt,
            int rulesVersion, int remainingAttempts, int todayBest, int allTimeBest,
            Long todayRank, Long allTimeRank
    ) {
        public static RunResponse from(CompetitionResults.Run run) {
            return new RunResponse(
                    run.runId(), run.status(), run.score(), run.attemptNumber(), run.competitionDate(),
                    run.startedAt(), run.expiresAt(), run.endedAt(), run.rulesVersion(),
                    run.remainingAttempts(), run.todayBest(), run.allTimeBest(), run.todayRank(), run.allTimeRank()
            );
        }
    }

    public record RoundResponse(int roundNumber, GameApiModels.GameStateResponse game) {
        static RoundResponse from(CompetitionResults.Round round) {
            return new RoundResponse(round.roundNumber(), GameApiModels.GameStateResponse.from(round.game()));
        }
    }

    public record MeResponse(
            boolean authenticated, Instant serverTime, String timezone, LocalDate competitionDate,
            int dailyAttemptLimit, int remainingAttempts, PlayerResponse player,
            int todayBest, int allTimeBest, Long todayRank, Long allTimeRank,
            RunResponse run, RoundResponse round
    ) {
        static MeResponse from(CompetitionResults.Me me) {
            return new MeResponse(
                    me.authenticated(), me.serverTime(), me.timezone(), me.competitionDate(),
                    me.dailyAttemptLimit(), me.remainingAttempts(),
                    me.player() == null ? null : PlayerResponse.from(me.player()),
                    me.todayBest(), me.allTimeBest(), me.todayRank(), me.allTimeRank(),
                    me.run() == null ? null : RunResponse.from(me.run()),
                    me.round() == null ? null : RoundResponse.from(me.round())
            );
        }
    }

    public record ProfileResponse(PlayerResponse player) {}
    public record StartRunResponse(RunResponse run, boolean replayed) {}
    public record CreateRoundResponse(UUID gameId, String commitment, int roundNumber, RunResponse run, boolean replayed) {}

    public record LeaderboardEntryResponse(
            UUID publicPlayerId, String publicTag, String displayName,
            long rank, int bestStreak, Instant achievedAt
    ) {
        static LeaderboardEntryResponse from(CompetitionResults.LeaderboardEntry value) {
            return new LeaderboardEntryResponse(value.publicPlayerId(), value.publicTag(), value.displayName(),
                    value.rank(), value.bestStreak(), value.achievedAt());
        }
    }

    public record LeaderboardResponse(
            String period, LocalDate competitionDate, Instant serverTime, String timezone,
            int registeredProfiles, int participantsWithResult,
            List<LeaderboardEntryResponse> entries, LeaderboardEntryResponse me
    ) {
        static LeaderboardResponse from(CompetitionResults.Leaderboard board) {
            return new LeaderboardResponse(
                    board.period(), board.competitionDate(), board.serverTime(), board.timezone(),
                    board.registeredProfiles(), board.participantsWithResult(),
                    board.entries().stream().map(LeaderboardEntryResponse::from).toList(),
                    board.me() == null ? null : LeaderboardEntryResponse.from(board.me())
            );
        }
    }
}
