package ru.joyhub.montyhall.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import ru.joyhub.montyhall.domain.GameState;
import ru.joyhub.montyhall.domain.Strategy;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_round")
public class GameRoundEntity {

    @Id
    private UUID id;

    @Column(name = "creation_request_id", nullable = false, unique = true)
    private UUID creationRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameState state;

    @Column(name = "key_box", nullable = false)
    private int keyBox;

    @Column(nullable = false, length = 64)
    private String nonce;

    @Column(nullable = false, length = 64)
    private String commitment;

    @Column(name = "initial_choice")
    private Integer initialChoice;

    @Column(name = "opened_box")
    private Integer openedBox;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Strategy strategy;

    @Column(name = "final_choice")
    private Integer finalChoice;

    private Boolean won;

    @Column(name = "visitor_id", nullable = false)
    private UUID visitorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "choice_at")
    private Instant choiceAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected GameRoundEntity() {
    }

    private GameRoundEntity(
            UUID id,
            UUID creationRequestId,
            int keyBox,
            String nonce,
            String commitment,
            UUID visitorId,
            Instant createdAt
    ) {
        this.id = id;
        this.creationRequestId = creationRequestId;
        this.state = GameState.CREATED;
        this.keyBox = keyBox;
        this.nonce = nonce;
        this.commitment = commitment;
        this.visitorId = visitorId;
        this.createdAt = createdAt;
    }

    public static GameRoundEntity create(
            UUID id,
            UUID creationRequestId,
            int keyBox,
            String nonce,
            String commitment,
            UUID visitorId,
            Instant createdAt
    ) {
        return new GameRoundEntity(id, creationRequestId, keyBox, nonce, commitment, visitorId, createdAt);
    }

    public void recordChoice(int initialChoice, int openedBox, Instant at) {
        this.initialChoice = initialChoice;
        this.openedBox = openedBox;
        this.choiceAt = at;
        this.state = GameState.CHOICE_MADE;
    }

    public void complete(Strategy strategy, int finalChoice, boolean won, Instant at) {
        this.strategy = strategy;
        this.finalChoice = finalChoice;
        this.won = won;
        this.completedAt = at;
        this.state = GameState.COMPLETED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreationRequestId() {
        return creationRequestId;
    }

    public GameState getState() {
        return state;
    }

    public int getKeyBox() {
        return keyBox;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCommitment() {
        return commitment;
    }

    public Integer getInitialChoice() {
        return initialChoice;
    }

    public Integer getOpenedBox() {
        return openedBox;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public Integer getFinalChoice() {
        return finalChoice;
    }

    public Boolean getWon() {
        return won;
    }

    public UUID getVisitorId() {
        return visitorId;
    }
}
