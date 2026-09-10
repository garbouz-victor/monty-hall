package ru.joyhub.montyhall.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class StatsQueryRepository {

    private static final String QUERY = """
            SELECT
                COUNT(*) AS total_games,
                COUNT(*) FILTER (WHERE strategy = 'SWITCH') AS switch_games,
                COUNT(*) FILTER (WHERE strategy = 'SWITCH' AND won) AS switch_wins,
                COUNT(*) FILTER (WHERE strategy = 'STAY') AS stay_games,
                COUNT(*) FILTER (WHERE strategy = 'STAY' AND won) AS stay_wins,
                MAX(completed_at) AS updated_at
            FROM game_round
            WHERE state = 'COMPLETED'
            """;

    private final JdbcTemplate jdbcTemplate;

    public StatsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StatsRow load() {
        return jdbcTemplate.queryForObject(QUERY, (resultSet, rowNumber) -> {
            Timestamp updatedAt = resultSet.getTimestamp("updated_at");
            return new StatsRow(
                    resultSet.getLong("total_games"),
                    resultSet.getLong("switch_games"),
                    resultSet.getLong("switch_wins"),
                    resultSet.getLong("stay_games"),
                    resultSet.getLong("stay_wins"),
                    updatedAt == null ? null : updatedAt.toInstant()
            );
        });
    }

    public record StatsRow(
            long totalGames,
            long switchGames,
            long switchWins,
            long stayGames,
            long stayWins,
            Instant updatedAt
    ) {
    }
}

