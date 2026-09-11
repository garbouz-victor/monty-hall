package ru.joyhub.competition.application;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class CompetitionNameValidator {
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N} _-]+$");

    public String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("displayName is required");
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        normalized = SPACES.matcher(normalized).replaceAll(" ");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2 || length > 20 || !ALLOWED.matcher(normalized).matches()) {
            throw new IllegalArgumentException("displayName must contain 2-20 safe characters");
        }
        return normalized;
    }
}
