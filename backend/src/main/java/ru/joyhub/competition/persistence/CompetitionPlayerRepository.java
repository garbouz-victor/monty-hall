package ru.joyhub.competition.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CompetitionPlayerRepository extends JpaRepository<CompetitionPlayerEntity, UUID> {
    Optional<CompetitionPlayerEntity> findByCredentialHashAndCredentialExpiresAtAfter(String credentialHash, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select player from CompetitionPlayerEntity player where player.id = :id")
    Optional<CompetitionPlayerEntity> findForUpdate(@Param("id") UUID id);
}
