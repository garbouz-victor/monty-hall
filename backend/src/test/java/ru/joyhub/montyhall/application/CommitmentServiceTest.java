package ru.joyhub.montyhall.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentServiceTest {

    private final CommitmentService service = new CommitmentService();

    @Test
    void hashesTheDocumentedCanonicalUtf8String() {
        UUID gameId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        assertThat(service.canonical(gameId, 2, "0011aaff"))
                .isEqualTo("v1:123e4567-e89b-12d3-a456-426614174000:2:0011aaff");
        assertThat(service.create(gameId, 2, "0011aaff"))
                .isEqualTo("f4709a20f8efb43861af79dfdff4465a07968bcbffaa2159f370460f79ba7d02");
    }
}

