package ru.joyhub.montyhall.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.joyhub.montyhall.application.GameResults;
import ru.joyhub.montyhall.domain.GameState;
import ru.joyhub.montyhall.domain.Strategy;

import java.time.Instant;
import java.util.UUID;

public final class GameApiModels {

    private GameApiModels() {
    }

    public record CreateGameResponse(UUID gameId, String commitment) {
        static CreateGameResponse from(GameResults.Created result) {
            return new CreateGameResponse(result.gameId(), result.commitment());
        }
    }

    public record ChoiceRequest(@Min(1) @Max(3) int box) {
    }

    public record ChoiceResponse(int selectedBox, int openedBox, int switchToBox) {
        static ChoiceResponse from(GameResults.Choice result) {
            return new ChoiceResponse(result.selectedBox(), result.openedBox(), result.switchToBox());
        }
    }

    public record DecisionRequest(@NotNull Strategy strategy) {
    }

    public record DecisionResponse(
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
        static DecisionResponse from(GameResults.Completed result) {
            return new DecisionResponse(
                    result.gameId(),
                    result.initialChoice(),
                    result.openedBox(),
                    result.finalChoice(),
                    result.strategy(),
                    result.keyBox(),
                    result.won(),
                    result.nonce(),
                    result.commitment()
            );
        }
    }

    public record GameStateResponse(
            UUID gameId,
            GameState state,
            String commitment,
            Integer initialChoice,
            Integer openedBox,
            Integer switchToBox,
            Integer finalChoice,
            Strategy strategy,
            Integer keyBox,
            Boolean won,
            String nonce
    ) {
        static GameStateResponse from(GameResults.GameSnapshot snapshot) {
            return new GameStateResponse(
                    snapshot.gameId(), snapshot.state(), snapshot.commitment(),
                    snapshot.initialChoice(), snapshot.openedBox(), snapshot.switchToBox(),
                    snapshot.finalChoice(), snapshot.strategy(), snapshot.keyBox(),
                    snapshot.won(), snapshot.nonce()
            );
        }
    }

    public record StrategyStatsResponse(long games, long wins, long losses, double winRate) {
        static StrategyStatsResponse from(GameResults.StrategyStats stats) {
            return new StrategyStatsResponse(stats.games(), stats.wins(), stats.losses(), stats.winRate());
        }
    }

    public record TheoreticalResponse(double switchWinRate, double stayWinRate) {
        static TheoreticalResponse from(GameResults.Theoretical theoretical) {
            return new TheoreticalResponse(theoretical.switchWinRate(), theoretical.stayWinRate());
        }
    }

    public record StatsResponse(
            long totalCompletedGames,
            @JsonProperty("switch") StrategyStatsResponse switched,
            @JsonProperty("stay") StrategyStatsResponse stayed,
            TheoreticalResponse theoretical,
            Instant updatedAt
    ) {
        static StatsResponse from(GameResults.Stats stats) {
            return new StatsResponse(
                    stats.totalCompletedGames(),
                    StrategyStatsResponse.from(stats.switchStats()),
                    StrategyStatsResponse.from(stats.stayStats()),
                    TheoreticalResponse.from(stats.theoretical()),
                    stats.updatedAt()
            );
        }
    }
}
