package ru.joyhub.montyhall.application;

import ru.joyhub.montyhall.domain.Strategy;

import java.time.Instant;
import java.util.UUID;

public final class GameResults {

    private GameResults() {
    }

    public record Created(UUID gameId, String commitment) {
    }

    public record Choice(int selectedBox, int openedBox, int switchToBox) {
    }

    public record Completed(
            UUID gameId,
            int initialChoice,
            int openedBox,
            int finalChoice,
            Strategy strategy,
            int keyBox,
            boolean won,
            String nonce,
            String commitment
    ) {
    }

    public record StrategyStats(long games, long wins, long losses, double winRate) {
    }

    public record Theoretical(double switchWinRate, double stayWinRate) {
    }

    public record Stats(
            long totalCompletedGames,
            StrategyStats switchStats,
            StrategyStats stayStats,
            Theoretical theoretical,
            Instant updatedAt
    ) {
    }
}

