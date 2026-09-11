package ru.joyhub.montyhall.application;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("The creation request belongs to another visitor");
    }
}
