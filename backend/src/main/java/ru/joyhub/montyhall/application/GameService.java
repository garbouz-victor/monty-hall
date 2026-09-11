package ru.joyhub.montyhall.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.joyhub.montyhall.domain.GameState;
import ru.joyhub.montyhall.domain.HostMove;
import ru.joyhub.montyhall.domain.MontyHallRules;
import ru.joyhub.montyhall.domain.Strategy;
import ru.joyhub.montyhall.persistence.GameCreationRepository;
import ru.joyhub.montyhall.persistence.GameRoundEntity;
import ru.joyhub.montyhall.persistence.GameRoundRepository;
import ru.joyhub.montyhall.persistence.StatsQueryRepository;
import ru.joyhub.competition.application.CompetitionException;
import ru.joyhub.competition.application.CompetitionResults;
import ru.joyhub.competition.application.CompetitionService;
import ru.joyhub.competition.domain.CompetitionRunStatus;
import ru.joyhub.competition.persistence.CompetitionPlayerRepository;
import ru.joyhub.competition.persistence.CompetitionRunEntity;
import ru.joyhub.competition.persistence.CompetitionRunRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static ru.joyhub.montyhall.application.GameResults.Choice;
import static ru.joyhub.montyhall.application.GameResults.Completed;
import static ru.joyhub.montyhall.application.GameResults.Created;
import static ru.joyhub.montyhall.application.GameResults.GameSnapshot;
import static ru.joyhub.montyhall.application.GameResults.Stats;
import static ru.joyhub.montyhall.application.GameResults.StrategyStats;
import static ru.joyhub.montyhall.application.GameResults.Theoretical;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final double SWITCH_THEORY = 2.0 / 3.0;
    private static final double STAY_THEORY = 1.0 / 3.0;

    private final GameRoundRepository games;
    private final GameCreationRepository gameCreation;
    private final StatsQueryRepository stats;
    private final GameRandomSource random;
    private final CommitmentService commitments;
    private final Clock clock;
    private final int maxOpenGames;
    private final Duration openGameWindow;
    private final CompetitionPlayerRepository competitionPlayers;
    private final CompetitionRunRepository competitionRuns;
    private final CompetitionService competition;

    public GameService(
            GameRoundRepository games,
            GameCreationRepository gameCreation,
            StatsQueryRepository stats,
            GameRandomSource random,
            CommitmentService commitments,
            Clock clock,
            @Value("${joyhub.abuse.max-open-games:20}") int maxOpenGames,
            @Value("${joyhub.abuse.open-game-window:PT24H}") Duration openGameWindow,
            CompetitionPlayerRepository competitionPlayers,
            CompetitionRunRepository competitionRuns,
            CompetitionService competition
    ) {
        this.games = games;
        this.gameCreation = gameCreation;
        this.stats = stats;
        this.random = random;
        this.commitments = commitments;
        this.clock = clock;
        this.maxOpenGames = maxOpenGames;
        this.openGameWindow = openGameWindow;
        this.competitionPlayers = competitionPlayers;
        this.competitionRuns = competitionRuns;
        this.competition = competition;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(UUID creationRequestId, Optional<UUID> requestVisitorId) {
        return create(creationRequestId, requestVisitorId, Optional.empty());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(
            UUID creationRequestId, Optional<UUID> requestVisitorId, Optional<UUID> competitionPlayerId
    ) {
        Optional<GameCreationRepository.CreationRecord> existing = gameCreation.find(creationRequestId);
        if (existing.isPresent()) {
            return replay(existing.get(), requestVisitorId, competitionPlayerId);
        }

        UUID visitorId = requestVisitorId.orElseGet(UUID::randomUUID);
        Instant now = clock.instant();
        long openGames = games.countByVisitorIdAndStateNotAndCreatedAtAfter(
                visitorId,
                GameState.COMPLETED,
                now.minus(openGameWindow)
        );
        if (openGames >= maxOpenGames) {
            throw new TooManyOpenGamesException();
        }

        UUID gameId = UUID.randomUUID();
        int keyBox = random.nextInt(MontyHallRules.BOX_COUNT) + 1;
        String nonce = random.newNonce();
        String commitment = commitments.create(gameId, keyBox, nonce);
        GameCreationRepository.CreationAttempt attempt = gameCreation.insertOrGet(
                creationRequestId,
                gameId,
                keyBox,
                nonce,
                commitment,
                visitorId,
                now
        );

        GameCreationRepository.CreationRecord stored = attempt.game();
        authorizeCreationRecord(stored, competitionPlayerId);
        ensureVisitorMayReplay(stored, requestVisitorId);
        if (attempt.created()) {
            log.info("game_created gameId={}", stored.gameId());
        } else {
            log.info("game_create_replayed gameId={}", stored.gameId());
        }
        return createdResult(stored);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Choice choose(UUID gameId, UUID visitorId, int selectedBox) {
        return choose(gameId, visitorId, selectedBox, Optional.empty());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = CompetitionException.class)
    public Choice choose(UUID gameId, UUID visitorId, int selectedBox, Optional<UUID> competitionPlayerId) {
        MontyHallRules.validateBox(selectedBox);
        CompetitionRunEntity run = lockCompetition(gameId, visitorId, competitionPlayerId);
        GameRoundEntity game = ownedGameForUpdate(gameId, visitorId);

        if (game.getState() == GameState.COMPLETED) {
            throw new InvalidGameTransitionException("A completed game cannot be changed");
        }
        if (game.getState() == GameState.CHOICE_MADE) {
            if (game.getInitialChoice() == selectedBox) {
                return choiceResult(game);
            }
            throw new InvalidGameTransitionException("The initial choice has already been recorded");
        }

        if (run != null) requireActiveBeforeDeadline(run, clock.instant());

        HostMove move = MontyHallRules.hostMove(game.getKeyBox(), selectedBox, random);
        game.recordChoice(selectedBox, move.openedBox(), clock.instant());
        log.info("game_choice_made gameId={} selectedBox={} openedBox={}", gameId, selectedBox, move.openedBox());
        return new Choice(selectedBox, move.openedBox(), move.switchToBox());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Completed decide(UUID gameId, UUID visitorId, Strategy strategy) {
        return decide(gameId, visitorId, strategy, Optional.empty());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = CompetitionException.class)
    public Completed decide(UUID gameId, UUID visitorId, Strategy strategy, Optional<UUID> competitionPlayerId) {
        CompetitionRunEntity run = lockCompetition(gameId, visitorId, competitionPlayerId);
        GameRoundEntity game = ownedGameForUpdate(gameId, visitorId);

        if (game.getState() == GameState.CREATED) {
            throw new InvalidGameTransitionException("Make an initial choice before the final decision");
        }
        if (game.getState() == GameState.COMPLETED) {
            if (game.getStrategy() == strategy) {
                log.info("game_decision_replayed gameId={}", gameId);
                return completedResult(game, run);
            }
            throw new InvalidGameTransitionException("The game has already been completed with another strategy");
        }

        Instant acceptedAt = clock.instant();
        if (run != null) requireActiveBeforeDeadline(run, acceptedAt);

        int finalChoice = MontyHallRules.finalChoice(
                game.getInitialChoice(),
                game.getOpenedBox(),
                strategy
        );
        boolean won = finalChoice == game.getKeyBox();
        game.complete(strategy, finalChoice, won, acceptedAt);
        if (run != null) {
            if (won) {
                run.recordWin(acceptedAt);
                log.info("competition_score_confirmed runId={} gameId={} score={}", run.getId(), gameId, run.getScore());
            } else {
                run.recordLoss(acceptedAt);
                log.info("competition_run_ended runId={} gameId={} status=LOST score={}", run.getId(), gameId, run.getScore());
            }
            games.flush();
        }
        log.info("game_completed gameId={} strategy={} won={}", gameId, strategy, won);
        return completedResult(game, run);
    }

    @Transactional(readOnly = true)
    public GameSnapshot getState(UUID gameId, UUID visitorId) {
        return getState(gameId, visitorId, Optional.empty());
    }

    @Transactional(readOnly = true)
    public GameSnapshot getState(UUID gameId, UUID visitorId, Optional<UUID> competitionPlayerId) {
        GameRoundEntity game = games.findByIdAndVisitorId(gameId, visitorId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        authorizeCompetitionRead(game, competitionPlayerId);

        if (game.getState() == GameState.CREATED) {
            return new GameSnapshot(
                    game.getId(), game.getState(), game.getCommitment(),
                    null, null, null, null, null, null, null, null
            );
        }

        if (game.getState() == GameState.CHOICE_MADE) {
            int switchToBox = MontyHallRules.finalChoice(
                    game.getInitialChoice(), game.getOpenedBox(), Strategy.SWITCH
            );
            return new GameSnapshot(
                    game.getId(), game.getState(), game.getCommitment(),
                    game.getInitialChoice(), game.getOpenedBox(), switchToBox,
                    null, null, null, null, null
            );
        }

        return new GameSnapshot(
                game.getId(), game.getState(), game.getCommitment(),
                game.getInitialChoice(), game.getOpenedBox(), null,
                game.getFinalChoice(), game.getStrategy(), game.getKeyBox(),
                game.getWon(), game.getNonce()
        );
    }

    @Transactional(readOnly = true)
    public Stats getStats() {
        StatsQueryRepository.StatsRow row = stats.load();
        return new Stats(
                row.totalGames(),
                strategyStats(row.switchGames(), row.switchWins()),
                strategyStats(row.stayGames(), row.stayWins()),
                new Theoretical(SWITCH_THEORY, STAY_THEORY),
                row.updatedAt() == null ? clock.instant() : row.updatedAt()
        );
    }

    private GameRoundEntity ownedGameForUpdate(UUID gameId, UUID visitorId) {
        return games.findOwnedForUpdate(gameId, visitorId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }

    private Created replay(
            GameCreationRepository.CreationRecord existing,
            Optional<UUID> requestVisitorId,
            Optional<UUID> competitionPlayerId
    ) {
        authorizeCreationRecord(existing, competitionPlayerId);
        ensureVisitorMayReplay(existing, requestVisitorId);
        log.info("game_create_replayed gameId={}", existing.gameId());
        return createdResult(existing);
    }

    private static void authorizeCreationRecord(
            GameCreationRepository.CreationRecord existing, Optional<UUID> competitionPlayerId
    ) {
        if (existing.competitionRunId() != null
                && (competitionPlayerId.isEmpty() || !competitionPlayerId.get().equals(existing.competitionPlayerId()))) {
            throw CompetitionException.notFound();
        }
    }

    private static void ensureVisitorMayReplay(
            GameCreationRepository.CreationRecord existing,
            Optional<UUID> requestVisitorId
    ) {
        if (requestVisitorId.isPresent() && !requestVisitorId.get().equals(existing.visitorId())) {
            throw new IdempotencyKeyConflictException();
        }
    }

    private static Created createdResult(GameCreationRepository.CreationRecord game) {
        return new Created(game.gameId(), game.commitment(), game.visitorId());
    }

    private static Choice choiceResult(GameRoundEntity game) {
        int switchTo = MontyHallRules.finalChoice(
                game.getInitialChoice(),
                game.getOpenedBox(),
                Strategy.SWITCH
        );
        return new Choice(game.getInitialChoice(), game.getOpenedBox(), switchTo);
    }

    private Completed completedResult(GameRoundEntity game, CompetitionRunEntity run) {
        CompetitionResults.Run competitionSnapshot = run == null
                ? null : competition.currentRunSnapshot(run, clock.instant());
        return new Completed(
                game.getId(),
                game.getInitialChoice(),
                game.getOpenedBox(),
                game.getFinalChoice(),
                game.getStrategy(),
                game.getKeyBox(),
                Boolean.TRUE.equals(game.getWon()),
                game.getNonce(),
                game.getCommitment(),
                competitionSnapshot
        );
    }

    private CompetitionRunEntity lockCompetition(
            UUID gameId, UUID visitorId, Optional<UUID> competitionPlayerId
    ) {
        GameRoundRepository.GameLockPreview preview = games.findLockPreview(gameId, visitorId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (preview.getCompetitionRunId() == null) return null;
        UUID playerId = competitionPlayerId.orElseThrow(CompetitionException::notFound);
        competitionPlayers.findForUpdate(playerId).orElseThrow(CompetitionException::notFound);
        CompetitionRunEntity run = competitionRuns.findForUpdate(preview.getCompetitionRunId())
                .filter(value -> value.getPlayerId().equals(playerId))
                .orElseThrow(CompetitionException::notFound);
        return run;
    }

    private void authorizeCompetitionRead(GameRoundEntity game, Optional<UUID> competitionPlayerId) {
        if (game.getCompetitionRunId() == null) return;
        UUID playerId = competitionPlayerId.orElseThrow(CompetitionException::notFound);
        competitionRuns.findById(game.getCompetitionRunId())
                .filter(run -> run.getPlayerId().equals(playerId))
                .orElseThrow(CompetitionException::notFound);
    }

    private static void requireActiveBeforeDeadline(CompetitionRunEntity run, Instant acceptedAt) {
        if (run.getStatus() != CompetitionRunStatus.ACTIVE) {
            throw CompetitionException.conflict("Попытка уже завершена");
        }
        if (run.isExpiredAt(acceptedAt)) {
            run.expire(acceptedAt);
            throw CompetitionException.expired();
        }
    }

    private static StrategyStats strategyStats(long games, long wins) {
        return new StrategyStats(
                games,
                wins,
                games - wins,
                games == 0 ? 0.0 : (double) wins / games
        );
    }
}
