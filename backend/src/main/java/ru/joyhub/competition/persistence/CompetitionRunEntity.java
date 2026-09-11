package ru.joyhub.competition.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import ru.joyhub.competition.domain.CompetitionRunStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "competition_run")
public class CompetitionRunEntity {
    @Id
    private UUID id;
    @Column(name = "player_id", nullable = false)
    private UUID playerId;
    @Column(name = "start_request_id", nullable = false)
    private UUID startRequestId;
    @Column(name = "competition_date", nullable = false)
    private LocalDate competitionDate;
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CompetitionRunStatus status;
    @Column(nullable = false)
    private int score;
    @Column(name = "score_reached_at")
    private Instant scoreReachedAt;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "rules_version", nullable = false)
    private int rulesVersion;
    @Version
    private long version;

    protected CompetitionRunEntity() {
    }

    public static CompetitionRunEntity start(
            UUID id, UUID playerId, UUID startRequestId, LocalDate competitionDate,
            int attemptNumber, Instant startedAt, Instant expiresAt, int rulesVersion
    ) {
        CompetitionRunEntity run = new CompetitionRunEntity();
        run.id = id;
        run.playerId = playerId;
        run.startRequestId = startRequestId;
        run.competitionDate = competitionDate;
        run.attemptNumber = attemptNumber;
        run.status = CompetitionRunStatus.ACTIVE;
        run.startedAt = startedAt;
        run.expiresAt = expiresAt;
        run.rulesVersion = rulesVersion;
        return run;
    }

    public void recordWin(Instant acceptedAt) {
        if (status != CompetitionRunStatus.ACTIVE || !acceptedAt.isBefore(expiresAt)) {
            throw new IllegalStateException("Only a live run can receive a win");
        }
        score += 1;
        scoreReachedAt = acceptedAt;
    }

    public void recordLoss(Instant acceptedAt) {
        finish(CompetitionRunStatus.LOST, acceptedAt);
    }

    public void abandon(Instant acceptedAt) {
        finish(CompetitionRunStatus.ABANDONED, acceptedAt);
    }

    public void expire(Instant acceptedAt) {
        finish(CompetitionRunStatus.EXPIRED, acceptedAt);
    }

    private void finish(CompetitionRunStatus target, Instant acceptedAt) {
        if (status != CompetitionRunStatus.ACTIVE) return;
        status = target;
        endedAt = acceptedAt;
    }

    public boolean isExpiredAt(Instant acceptedAt) {
        return !acceptedAt.isBefore(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public UUID getStartRequestId() { return startRequestId; }
    public LocalDate getCompetitionDate() { return competitionDate; }
    public int getAttemptNumber() { return attemptNumber; }
    public CompetitionRunStatus getStatus() { return status; }
    public int getScore() { return score; }
    public Instant getScoreReachedAt() { return scoreReachedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getEndedAt() { return endedAt; }
    public int getRulesVersion() { return rulesVersion; }
}
