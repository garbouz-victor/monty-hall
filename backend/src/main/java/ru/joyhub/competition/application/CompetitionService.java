package ru.joyhub.competition.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.joyhub.competition.domain.CompetitionRules;
import ru.joyhub.competition.domain.CompetitionRunStatus;
import ru.joyhub.competition.domain.LeaderboardPeriod;
import ru.joyhub.competition.persistence.CompetitionLeaderboardRepository;
import ru.joyhub.competition.persistence.CompetitionPlayerEntity;
import ru.joyhub.competition.persistence.CompetitionPlayerRepository;
import ru.joyhub.competition.persistence.CompetitionRunEntity;
import ru.joyhub.competition.persistence.CompetitionRunRepository;
import ru.joyhub.montyhall.application.CommitmentService;
import ru.joyhub.montyhall.application.GameRandomSource;
import ru.joyhub.montyhall.application.GameResults;
import ru.joyhub.montyhall.domain.GameState;
import ru.joyhub.montyhall.domain.MontyHallRules;
import ru.joyhub.montyhall.domain.Strategy;
import ru.joyhub.montyhall.persistence.GameCreationRepository;
import ru.joyhub.montyhall.persistence.GameRoundEntity;
import ru.joyhub.montyhall.persistence.GameRoundRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompetitionService {
    private static final Logger log = LoggerFactory.getLogger(CompetitionService.class);
    private final CompetitionPlayerRepository players;
    private final CompetitionRunRepository runs;
    private final CompetitionLeaderboardRepository leaderboard;
    private final GameRoundRepository games;
    private final GameCreationRepository gameCreation;
    private final GameRandomSource random;
    private final CommitmentService commitments;
    private final CompetitionNameValidator names;
    private final Clock clock;
    private final int dailyLimit;

    public CompetitionService(
            CompetitionPlayerRepository players, CompetitionRunRepository runs,
            CompetitionLeaderboardRepository leaderboard, GameRoundRepository games,
            GameCreationRepository gameCreation, GameRandomSource random,
            CommitmentService commitments, CompetitionNameValidator names, Clock clock,
            @Value("${joyhub.competition.daily-attempt-limit:5}") int dailyLimit
    ) {
        this.players = players;
        this.runs = runs;
        this.leaderboard = leaderboard;
        this.games = games;
        this.gameCreation = gameCreation;
        this.random = random;
        this.commitments = commitments;
        this.names = names;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    @Transactional
    public CompetitionResults.ProfileResult saveProfile(
            Optional<UUID> authenticatedPlayer, String rawName,
            String newCredentialHash, Instant newCredentialExpiresAt
    ) {
        String displayName = names.normalize(rawName);
        Instant now = clock.instant();
        if (authenticatedPlayer.isPresent()) {
            CompetitionPlayerEntity player = lockPlayer(authenticatedPlayer.get());
            player.rename(displayName, now);
            return new CompetitionResults.ProfileResult(playerResult(player), null);
        }
        UUID publicId = UUID.randomUUID();
        CompetitionPlayerEntity player = CompetitionPlayerEntity.create(
                UUID.randomUUID(), publicId, "JH-" + publicId.toString().substring(0, 9).toUpperCase(),
                displayName, newCredentialHash, newCredentialExpiresAt, now
        );
        players.save(player);
        log.info("competition_profile_created publicPlayerId={}", player.getPublicId());
        return new CompetitionResults.ProfileResult(playerResult(player), newCredentialHash);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CompetitionResults.RunResult startRun(UUID playerId, UUID requestId) {
        CompetitionPlayerEntity player = lockPlayer(playerId);
        Optional<CompetitionRunEntity> replay = runs.findByPlayerIdAndStartRequestId(playerId, requestId);
        if (replay.isPresent()) {
            log.info("competition_run_start_replayed runId={}", replay.get().getId());
            return new CompetitionResults.RunResult(runResult(replay.get(), clock.instant()), true);
        }

        Instant acceptedAt = clock.instant();
        expireOldRuns(player, acceptedAt);
        if (runs.findFirstByPlayerIdAndStatusOrderByStartedAtDesc(playerId, CompetitionRunStatus.ACTIVE).isPresent()) {
            throw CompetitionException.conflict("Сначала продолжите или завершите активную попытку");
        }
        CompetitionRules.DayWindow day = CompetitionRules.dayAt(acceptedAt);
        long used = runs.countByPlayerIdAndCompetitionDate(playerId, day.date());
        if (used >= dailyLimit) {
            log.info("competition_quota_rejected playerId={}", playerId);
            throw CompetitionException.quota();
        }
        CompetitionRunEntity run = CompetitionRunEntity.start(
                UUID.randomUUID(), playerId, requestId, day.date(), Math.toIntExact(used + 1),
                acceptedAt, day.expiresAt(), CompetitionRules.RULES_VERSION
        );
        runs.saveAndFlush(run);
        log.info("competition_run_started runId={} attempt={}", run.getId(), run.getAttemptNumber());
        return new CompetitionResults.RunResult(runResult(run, acceptedAt), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = CompetitionException.class)
    public CompetitionResults.RoundResult createRound(
            UUID playerId, UUID runId, UUID creationRequestId, int expectedRoundNumber,
            Optional<UUID> requestVisitorId
    ) {
        CompetitionPlayerEntity player = lockPlayer(playerId);
        CompetitionRunEntity run = lockOwnedRun(runId, player.getId());
        Instant acceptedAt = clock.instant();
        requireLive(run, acceptedAt);

        Optional<GameRoundEntity> existingNumber = games.findByCompetitionRunIdAndCompetitionRoundNumber(
                runId, expectedRoundNumber
        );
        if (existingNumber.isPresent()) {
            return roundReplay(existingNumber.get(), run, requestVisitorId);
        }

        Optional<GameRoundEntity> latest = games.findFirstByCompetitionRunIdOrderByCompetitionRoundNumberDesc(runId);
        int requiredNumber = latest.map(game -> game.getCompetitionRoundNumber() + 1).orElse(1);
        if (expectedRoundNumber != requiredNumber) {
            throw CompetitionException.conflict("Ожидается раунд №" + requiredNumber);
        }
        if (latest.isPresent() && latest.get().getState() != GameState.COMPLETED) {
            throw CompetitionException.conflict("Сначала завершите текущий раунд");
        }
        if (latest.isPresent() && !Boolean.TRUE.equals(latest.get().getWon())) {
            throw CompetitionException.conflict("Попытка уже завершена поражением");
        }

        UUID visitorId = requestVisitorId.orElseGet(UUID::randomUUID);
        UUID gameId = UUID.randomUUID();
        int keyBox = random.nextInt(MontyHallRules.BOX_COUNT) + 1;
        String nonce = random.newNonce();
        String commitment = commitments.create(gameId, keyBox, nonce);
        GameCreationRepository.CreationAttempt attempt = gameCreation.insertCompetitionOrGet(
                creationRequestId, gameId, keyBox, nonce, commitment, visitorId,
                acceptedAt, runId, expectedRoundNumber
        );
        GameCreationRepository.CreationRecord stored = attempt.game();
        if (!runId.equals(stored.competitionRunId()) || stored.competitionRoundNumber() != expectedRoundNumber
                || !playerId.equals(stored.competitionPlayerId())) {
            throw new ru.joyhub.montyhall.application.IdempotencyKeyConflictException();
        }
        ensureVisitor(stored.visitorId(), requestVisitorId);
        log.info(attempt.created() ? "competition_round_created runId={} gameId={} roundNumber={}"
                        : "competition_round_create_replayed runId={} gameId={} roundNumber={}",
                runId, stored.gameId(), expectedRoundNumber);
        return new CompetitionResults.RoundResult(
                new GameResults.Created(stored.gameId(), stored.commitment(), stored.visitorId()),
                runResult(run, acceptedAt), expectedRoundNumber, !attempt.created()
        );
    }

    @Transactional
    public CompetitionResults.Run abandon(UUID playerId, UUID runId) {
        lockPlayer(playerId);
        CompetitionRunEntity run = lockOwnedRun(runId, playerId);
        Instant acceptedAt = clock.instant();
        if (run.getStatus() == CompetitionRunStatus.ACTIVE) {
            if (run.isExpiredAt(acceptedAt)) run.expire(acceptedAt);
            else run.abandon(acceptedAt);
            log.info("competition_run_ended runId={} status={}", runId, run.getStatus());
        }
        return runResult(run, acceptedAt);
    }

    @Transactional(readOnly = true)
    public CompetitionResults.Me me(Optional<UUID> playerId) {
        Instant now = clock.instant();
        CompetitionRules.DayWindow day = CompetitionRules.dayAt(now);
        if (playerId.isEmpty()) {
            return new CompetitionResults.Me(false, now, CompetitionRules.ZONE.getId(), day.date(),
                    dailyLimit, dailyLimit, null, 0, 0, null, null, null, null, null);
        }
        CompetitionPlayerEntity player = players.findById(playerId.get()).orElseThrow(CompetitionException::unauthorized);
        CompetitionRunEntity run = runs.findFirstByPlayerIdAndStatusOrderByStartedAtDesc(player.getId(), CompetitionRunStatus.ACTIVE)
                .orElseGet(() -> runs.findFirstByPlayerIdOrderByStartedAtDesc(player.getId()).orElse(null));
        GameRoundEntity game = run == null ? null
                : games.findFirstByCompetitionRunIdOrderByCompetitionRoundNumberDesc(run.getId()).orElse(null);
        return meResult(player, run, game, now);
    }

    @Transactional(readOnly = true)
    public CompetitionResults.Me runState(UUID playerId, UUID runId) {
        CompetitionPlayerEntity player = players.findById(playerId).orElseThrow(CompetitionException::unauthorized);
        CompetitionRunEntity run = runs.findById(runId)
                .filter(value -> value.getPlayerId().equals(playerId)).orElseThrow(CompetitionException::notFound);
        GameRoundEntity game = games.findFirstByCompetitionRunIdOrderByCompetitionRoundNumberDesc(runId).orElse(null);
        return meResult(player, run, game, clock.instant());
    }

    @Transactional(readOnly = true)
    public CompetitionResults.Leaderboard leaderboard(
            LeaderboardPeriod period, int limit, Optional<UUID> playerId
    ) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("limit must be 1..10");
        Instant now = clock.instant();
        LocalDate date = CompetitionRules.dayAt(now).date();
        return new CompetitionResults.Leaderboard(
                period.name(), period == LeaderboardPeriod.TODAY ? date : null,
                now, CompetitionRules.ZONE.getId(), leaderboard.registeredProfiles(),
                leaderboard.participants(period, date, CompetitionRules.RULES_VERSION),
                leaderboard.top(period, date, CompetitionRules.RULES_VERSION, limit),
                playerId.flatMap(id -> leaderboard.player(period, date, CompetitionRules.RULES_VERSION, id)).orElse(null)
        );
    }

    public CompetitionResults.Run currentRunSnapshot(CompetitionRunEntity run, Instant acceptedAt) {
        return runResult(run, acceptedAt);
    }

    private CompetitionResults.Me meResult(
            CompetitionPlayerEntity player, CompetitionRunEntity run, GameRoundEntity game, Instant now
    ) {
        LocalDate date = CompetitionRules.dayAt(now).date();
        int used = Math.toIntExact(runs.countByPlayerIdAndCompetitionDate(player.getId(), date));
        int todayBest = leaderboard.best(player.getId(), LeaderboardPeriod.TODAY, date, CompetitionRules.RULES_VERSION);
        int allBest = leaderboard.best(player.getId(), LeaderboardPeriod.ALL_TIME, date, CompetitionRules.RULES_VERSION);
        Long todayRank = leaderboard.player(LeaderboardPeriod.TODAY, date, CompetitionRules.RULES_VERSION, player.getId())
                .map(CompetitionResults.LeaderboardEntry::rank).orElse(null);
        Long allRank = leaderboard.player(LeaderboardPeriod.ALL_TIME, date, CompetitionRules.RULES_VERSION, player.getId())
                .map(CompetitionResults.LeaderboardEntry::rank).orElse(null);
        CompetitionResults.Run runResult = run == null ? null : new CompetitionResults.Run(
                run.getId(), effectiveStatus(run, now), run.getScore(), run.getAttemptNumber(),
                run.getCompetitionDate(), run.getStartedAt(), run.getExpiresAt(), run.getEndedAt(),
                run.getRulesVersion(), Math.max(0, dailyLimit - used), todayBest, allBest, todayRank, allRank
        );
        CompetitionResults.Round round = game == null ? null
                : new CompetitionResults.Round(game.getCompetitionRoundNumber(), snapshot(game));
        return new CompetitionResults.Me(
                true, now, CompetitionRules.ZONE.getId(), date, dailyLimit, Math.max(0, dailyLimit - used),
                playerResult(player), todayBest, allBest, todayRank, allRank, runResult, round,
                game == null ? null : game.getVisitorId()
        );
    }

    private CompetitionResults.Run runResult(CompetitionRunEntity run, Instant now) {
        LocalDate today = CompetitionRules.dayAt(now).date();
        int remaining = Math.max(0, dailyLimit - Math.toIntExact(runs.countByPlayerIdAndCompetitionDate(run.getPlayerId(), today)));
        int todayBest = leaderboard.best(run.getPlayerId(), LeaderboardPeriod.TODAY, today, CompetitionRules.RULES_VERSION);
        int allBest = leaderboard.best(run.getPlayerId(), LeaderboardPeriod.ALL_TIME, today, CompetitionRules.RULES_VERSION);
        return new CompetitionResults.Run(
                run.getId(), effectiveStatus(run, now), run.getScore(), run.getAttemptNumber(),
                run.getCompetitionDate(), run.getStartedAt(), run.getExpiresAt(), run.getEndedAt(),
                run.getRulesVersion(), remaining, todayBest, allBest,
                leaderboard.player(LeaderboardPeriod.TODAY, today, CompetitionRules.RULES_VERSION, run.getPlayerId())
                        .map(CompetitionResults.LeaderboardEntry::rank).orElse(null),
                leaderboard.player(LeaderboardPeriod.ALL_TIME, today, CompetitionRules.RULES_VERSION, run.getPlayerId())
                        .map(CompetitionResults.LeaderboardEntry::rank).orElse(null)
        );
    }

    private static CompetitionRunStatus effectiveStatus(CompetitionRunEntity run, Instant now) {
        return run.getStatus() == CompetitionRunStatus.ACTIVE && run.isExpiredAt(now)
                ? CompetitionRunStatus.EXPIRED : run.getStatus();
    }

    private CompetitionResults.RoundResult roundReplay(
            GameRoundEntity game, CompetitionRunEntity run, Optional<UUID> requestVisitorId
    ) {
        ensureVisitor(game.getVisitorId(), requestVisitorId);
        log.info("competition_round_create_replayed runId={} gameId={} roundNumber={}",
                run.getId(), game.getId(), game.getCompetitionRoundNumber());
        return new CompetitionResults.RoundResult(
                new GameResults.Created(game.getId(), game.getCommitment(), game.getVisitorId()),
                runResult(run, clock.instant()), game.getCompetitionRoundNumber(), true
        );
    }

    private static void ensureVisitor(UUID actual, Optional<UUID> supplied) {
        if (supplied.isPresent() && !supplied.get().equals(actual)) {
            throw new ru.joyhub.montyhall.application.IdempotencyKeyConflictException();
        }
    }

    private void expireOldRuns(CompetitionPlayerEntity player, Instant acceptedAt) {
        for (CompetitionRunEntity run : runs.findByPlayerIdAndStatus(player.getId(), CompetitionRunStatus.ACTIVE)) {
            if (run.isExpiredAt(acceptedAt)) {
                run.expire(acceptedAt);
                log.info("competition_run_expired runId={}", run.getId());
            }
        }
    }

    private static GameResults.GameSnapshot snapshot(GameRoundEntity game) {
        Integer switchTo = game.getState() == GameState.CHOICE_MADE
                ? MontyHallRules.finalChoice(game.getInitialChoice(), game.getOpenedBox(), Strategy.SWITCH) : null;
        boolean completed = game.getState() == GameState.COMPLETED;
        return new GameResults.GameSnapshot(
                game.getId(), game.getState(), game.getCommitment(),
                game.getState() == GameState.CREATED ? null : game.getInitialChoice(),
                game.getState() == GameState.CREATED ? null : game.getOpenedBox(), switchTo,
                completed ? game.getFinalChoice() : null, completed ? game.getStrategy() : null,
                completed ? game.getKeyBox() : null, completed ? game.getWon() : null,
                completed ? game.getNonce() : null
        );
    }

    private void requireLive(CompetitionRunEntity run, Instant acceptedAt) {
        if (run.getStatus() != CompetitionRunStatus.ACTIVE) {
            throw CompetitionException.conflict("Попытка уже завершена");
        }
        if (run.isExpiredAt(acceptedAt)) {
            run.expire(acceptedAt);
            log.info("competition_run_expired runId={}", run.getId());
            throw CompetitionException.expired();
        }
    }

    private CompetitionPlayerEntity lockPlayer(UUID playerId) {
        return players.findForUpdate(playerId).orElseThrow(CompetitionException::unauthorized);
    }

    private CompetitionRunEntity lockOwnedRun(UUID runId, UUID playerId) {
        return runs.findOwnedForUpdate(runId, playerId).orElseThrow(CompetitionException::notFound);
    }

    private static CompetitionResults.Player playerResult(CompetitionPlayerEntity player) {
        return new CompetitionResults.Player(player.getPublicId(), player.getPublicTag(), player.getDisplayName());
    }
}
