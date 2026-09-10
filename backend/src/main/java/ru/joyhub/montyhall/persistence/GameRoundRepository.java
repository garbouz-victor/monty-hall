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

    long countByVisitorIdAndStateNotAndCreatedAtAfter(UUID visitorId, GameState state, Instant createdAfter);
}

