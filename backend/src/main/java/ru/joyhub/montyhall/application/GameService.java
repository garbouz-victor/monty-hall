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
import ru.joyhub.montyhall.persistence.GameRoundEntity;
import ru.joyhub.montyhall.persistence.GameRoundRepository;
import ru.joyhub.montyhall.persistence.StatsQueryRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final StatsQueryRepository stats;
    private final GameRandomSource random;
    private final CommitmentService commitments;
    private final Clock clock;
    private final int maxOpenGames;
    private final Duration openGameWindow;

    public GameService(
            GameRoundRepository games,
            StatsQueryRepository stats,
            GameRandomSource random,
            CommitmentService commitments,
            Clock clock,
            @Value("${joyhub.abuse.max-open-games:20}") int maxOpenGames,
            @Value("${joyhub.abuse.open-game-window:PT24H}") Duration openGameWindow
    ) {
        this.games = games;
        this.stats = stats;
        this.random = random;
        this.commitments = commitments;
        this.clock = clock;
        this.maxOpenGames = maxOpenGames;
        this.openGameWindow = openGameWindow;
    }

    @Transactional
    public Created create(UUID visitorId) {
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
        games.save(GameRoundEntity.create(gameId, keyBox, nonce, commitment, visitorId, now));
        log.info("game_created gameId={}", gameId);
        return new Created(gameId, commitment);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Choice choose(UUID gameId, UUID visitorId, int selectedBox) {
        MontyHallRules.validateBox(selectedBox);
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

        HostMove move = MontyHallRules.hostMove(game.getKeyBox(), selectedBox, random);
        game.recordChoice(selectedBox, move.openedBox(), clock.instant());
        log.info("game_choice_made gameId={} selectedBox={} openedBox={}", gameId, selectedBox, move.openedBox());
        return new Choice(selectedBox, move.openedBox(), move.switchToBox());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Completed decide(UUID gameId, UUID visitorId, Strategy strategy) {
        GameRoundEntity game = ownedGameForUpdate(gameId, visitorId);

        if (game.getState() == GameState.CREATED) {
            throw new InvalidGameTransitionException("Make an initial choice before the final decision");
        }
        if (game.getState() == GameState.COMPLETED) {
            if (game.getStrategy() == strategy) {
                return completedResult(game);
            }
            throw new InvalidGameTransitionException("The game has already been completed with another strategy");
        }

        int finalChoice = MontyHallRules.finalChoice(
                game.getInitialChoice(),
                game.getOpenedBox(),
                strategy
        );
        boolean won = finalChoice == game.getKeyBox();
        game.complete(strategy, finalChoice, won, clock.instant());
        log.info("game_completed gameId={} strategy={} won={}", gameId, strategy, won);
        return completedResult(game);
    }

    @Transactional(readOnly = true)
    public GameSnapshot getState(UUID gameId, UUID visitorId) {
        GameRoundEntity game = games.findByIdAndVisitorId(gameId, visitorId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

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

    private static Choice choiceResult(GameRoundEntity game) {
        int switchTo = MontyHallRules.finalChoice(
                game.getInitialChoice(),
                game.getOpenedBox(),
                Strategy.SWITCH
        );
        return new Choice(game.getInitialChoice(), game.getOpenedBox(), switchTo);
    }

    private static Completed completedResult(GameRoundEntity game) {
        return new Completed(
                game.getId(),
                game.getInitialChoice(),
                game.getOpenedBox(),
                game.getFinalChoice(),
                game.getStrategy(),
                game.getKeyBox(),
                Boolean.TRUE.equals(game.getWon()),
                game.getNonce(),
                game.getCommitment()
        );
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
