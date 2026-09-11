package ru.joyhub.montyhall.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.joyhub.montyhall.domain.GameState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GameRoundRepository extends JpaRepository<GameRoundEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from GameRoundEntity game where game.id = :id and game.visitorId = :visitorId")
    Optional<GameRoundEntity> findOwnedForUpdate(@Param("id") UUID id, @Param("visitorId") UUID visitorId);

    Optional<GameRoundEntity> findByIdAndVisitorId(UUID id, UUID visitorId);

    @Query("""
            select game.id as id, game.competitionRunId as competitionRunId
            from GameRoundEntity game
            where game.id = :id and game.visitorId = :visitorId
            """)
    Optional<GameLockPreview> findLockPreview(@Param("id") UUID id, @Param("visitorId") UUID visitorId);

    Optional<GameRoundEntity> findByCompetitionRunIdAndCompetitionRoundNumber(UUID runId, int roundNumber);

    Optional<GameRoundEntity> findFirstByCompetitionRunIdOrderByCompetitionRoundNumberDesc(UUID runId);

    Optional<GameRoundEntity> findByCreationRequestId(UUID creationRequestId);

    long countByVisitorIdAndStateNotAndCreatedAtAfter(UUID visitorId, GameState state, Instant createdAfter);

    interface GameLockPreview {
        UUID getId();
        UUID getCompetitionRunId();
    }
}
