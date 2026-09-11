package ru.joyhub.montyhall.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GameCreationRepository {

    private static final String FIND = """
            SELECT g.id, g.commitment, g.visitor_id, g.competition_run_id,
                   r.player_id AS competition_player_id, g.competition_round_number
            FROM game_round g
            LEFT JOIN competition_run r ON r.id = g.competition_run_id
            WHERE creation_request_id = ?
            """;

    private static final String INSERT = """
            INSERT INTO game_round (
                id, creation_request_id, state, key_box, nonce, commitment,
                visitor_id, created_at, version
            )
            VALUES (?, ?, 'CREATED', ?, ?, ?, ?, ?, 0)
            ON CONFLICT (creation_request_id) DO NOTHING
            """;

    private static final String INSERT_COMPETITION = """
            INSERT INTO game_round (
                id, creation_request_id, state, key_box, nonce, commitment,
                visitor_id, created_at, version, competition_run_id, competition_round_number
            )
            VALUES (?, ?, 'CREATED', ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (creation_request_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public GameCreationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CreationRecord> find(UUID creationRequestId) {
        return jdbcTemplate.query(
                FIND,
                (resultSet, rowNumber) -> new CreationRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("commitment"),
                        resultSet.getObject("visitor_id", UUID.class),
                        resultSet.getObject("competition_run_id", UUID.class),
                        resultSet.getObject("competition_player_id", UUID.class),
                        resultSet.getObject("competition_round_number", Integer.class)
                ),
                creationRequestId
        ).stream().findFirst();
    }

    public CreationAttempt insertOrGet(
            UUID creationRequestId,
            UUID gameId,
            int keyBox,
            String nonce,
            String commitment,
            UUID visitorId,
            Instant createdAt
    ) {
        int inserted = jdbcTemplate.update(
                INSERT,
                gameId,
                creationRequestId,
                keyBox,
                nonce,
                commitment,
                visitorId,
                Timestamp.from(createdAt)
        );

        CreationRecord stored = find(creationRequestId)
                .orElseThrow(() -> new IllegalStateException("Created game could not be read back"));
        return new CreationAttempt(stored, inserted == 1);
    }

    public CreationAttempt insertCompetitionOrGet(
            UUID creationRequestId, UUID gameId, int keyBox, String nonce, String commitment,
            UUID visitorId, Instant createdAt, UUID runId, int roundNumber
    ) {
        int inserted = jdbcTemplate.update(
                INSERT_COMPETITION, gameId, creationRequestId, keyBox, nonce, commitment,
                visitorId, Timestamp.from(createdAt), runId, roundNumber
        );
        CreationRecord stored = find(creationRequestId)
                .orElseThrow(() -> new IllegalStateException("Created competition game could not be read back"));
        return new CreationAttempt(stored, inserted == 1);
    }

    public record CreationRecord(
            UUID gameId, String commitment, UUID visitorId, UUID competitionRunId,
            UUID competitionPlayerId, Integer competitionRoundNumber
    ) {
    }

    public record CreationAttempt(CreationRecord game, boolean created) {
    }
}
