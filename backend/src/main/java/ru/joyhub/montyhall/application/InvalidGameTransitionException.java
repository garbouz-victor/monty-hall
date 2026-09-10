package ru.joyhub.montyhall.application;

public class InvalidGameTransitionException extends RuntimeException {
    public InvalidGameTransitionException(String message) {
        super(message);
    }
}

