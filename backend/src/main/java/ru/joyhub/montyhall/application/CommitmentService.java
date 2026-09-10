package ru.joyhub.montyhall.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class CommitmentService {

    public String create(UUID gameId, int keyBox, String nonce) {
        String canonical = canonical(gameId, keyBox, nonce);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String canonical(UUID gameId, int keyBox, String nonce) {
        return "v1:%s:%d:%s".formatted(gameId, keyBox, nonce);
    }
}

