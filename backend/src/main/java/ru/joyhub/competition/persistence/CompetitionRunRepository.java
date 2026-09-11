package ru.joyhub.competition.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.joyhub.competition.domain.CompetitionRunStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompetitionRunRepository extends JpaRepository<CompetitionRunEntity, UUID> {
    Optional<CompetitionRunEntity> findByPlayerIdAndStartRequestId(UUID playerId, UUID startRequestId);
    Optional<CompetitionRunEntity> findFirstByPlayerIdAndStatusOrderByStartedAtDesc(UUID playerId, CompetitionRunStatus status);
    Optional<CompetitionRunEntity> findFirstByPlayerIdOrderByStartedAtDesc(UUID playerId);
    long countByPlayerIdAndCompetitionDate(UUID playerId, LocalDate competitionDate);
    List<CompetitionRunEntity> findByPlayerIdAndStatus(UUID playerId, CompetitionRunStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from CompetitionRunEntity run where run.id = :id")
    Optional<CompetitionRunEntity> findForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from CompetitionRunEntity run where run.id = :id and run.playerId = :playerId")
    Optional<CompetitionRunEntity> findOwnedForUpdate(@Param("id") UUID id, @Param("playerId") UUID playerId);
}
