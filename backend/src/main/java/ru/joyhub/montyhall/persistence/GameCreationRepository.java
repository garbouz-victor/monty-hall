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
            SELECT id, commitment, visitor_id
            FROM game_round
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
                        resultSet.getObject("visitor_id", UUID.class)
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

    public record CreationRecord(UUID gameId, String commitment, UUID visitorId) {
    }

    public record CreationAttempt(CreationRecord game, boolean created) {
    }
}
