package ru.joyhub.competition.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.joyhub.competition.application.CompetitionResults;
import ru.joyhub.competition.domain.LeaderboardPeriod;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CompetitionLeaderboardRepository {
    private static final String BEST_CTE = """
            WITH best AS (
              SELECT DISTINCT ON (r.player_id)
                r.player_id, p.public_id, p.public_tag, p.display_name,
                r.score AS best_streak, r.score_reached_at AS achieved_at
              FROM competition_run r
              JOIN competition_player p ON p.id = r.player_id
              WHERE p.excluded_from_leaderboard = FALSE
                AND r.score >= 1
                AND %s
              ORDER BY r.player_id, r.score DESC, r.score_reached_at ASC, p.public_id
            ), ranked AS (
              SELECT best.*,
                1 + (SELECT COUNT(*) FROM best higher WHERE higher.best_streak > best.best_streak) AS rank
              FROM best
            )
            """;
    private final JdbcTemplate jdbc;

    public CompetitionLeaderboardRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CompetitionResults.LeaderboardEntry> top(
            LeaderboardPeriod period, LocalDate date, int rulesVersion, int limit
    ) {
        String predicate = period == LeaderboardPeriod.TODAY ? "r.competition_date = ?" : "r.rules_version = ?";
        String sql = BEST_CTE.formatted(predicate) + """
                SELECT public_id, public_tag, display_name, rank, best_streak, achieved_at
                FROM ranked
                ORDER BY best_streak DESC, achieved_at ASC, public_id
                LIMIT ?
                """;
        Object periodValue = period == LeaderboardPeriod.TODAY ? Date.valueOf(date) : rulesVersion;
        return jdbc.query(sql, this::map, periodValue, limit);
    }

    public Optional<CompetitionResults.LeaderboardEntry> player(
            LeaderboardPeriod period, LocalDate date, int rulesVersion, UUID playerId
    ) {
        String predicate = period == LeaderboardPeriod.TODAY ? "r.competition_date = ?" : "r.rules_version = ?";
        String sql = BEST_CTE.formatted(predicate) + """
                SELECT public_id, public_tag, display_name, rank, best_streak, achieved_at
                FROM ranked WHERE player_id = ?
                """;
        Object periodValue = period == LeaderboardPeriod.TODAY ? Date.valueOf(date) : rulesVersion;
        return jdbc.query(sql, this::map, periodValue, playerId).stream().findFirst();
    }

    public int best(UUID playerId, LeaderboardPeriod period, LocalDate date, int rulesVersion) {
        String predicate = period == LeaderboardPeriod.TODAY ? "competition_date = ?" : "rules_version = ?";
        Object value = period == LeaderboardPeriod.TODAY ? Date.valueOf(date) : rulesVersion;
        Integer result = jdbc.queryForObject(
                "SELECT COALESCE(MAX(score), 0) FROM competition_run WHERE player_id = ? AND " + predicate,
                Integer.class, playerId, value
        );
        return result == null ? 0 : result;
    }

    public int registeredProfiles() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM competition_player WHERE excluded_from_leaderboard = FALSE", Integer.class
        );
        return value == null ? 0 : value;
    }

    public int participants(LeaderboardPeriod period, LocalDate date, int rulesVersion) {
        String predicate = period == LeaderboardPeriod.TODAY ? "r.competition_date = ?" : "r.rules_version = ?";
        Object value = period == LeaderboardPeriod.TODAY ? Date.valueOf(date) : rulesVersion;
        Integer result = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.player_id)
                FROM competition_run r JOIN competition_player p ON p.id = r.player_id
                WHERE p.excluded_from_leaderboard = FALSE AND r.score >= 1 AND
                """ + predicate, Integer.class, value);
        return result == null ? 0 : result;
    }

    private CompetitionResults.LeaderboardEntry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp achieved = rs.getTimestamp("achieved_at");
        return new CompetitionResults.LeaderboardEntry(
                rs.getObject("public_id", UUID.class), rs.getString("public_tag"),
                rs.getString("display_name"), rs.getLong("rank"), rs.getInt("best_streak"),
                achieved == null ? Instant.EPOCH : achieved.toInstant()
        );
    }
}
