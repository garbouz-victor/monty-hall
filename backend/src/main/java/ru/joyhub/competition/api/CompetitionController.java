package ru.joyhub.competition.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.joyhub.competition.application.CompetitionResults;
import ru.joyhub.competition.application.CompetitionService;
import ru.joyhub.competition.domain.LeaderboardPeriod;
import ru.joyhub.montyhall.api.VisitorIdentityService;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/competition")
public class CompetitionController {
    private final CompetitionService competition;
    private final CompetitionCredentialService credentials;
    private final VisitorIdentityService visitors;

    public CompetitionController(
            CompetitionService competition, CompetitionCredentialService credentials,
            VisitorIdentityService visitors
    ) {
        this.competition = competition;
        this.credentials = credentials;
        this.visitors = visitors;
    }

    @GetMapping("/me")
    public ResponseEntity<CompetitionApiModels.MeResponse> me(
            HttpServletRequest request, HttpServletResponse response
    ) {
        CompetitionResults.Me me = competition.me(credentials.findPlayerId(request));
        if (me.visitorId() != null) visitors.write(response, me.visitorId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CompetitionApiModels.MeResponse.from(me));
    }

    @PutMapping("/profile")
    public ResponseEntity<CompetitionApiModels.ProfileResponse> profile(
            @Valid @RequestBody CompetitionApiModels.ProfileRequest body,
            HttpServletRequest request, HttpServletResponse response
    ) {
        Optional<UUID> playerId = credentials.findPlayerId(request);
        CompetitionCredentialService.IssuedCredential issued = credentials.issue();
        CompetitionResults.ProfileResult result = competition.saveProfile(
                playerId, body.displayName(), issued.hash(), issued.expiresAt()
        );
        if (playerId.isEmpty()) credentials.write(response, issued.rawToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CompetitionApiModels.ProfileResponse(
                        new CompetitionApiModels.PlayerResponse(
                                result.player().publicPlayerId(), result.player().publicTag(), result.player().displayName()
                        )
                ));
    }

    @PostMapping("/runs")
    public ResponseEntity<CompetitionApiModels.StartRunResponse> start(
            @RequestHeader("Idempotency-Key") UUID requestId, HttpServletRequest request
    ) {
        CompetitionResults.RunResult result = competition.startRun(credentials.requirePlayerId(request), requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CompetitionApiModels.StartRunResponse(
                        CompetitionApiModels.RunResponse.from(result.run()), result.replayed()
                ));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<CompetitionApiModels.MeResponse> run(
            @PathVariable UUID runId, HttpServletRequest request, HttpServletResponse response
    ) {
        CompetitionResults.Me me = competition.runState(credentials.requirePlayerId(request), runId);
        if (me.visitorId() != null) visitors.write(response, me.visitorId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CompetitionApiModels.MeResponse.from(me));
    }

    @PostMapping("/runs/{runId}/rounds")
    public ResponseEntity<CompetitionApiModels.CreateRoundResponse> createRound(
            @PathVariable UUID runId, @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody CompetitionApiModels.RoundRequest body,
            HttpServletRequest request, HttpServletResponse response
    ) {
        CompetitionResults.RoundResult result = competition.createRound(
                credentials.requirePlayerId(request), runId, requestId,
                body.expectedRoundNumber(), visitors.find(request)
        );
        visitors.write(response, result.game().visitorId());
        return ResponseEntity.status(result.replayed() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(new CompetitionApiModels.CreateRoundResponse(
                        result.game().gameId(), result.game().commitment(), result.roundNumber(),
                        CompetitionApiModels.RunResponse.from(result.run()), result.replayed()
                ));
    }

    @PostMapping("/runs/{runId}/abandon")
    public ResponseEntity<CompetitionApiModels.RunResponse> abandon(
            @PathVariable UUID runId, HttpServletRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                CompetitionApiModels.RunResponse.from(
                        competition.abandon(credentials.requirePlayerId(request), runId)
                )
        );
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<CompetitionApiModels.LeaderboardResponse> leaderboard(
            @RequestParam(defaultValue = "TODAY") LeaderboardPeriod period,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request
    ) {
        CompetitionResults.Leaderboard board = competition.leaderboard(
                period, limit, Optional.empty()
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=15")
                .body(CompetitionApiModels.LeaderboardResponse.from(board));
    }
}
