package ru.joyhub.montyhall.application;

import java.util.UUID;

public class GameNotFoundException extends RuntimeException {
    public GameNotFoundException(UUID gameId) {
        super("Game %s was not found".formatted(gameId));
    }
}

