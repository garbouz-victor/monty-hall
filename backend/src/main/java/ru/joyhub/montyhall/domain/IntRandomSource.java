package ru.joyhub.montyhall.domain;

@FunctionalInterface
public interface IntRandomSource {
    int nextInt(int bound);
}

