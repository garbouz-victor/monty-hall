package ru.joyhub.competition.api;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ru.joyhub.JoyHubApplication.class, CompetitionApiIntegrationTest.ClockConfig.class})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompetitionApiIntegrationTest {
    private static final String CSRF = "X-JoyHub-CSRF";
    private static final String COMP_COOKIE = "joyhub_competition_test";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("joyhub_competition_test").withUsername("joyhub").withPassword("joyhub");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("joyhub.visitor-cookie.secure", () -> false);
        registry.add("joyhub.competition.cookie.name", () -> COMP_COOKIE);
        registry.add("joyhub.competition.cookie.secure", () -> true);
        registry.add("joyhub.competition.daily-attempt-limit", () -> 5);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM game_round");
        jdbc.update("DELETE FROM competition_run");
        jdbc.update("DELETE FROM competition_player");
        clock.set(Instant.parse("2026-09-10T12:00:00Z"));
    }

    @Test
    void profileStartTwoWinsAndLossProduceOneRunWithScoreTwo() throws Exception {
        Session session = profile("Виктор");
        JsonNode run = json(start(session, UUID.randomUUID()).andExpect(status().isOk()).andReturn()).get("run");
        UUID runId = UUID.fromString(run.get("runId").stringValue());

        Session withVisitor = finishRound(session, runId, 1, true).session();
        assertThat(runScore(runId)).isEqualTo(1);
        finishRound(withVisitor, runId, 2, true);
        assertThat(runScore(runId)).isEqualTo(2);
        RoundOutcome loss = finishRound(withVisitor, runId, 3, false);

        assertThat(loss.result().get("won").asBoolean()).isFalse();
        assertThat(loss.result().get("competition").get("status").stringValue()).isEqualTo("LOST");
        assertThat(loss.result().get("competition").get("score").asInt()).isEqualTo(2);
        assertThat(runScore(runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round WHERE competition_run_id = ?", Long.class, runId)).isEqualTo(3);

        mvc.perform(get("/api/v1/competition/leaderboard").param("period", "TODAY").param("limit", "10")
                        .cookie(session.competitionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].displayName").value("Виктор"))
                .andExpect(jsonPath("$.entries[0].bestStreak").value(2))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }

    @Test
    void firstLossKeepsZeroAndNextExplicitRunStartsAtZero() throws Exception {
        Session session = profile("Игрок 1");
        UUID first = runId(start(session, UUID.randomUUID()).andReturn());
        Session withVisitor = finishRound(session, first, 1, false).session();
        UUID second = runId(start(withVisitor, UUID.randomUUID()).andExpect(status().isOk()).andReturn());

        assertThat(runScore(first)).isZero();
        assertThat(runScore(second)).isZero();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void startAndRoundCreationAreIdempotentAndDoNotConsumeExtraQuota() throws Exception {
        Session session = profile("Retry_User");
        UUID startKey = UUID.randomUUID();
        JsonNode first = json(start(session, startKey).andReturn());
        JsonNode replay = json(start(session, startKey).andReturn());
        UUID runId = UUID.fromString(first.get("run").get("runId").stringValue());
        assertThat(replay.get("run").get("runId").stringValue()).isEqualTo(runId.toString());
        assertThat(replay.get("replayed").asBoolean()).isTrue();

        UUID firstKey = UUID.randomUUID();
        JsonNode created = json(createRound(session, runId, firstKey, 1).andReturn());
        JsonNode otherKeySameNumber = json(createRound(session, runId, UUID.randomUUID(), 1).andReturn());
        assertThat(otherKeySameNumber.get("gameId").stringValue()).isEqualTo(created.get("gameId").stringValue());
        createRound(session, runId, UUID.randomUUID(), 2).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_run", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameStartAndRoundReturnSingleRows() throws Exception {
        Session session = profile("Параллельно");
        UUID startKey = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return start(session, startKey).andReturn(); });
            var b = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return start(session, startKey).andReturn(); });
            gate.countDown();
            UUID first = runId(a.get(20, TimeUnit.SECONDS));
            UUID second = runId(b.get(20, TimeUnit.SECONDS));
            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_run", Long.class)).isEqualTo(1);

            UUID runId = first;
            CountDownLatch roundGate = new CountDownLatch(1);
            var r1 = pool.submit(() -> { roundGate.await(5, TimeUnit.SECONDS); return createRound(session, runId, UUID.randomUUID(), 1).andReturn(); });
            var r2 = pool.submit(() -> { roundGate.await(5, TimeUnit.SECONDS); return createRound(session, runId, UUID.randomUUID(), 1).andReturn(); });
            roundGate.countDown();
            assertThat(json(r1.get(20, TimeUnit.SECONDS)).get("gameId").stringValue())
                    .isEqualTo(json(r2.get(20, TimeUnit.SECONDS)).get("gameId").stringValue());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round", Long.class)).isEqualTo(1);
        }
    }

    @Test
    void concurrentCompetitionDecisionsAreAppliedOnceAndDifferentStrategiesConflict() throws Exception {
        Session createdSession = profile("Параллельный ход");
        UUID runId = runId(start(createdSession, UUID.randomUUID()).andReturn());
        JsonNode first = json(createRound(createdSession, runId, UUID.randomUUID(), 1).andReturn());
        Session session = new Session(createdSession.competitionCookie(), lastVisitorCookie);
        UUID firstGame = UUID.fromString(first.get("gameId").stringValue());
        int firstKey = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, firstGame);
        choose(session, session.visitorCookie(), firstGame, firstKey).andExpect(status().isOk());

        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch sameGate = new CountDownLatch(1);
            var sameA = pool.submit(() -> { sameGate.await(5, TimeUnit.SECONDS); return decide(session, session.visitorCookie(), firstGame, "STAY").andReturn().getResponse().getStatus(); });
            var sameB = pool.submit(() -> { sameGate.await(5, TimeUnit.SECONDS); return decide(session, session.visitorCookie(), firstGame, "STAY").andReturn().getResponse().getStatus(); });
            sameGate.countDown();
            assertThat(sameA.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(sameB.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(runScore(runId)).isEqualTo(1);

            JsonNode second = json(createRound(session, runId, UUID.randomUUID(), 2).andReturn());
            UUID secondGame = UUID.fromString(second.get("gameId").stringValue());
            int secondKey = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, secondGame);
            choose(session, session.visitorCookie(), secondGame, secondKey).andExpect(status().isOk());

            CountDownLatch differentGate = new CountDownLatch(1);
            var stay = pool.submit(() -> { differentGate.await(5, TimeUnit.SECONDS); return decide(session, session.visitorCookie(), secondGame, "STAY").andReturn().getResponse().getStatus(); });
            var change = pool.submit(() -> { differentGate.await(5, TimeUnit.SECONDS); return decide(session, session.visitorCookie(), secondGame, "SWITCH").andReturn().getResponse().getStatus(); });
            differentGate.countDown();
            assertThat(java.util.List.of(stay.get(20, TimeUnit.SECONDS), change.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round WHERE id = ? AND state = 'COMPLETED'", Long.class, firstGame)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round WHERE competition_run_id = ? AND state = 'COMPLETED'", Long.class, runId)).isEqualTo(2);
    }

    @Test
    void sixthAttemptIsRejectedButReplayStillWorks() throws Exception {
        Session session = profile("Пять попыток");
        UUID lastKey = null;
        UUID lastRun = null;
        for (int i = 0; i < 5; i++) {
            lastKey = UUID.randomUUID();
            lastRun = runId(start(session, lastKey).andExpect(status().isOk()).andReturn());
            abandon(session, lastRun).andExpect(status().isOk());
        }
        start(session, UUID.randomUUID()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMPETITION_DAILY_LIMIT"));
        start(session, lastKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value(lastRun.toString()))
                .andExpect(jsonPath("$.replayed").value(true));

        clock.set(Instant.parse("2026-09-10T21:00:00Z"));
        start(session, lastKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value(lastRun.toString()))
                .andExpect(jsonPath("$.replayed").value(true));
        start(session, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.attemptNumber").value(1));
    }

    @Test
    void winAndLossDecisionReplaysNeverDoubleCountAndDifferentDecisionConflicts() throws Exception {
        Session session = profile("Надёжный Retry");
        UUID runId = runId(start(session, UUID.randomUUID()).andReturn());

        JsonNode first = json(createRound(session, runId, UUID.randomUUID(), 1).andReturn());
        UUID firstGame = UUID.fromString(first.get("gameId").stringValue());
        Cookie visitor = lastVisitorCookie;
        session = new Session(session.competitionCookie(), visitor);
        int firstKey = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, firstGame);
        choose(session, visitor, firstGame, firstKey).andExpect(status().isOk());
        decide(session, visitor, firstGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.score").value(1))
                .andExpect(jsonPath("$.competition.todayBest").value(1))
                .andExpect(jsonPath("$.competition.allTimeBest").value(1))
                .andExpect(jsonPath("$.competition.todayRank").value(1));
        decide(session, visitor, firstGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.score").value(1));
        decide(session, visitor, firstGame, "SWITCH").andExpect(status().isConflict());
        assertThat(runScore(runId)).isEqualTo(1);

        JsonNode second = json(createRound(session, runId, UUID.randomUUID(), 2).andReturn());
        UUID secondGame = UUID.fromString(second.get("gameId").stringValue());
        int secondKey = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, secondGame);
        choose(session, visitor, secondGame, secondKey % 3 + 1).andExpect(status().isOk());
        decide(session, visitor, secondGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.status").value("LOST"))
                .andExpect(jsonPath("$.competition.score").value(1));
        decide(session, visitor, secondGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.score").value(1));
        assertThat(runScore(runId)).isEqualTo(1);
        createRound(session, runId, UUID.randomUUID(), 3).andExpect(status().isConflict());
    }

    @Test
    void failedScorePersistenceRollsBackTheCompletedGameAtomically() throws Exception {
        Session original = profile("Атомарность");
        UUID runId = runId(start(original, UUID.randomUUID()).andReturn());
        JsonNode created = json(createRound(original, runId, UUID.randomUUID(), 1).andReturn());
        Session session = new Session(original.competitionCookie(), lastVisitorCookie);
        UUID gameId = UUID.fromString(created.get("gameId").stringValue());
        int key = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, gameId);
        choose(session, session.visitorCookie(), gameId, key).andExpect(status().isOk());

        jdbc.update("UPDATE competition_run SET score = ? WHERE id = ?", Integer.MAX_VALUE, runId);
        decide(session, session.visitorCookie(), gameId, "STAY").andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT state FROM game_round WHERE id = ?", String.class, gameId))
                .isEqualTo("CHOICE_MADE");
        assertThat(runScore(runId)).isEqualTo(Integer.MAX_VALUE);
        assertThat(jdbc.queryForObject("SELECT status FROM competition_run WHERE id = ?", String.class, runId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void credentialCsrfAndCompetitiveGameOwnershipAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/competition/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_player", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_round", Long.class)).isZero();
        mvc.perform(put("/api/v1/competition/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Safe Name\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        profile("<script>", status().isBadRequest());

        Session owner = profile("Один Игрок");
        Session attacker = profile("Другой Игрок");
        UUID runId = runId(start(owner, UUID.randomUUID()).andReturn());
        UUID roundKey = UUID.randomUUID();
        JsonNode created = json(createRound(owner, runId, roundKey, 1).andReturn());
        UUID gameId = UUID.fromString(created.get("gameId").stringValue());
        Cookie visitor = createdCookie("joyhub_visitor", created.get("_visitor") == null ? null : created.get("_visitor").stringValue());
        visitor = lastVisitorCookie;

        mvc.perform(get("/api/v1/games/{id}", gameId).cookie(visitor, attacker.competitionCookie()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/games/{id}/choice", gameId).cookie(visitor)
                        .header(CSRF, "1").contentType(MediaType.APPLICATION_JSON).content("{\"box\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/competition/runs/{id}", runId).cookie(attacker.competitionCookie()))
                .andExpect(status().isNotFound());
        createRound(attacker, runId, UUID.randomUUID(), 1).andExpect(status().isNotFound());
        abandon(attacker, runId).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/games").cookie(visitor, attacker.competitionCookie())
                        .header("Idempotency-Key", roundKey))
                .andExpect(status().isNotFound());

        choose(owner, visitor, gameId, 1).andExpect(status().isOk());
        decide(attacker, visitor, gameId, "STAY").andExpect(status().isNotFound());
    }

    @Test
    void moscowBoundaryExpiresOldRunAndAllowsNewDayWithoutJvmTimezone() throws Exception {
        clock.set(Instant.parse("2026-09-10T20:59:59Z")); // 23:59:59 MSK
        Session session = profile("Граница Дня");
        UUID old = runId(start(session, UUID.randomUUID()).andReturn());
        JsonNode first = json(createRound(session, old, UUID.randomUUID(), 1).andReturn());
        Cookie visitor = lastVisitorCookie;
        session = new Session(session.competitionCookie(), visitor);
        int key = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class,
                UUID.fromString(first.get("gameId").stringValue()));
        UUID firstGame = UUID.fromString(first.get("gameId").stringValue());
        choose(session, visitor, firstGame, key).andExpect(status().isOk());
        decide(session, visitor, firstGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.score").value(1));

        JsonNode second = json(createRound(session, old, UUID.randomUUID(), 2).andReturn());
        UUID secondGame = UUID.fromString(second.get("gameId").stringValue());
        int secondKey = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, secondGame);
        choose(session, visitor, secondGame, secondKey).andExpect(status().isOk());

        clock.set(Instant.parse("2026-09-10T21:00:00Z")); // exactly 00:00 MSK
        decide(session, visitor, firstGame, "STAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.score").value(1));
        decide(session, visitor, secondGame, "STAY")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPETITION_RUN_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM competition_run WHERE id = ?", String.class, old)).isEqualTo("EXPIRED");

        mvc.perform(get("/api/v1/competition/leaderboard").param("period", "TODAY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(get("/api/v1/competition/leaderboard").param("period", "ALL_TIME"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].bestStreak").value(1));
        UUID next = runId(start(session, UUID.randomUUID()).andExpect(status().isOk()).andReturn());
        assertThat(next).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT competition_date FROM competition_run WHERE id = ?", java.sql.Date.class, next).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 9, 11));
    }

    @Test
    void leaderboardUsesMaxPerProfileAndCompetitionRanksOneTwoTwoFour() throws Exception {
        UUID first = insertPlayerWithRun("Первый", 7, Instant.parse("2026-09-10T10:00:00Z"));
        insertRun(first, 2, Instant.parse("2026-09-10T11:00:00Z")); // must not be summed
        insertPlayerWithRun("Второй", 5, Instant.parse("2026-09-10T10:01:00Z"));
        insertPlayerWithRun("Третий", 5, Instant.parse("2026-09-10T10:02:00Z"));
        UUID fourth = insertPlayerWithRun("Четвёртый", 3, Instant.parse("2026-09-10T10:03:00Z"));

        MvcResult result = mvc.perform(get("/api/v1/competition/leaderboard")
                        .param("period", "TODAY").param("limit", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(4)).andReturn();
        JsonNode entries = json(result).get("entries");
        assertThat(entries.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(entries.get(1).get("rank").asInt()).isEqualTo(2);
        assertThat(entries.get(2).get("rank").asInt()).isEqualTo(2);
        assertThat(entries.get(3).get("rank").asInt()).isEqualTo(4);
        assertThat(entries.get(0).get("bestStreak").asInt()).isEqualTo(7);
        assertThat(entries.get(1).get("displayName").stringValue()).isEqualTo("Второй");
        assertThat(entries.get(2).get("displayName").stringValue()).isEqualTo("Третий");

        jdbc.update("UPDATE competition_player SET excluded_from_leaderboard = TRUE WHERE id = ?", fourth);
        mvc.perform(get("/api/v1/competition/leaderboard").param("period", "TODAY").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.registeredProfiles").value(3));
    }

    @Test
    void renameKeepsProfileAndQuotaAndSecretsNeverAppearInDtos() throws Exception {
        Session session = profile("Старое Имя");
        UUID runId = runId(start(session, UUID.randomUUID()).andReturn());
        abandon(session, runId).andExpect(status().isOk());
        mvc.perform(put("/api/v1/competition/profile").cookie(session.competitionCookie()).header(CSRF, "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Новое Имя\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.player.displayName").value("Новое Имя"))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.credentialHash").doesNotExist());
        mvc.perform(get("/api/v1/competition/me").cookie(session.competitionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingAttempts").value(4))
                .andExpect(jsonPath("$.player.displayName").value("Новое Имя"))
                .andExpect(jsonPath("$.visitorId").doesNotExist());
    }

    private volatile Cookie lastVisitorCookie;

    private UUID insertPlayerWithRun(String name, int score, Instant achieved) {
        UUID player = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO competition_player
                  (id, public_id, public_tag, display_name, credential_hash, credential_expires_at,
                   excluded_from_leaderboard, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?, 0)
                """, player, publicId, "JH-" + publicId.toString().substring(0, 9), name,
                CompetitionCredentialService.hash(publicId.toString()),
                java.sql.Timestamp.from(achieved.plusSeconds(86_400)), java.sql.Timestamp.from(achieved), java.sql.Timestamp.from(achieved));
        insertRun(player, score, achieved);
        return player;
    }

    private void insertRun(UUID player, int score, Instant achieved) {
        jdbc.update("""
                INSERT INTO competition_run
                  (id, player_id, start_request_id, competition_date, attempt_number, status, score,
                   score_reached_at, started_at, expires_at, ended_at, rules_version, version)
                VALUES (?, ?, ?, DATE '2026-09-10',
                  (SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM competition_run WHERE player_id = ? AND competition_date = DATE '2026-09-10'),
                  'LOST', ?, ?, ?, ?, ?, 1, 0)
                """, UUID.randomUUID(), player, UUID.randomUUID(), player, score,
                java.sql.Timestamp.from(achieved), java.sql.Timestamp.from(achieved.minusSeconds(60)),
                java.sql.Timestamp.from(Instant.parse("2026-09-10T21:00:00Z")), java.sql.Timestamp.from(achieved));
    }

    private RoundOutcome finishRound(Session session, UUID runId, int number, boolean win) throws Exception {
        JsonNode created = json(createRound(session, runId, UUID.randomUUID(), number).andExpect(status().isCreated()).andReturn());
        UUID gameId = UUID.fromString(created.get("gameId").stringValue());
        Cookie visitor = lastVisitorCookie;
        int key = jdbc.queryForObject("SELECT key_box FROM game_round WHERE id = ?", Integer.class, gameId);
        int box = win ? key : key % 3 + 1;
        choose(session, visitor, gameId, box).andExpect(status().isOk());
        JsonNode result = json(decide(session, visitor, gameId, "STAY").andExpect(status().isOk()).andReturn());
        return new RoundOutcome(new Session(session.competitionCookie(), visitor), result);
    }

    private Session profile(String name) throws Exception { return profile(name, status().isOk()); }
    private Session profile(String name, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        MvcResult result = mvc.perform(put("/api/v1/competition/profile").header(CSRF, "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"" + name + "\"}"))
                .andExpect(expected).andReturn();
        Cookie cookie = result.getResponse().getCookie(COMP_COOKIE);
        if (cookie != null) {
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax", "Path=/");
        }
        return new Session(cookie, null);
    }

    private org.springframework.test.web.servlet.ResultActions start(Session session, UUID key) throws Exception {
        return mvc.perform(post("/api/v1/competition/runs").cookie(session.competitionCookie())
                .header(CSRF, "1").header("Idempotency-Key", key));
    }

    private org.springframework.test.web.servlet.ResultActions createRound(Session session, UUID run, UUID key, int number) throws Exception {
        var request = post("/api/v1/competition/runs/{id}/rounds", run)
                .cookie(session.competitionCookie()).header(CSRF, "1").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRoundNumber\":" + number + "}");
        if (session.visitorCookie() != null) request.cookie(session.visitorCookie());
        MvcResult result = mvc.perform(request)
                .andReturn();
        Cookie visitor = result.getResponse().getCookie("joyhub_visitor");
        if (visitor != null) lastVisitorCookie = visitor;
        return new FixedResultActions(result);
    }

    private org.springframework.test.web.servlet.ResultActions choose(Session s, Cookie visitor, UUID game, int box) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/choice", game).cookie(s.competitionCookie(), visitor)
                .header(CSRF, "1").contentType(MediaType.APPLICATION_JSON).content("{\"box\":" + box + "}"));
    }
    private org.springframework.test.web.servlet.ResultActions decide(Session s, Cookie visitor, UUID game, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/decision", game).cookie(s.competitionCookie(), visitor)
                .header(CSRF, "1").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"" + strategy + "\"}"));
    }
    private org.springframework.test.web.servlet.ResultActions abandon(Session session, UUID run) throws Exception {
        return mvc.perform(post("/api/v1/competition/runs/{id}/abandon", run)
                .cookie(session.competitionCookie()).header(CSRF, "1"));
    }

    private int runScore(UUID id) { return jdbc.queryForObject("SELECT score FROM competition_run WHERE id = ?", Integer.class, id); }
    private UUID runId(MvcResult result) throws Exception { return UUID.fromString(json(result).get("run").get("runId").stringValue()); }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private static Cookie createdCookie(String name, String value) { return new Cookie(name, value == null ? "missing" : value); }

    private record Session(Cookie competitionCookie, Cookie visitorCookie) {}
    private record RoundOutcome(Session session, JsonNode result) {}

    /** Adapts an already executed MockMvc result so helpers can keep ResultActions assertions. */
    private static final class FixedResultActions implements org.springframework.test.web.servlet.ResultActions {
        private final MvcResult result;
        private FixedResultActions(MvcResult result) { this.result = result; }
        @Override public org.springframework.test.web.servlet.ResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception { matcher.match(result); return this; }
        @Override public org.springframework.test.web.servlet.ResultActions andDo(org.springframework.test.web.servlet.ResultHandler handler) throws Exception { handler.handle(result); return this; }
        @Override public MvcResult andReturn() { return result; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-09-10T12:00:00Z");
        void set(Instant value) { instant = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
