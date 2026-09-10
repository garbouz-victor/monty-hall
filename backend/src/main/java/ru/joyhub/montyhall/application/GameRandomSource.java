package ru.joyhub.montyhall.application;

import ru.joyhub.montyhall.domain.IntRandomSource;

public interface GameRandomSource extends IntRandomSource {
    String newNonce();
}

