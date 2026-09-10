package ru.joyhub.montyhall.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class SecureGameRandomSource implements GameRandomSource {

    private static final int NONCE_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public int nextInt(int bound) {
        return secureRandom.nextInt(bound);
    }

    @Override
    public String newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }
}

