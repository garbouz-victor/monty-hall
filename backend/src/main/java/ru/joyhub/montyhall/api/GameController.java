package ru.joyhub.montyhall.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.joyhub.montyhall.application.GameService;

import java.util.UUID;

import static ru.joyhub.montyhall.api.GameApiModels.ChoiceRequest;
import static ru.joyhub.montyhall.api.GameApiModels.ChoiceResponse;
import static ru.joyhub.montyhall.api.GameApiModels.CreateGameResponse;
import static ru.joyhub.montyhall.api.GameApiModels.DecisionRequest;
import static ru.joyhub.montyhall.api.GameApiModels.DecisionResponse;
import static ru.joyhub.montyhall.api.GameApiModels.GameStateResponse;
import static ru.joyhub.montyhall.api.GameApiModels.StatsResponse;

@RestController
@RequestMapping("/api/v1")
public class GameController {

    private final GameService gameService;
    private final VisitorIdentityService visitors;

    public GameController(GameService gameService, VisitorIdentityService visitors) {
        this.gameService = gameService;
        this.visitors = visitors;
    }

    @PostMapping("/games")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameResponse createGame(
            @RequestHeader("Idempotency-Key") UUID creationRequestId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        var result = gameService.create(creationRequestId, visitors.find(request));
        visitors.write(response, result.visitorId());
        return CreateGameResponse.from(result);
    }

    @PostMapping("/games/{gameId}/choice")
    public ChoiceResponse makeChoice(
            @PathVariable UUID gameId,
            @Valid @RequestBody ChoiceRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID visitorId = visitors.resolve(request, response);
        return ChoiceResponse.from(gameService.choose(gameId, visitorId, body.box()));
    }

    @GetMapping("/games/{gameId}")
    public GameStateResponse getGameState(
            @PathVariable UUID gameId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID visitorId = visitors.resolve(request, response);
        return GameStateResponse.from(gameService.getState(gameId, visitorId));
    }

    @PostMapping("/games/{gameId}/decision")
    public DecisionResponse decide(
            @PathVariable UUID gameId,
            @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID visitorId = visitors.resolve(request, response);
        return DecisionResponse.from(gameService.decide(gameId, visitorId, body.strategy()));
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return StatsResponse.from(gameService.getStats());
    }
}
