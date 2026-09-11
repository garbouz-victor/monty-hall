package ru.joyhub.montyhall.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic source enabled only by the disposable full-stack-test Spring profile. */
@Component
@Profile("full-stack-test")
public class FullStackTestGameRandomSource implements GameRandomSource {
    private final int[] values;
    private final AtomicInteger position = new AtomicInteger();
    private final AtomicInteger nonce = new AtomicInteger();

    public FullStackTestGameRandomSource(@Value("${joyhub.test.random-sequence:0,0,0,0,1}") String sequence) {
        this.values = java.util.Arrays.stream(sequence.split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (values.length == 0) throw new IllegalArgumentException("Test random sequence is empty");
    }

    @Override
    public int nextInt(int bound) {
        if (bound == 2) return 0;
        int value = values[Math.floorMod(position.getAndIncrement(), values.length)];
        return Math.floorMod(value, bound);
    }

    @Override
    public String newNonce() {
        byte[] bytes = new byte[32];
        java.nio.ByteBuffer.wrap(bytes, 28, 4).putInt(nonce.incrementAndGet());
        return HexFormat.of().formatHex(bytes);
    }
}
