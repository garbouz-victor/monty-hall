package ru.joyhub.competition.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import ru.joyhub.competition.persistence.CompetitionPlayerRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public class CompetitionCredentialService {
    private static final Duration TTL = Duration.ofDays(365);
    private final CompetitionPlayerRepository players;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String cookieName;
    private final boolean secure;

    public CompetitionCredentialService(
            CompetitionPlayerRepository players,
            Clock clock,
            @Value("${joyhub.competition.cookie.name:__Host-joyhub_competition}") String cookieName,
            @Value("${joyhub.competition.cookie.secure:true}") boolean secure
    ) {
        this.players = players;
        this.clock = clock;
        this.cookieName = cookieName;
        this.secure = secure;
    }

    public Optional<UUID> findPlayerId(HttpServletRequest request) {
        return rawCookie(request)
                .map(CompetitionCredentialService::hash)
                .flatMap(value -> players.findByCredentialHashAndCredentialExpiresAtAfter(value, clock.instant()))
                .map(player -> player.getId());
    }

    public UUID requirePlayerId(HttpServletRequest request) {
        return findPlayerId(request).orElseThrow(ru.joyhub.competition.application.CompetitionException::unauthorized);
    }

    public IssuedCredential issue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedCredential(raw, hash(raw), clock.instant().plus(TTL));
    }

    public void write(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, rawToken)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(TTL).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Optional<String> rawCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            Optional<String> found = Arrays.stream(request.getCookies())
                    .filter(cookie -> cookieName.equals(cookie.getName()))
                    .map(Cookie::getValue).filter(value -> !value.isBlank()).findFirst();
            if (found.isPresent()) return found;
        }
        String header = request.getHeader(HttpHeaders.COOKIE);
        if (header == null) return Optional.empty();
        return Arrays.stream(header.split(";"))
                .map(String::trim).filter(value -> value.startsWith(cookieName + "="))
                .map(value -> value.substring(cookieName.length() + 1)).filter(value -> !value.isBlank())
                .findFirst();
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record IssuedCredential(String rawToken, String hash, java.time.Instant expiresAt) {}
}
