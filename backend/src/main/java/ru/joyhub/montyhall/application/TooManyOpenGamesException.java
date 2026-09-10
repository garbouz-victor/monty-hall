package ru.joyhub.montyhall.application;

public class TooManyOpenGamesException extends RuntimeException {
    public TooManyOpenGamesException() {
        super("Too many unfinished games");
    }
}

