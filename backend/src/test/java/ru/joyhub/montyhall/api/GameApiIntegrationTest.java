package ru.joyhub.montyhall.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.joyhub.montyhall.application.CommitmentService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GameApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("joyhub_test")
            .withUsername("joyhub")
            .withPassword("joyhub");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("joyhub.visitor-cookie.secure", () -> false);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CommitmentService commitments;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM game_round");
    }

    @Test
    void fullSwitchFlowHidesSecretUntilCompletionAndRevealsVerifiableCommitment() throws Exception {
        StartedGame game = startGame();

        MvcResult choice = choose(game, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedBox").value(3))
                .andExpect(jsonPath("$.openedBox").isNumber())
                .andExpect(jsonPath("$.switchToBox").isNumber())
                .andExpect(jsonPath("$.keyBox").doesNotExist())
                .andExpect(jsonPath("$.nonce").doesNotExist())
                .andReturn();
        JsonNode choiceJson = json(choice);
        assertThat(choiceJson.get("openedBox").asInt()).isNotEqualTo(3);
        assertThat(choiceJson.get("switchToBox").asInt()).isNotEqualTo(3);

        MvcResult completed = decide(game, "SWITCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("SWITCH"))
                .andReturn();
        JsonNode result = json(completed);

        assertThat(result.get("finalChoice").asInt()).isEqualTo(choiceJson.get("switchToBox").asInt());
        assertThat(result.get("commitment").asText()).isEqualTo(game.commitment());
        assertThat(commitments.create(
                game.id(),
                result.get("keyBox").asInt(),
                result.get("nonce").asText()
        )).isEqualTo(game.commitment());
    }

    @Test
    void stayKeepsInitialChoice() throws Exception {
        StartedGame game = startGame();
        choose(game, 2).andExpect(status().isOk());

        decide(game, "STAY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialChoice").value(2))
                .andExpect(jsonPath("$.finalChoice").value(2))
                .andExpect(jsonPath("$.strategy").value("STAY"));
    }

    @Test
    void repeatedChoiceWithSameBoxAndDecisionWithSameStrategyAreIdempotent() throws Exception {
        StartedGame game = startGame();
        String firstChoice = choose(game, 1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String repeatedChoice = choose(game, 1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeatedChoice).isEqualTo(firstChoice);

        String firstDecision = decide(game, "SWITCH").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String repeatedDecision = decide(game, "SWITCH").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeatedDecision).isEqualTo(firstDecision);

        mvc.perform(get("/api/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCompletedGames").value(1))
                .andExpect(jsonPath("$.switch.games").value(1));
    }

    @Test
    void rejectsChangingChoiceOrStrategyAndChoiceAfterCompletion() throws Exception {
        StartedGame game = startGame();
        choose(game, 1).andExpect(status().isOk());
        choose(game, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_GAME_STATE"));
        decide(game, "STAY").andExpect(status().isOk());
        decide(game, "SWITCH").andExpect(status().isConflict());
        choose(game, 1).andExpect(status().isConflict());
    }

    @Test
    void rejectsDecisionBeforeChoice() throws Exception {
        StartedGame game = startGame();
        decide(game, "SWITCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_GAME_STATE"));
    }

    @Test
    void validatesBoxAndUnknownGame() throws Exception {
        StartedGame game = startGame();
        choose(game, 0).andExpect(status().isBadRequest());
        choose(game, 4).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/games/{id}/choice", UUID.randomUUID())
                        .cookie(game.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"box\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));

        mvc.perform(post("/api/v1/games/not-a-uuid/choice")
                        .cookie(game.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"box\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void doesNotExposeAnotherVisitorsGame() throws Exception {
        StartedGame firstVisitorGame = startGame();
        StartedGame secondVisitorGame = startGame();

        mvc.perform(post("/api/v1/games/{id}/choice", firstVisitorGame.id())
                        .cookie(secondVisitorGame.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"box\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aggregatesOnlyCompletedGamesWithinEachStrategy() throws Exception {
        StartedGame abandoned = startGame();
        choose(abandoned, 1).andExpect(status().isOk());

        long expectedSwitchWins = completeAndReadWin("SWITCH", 1);
        expectedSwitchWins += completeAndReadWin("SWITCH", 2);
        long expectedStayWins = completeAndReadWin("STAY", 3);

        mvc.perform(get("/api/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCompletedGames").value(3))
                .andExpect(jsonPath("$.switch.games").value(2))
                .andExpect(jsonPath("$.switch.wins").value(expectedSwitchWins))
                .andExpect(jsonPath("$.switch.losses").value(2 - expectedSwitchWins))
                .andExpect(jsonPath("$.stay.games").value(1))
                .andExpect(jsonPath("$.stay.wins").value(expectedStayWins))
                .andExpect(jsonPath("$.stay.losses").value(1 - expectedStayWins))
                .andExpect(jsonPath("$.theoretical.switchWinRate").value(2.0 / 3.0))
                .andExpect(jsonPath("$.theoretical.stayWinRate").value(1.0 / 3.0));
    }

    @Test
    void healthIsSmallAndDatabaseAware() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.time").exists())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    private long completeAndReadWin(String strategy, int box) throws Exception {
        StartedGame game = startGame();
        choose(game, box).andExpect(status().isOk());
        JsonNode result = json(decide(game, strategy).andExpect(status().isOk()).andReturn());
        return result.get("won").asBoolean() ? 1 : 0;
    }

    private StartedGame startGame() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/games"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.gameId").isString())
                .andExpect(jsonPath("$.commitment").isString())
                .andExpect(jsonPath("$.keyBox").doesNotExist())
                .andExpect(jsonPath("$.nonce").doesNotExist())
                .andReturn();
        JsonNode body = json(result);
        return new StartedGame(
                UUID.fromString(body.get("gameId").asText()),
                body.get("commitment").asText(),
                result.getResponse().getCookie(VisitorIdentityService.COOKIE_NAME)
        );
    }

    private org.springframework.test.web.servlet.ResultActions choose(StartedGame game, int box) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/choice", game.id())
                .cookie(game.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"box\":%d}".formatted(box)));
    }

    private org.springframework.test.web.servlet.ResultActions decide(StartedGame game, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/decision", game.id())
                .cookie(game.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"%s\"}".formatted(strategy)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record StartedGame(UUID id, String commitment, Cookie cookie) {
    }
}
