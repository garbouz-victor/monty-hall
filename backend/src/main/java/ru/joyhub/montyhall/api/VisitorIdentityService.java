package ru.joyhub.montyhall.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Component
public class VisitorIdentityService {

    static final String COOKIE_NAME = "joyhub_visitor";
    private final boolean secure;

    public VisitorIdentityService(@Value("${joyhub.visitor-cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public UUID resolve(HttpServletRequest request, HttpServletResponse response) {
        Optional<UUID> existing = find(request);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID visitorId = newVisitorId();
        write(response, visitorId);
        return visitorId;
    }

    public Optional<UUID> find(HttpServletRequest request) {
        return findValidCookie(request);
    }

    public UUID newVisitorId() {
        return UUID.randomUUID();
    }

    public void write(HttpServletResponse response, UUID visitorId) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, visitorId.toString())
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(365))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static Optional<UUID> findValidCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            Optional<UUID> parsedCookie = Arrays.stream(request.getCookies())
                    .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .map(VisitorIdentityService::parseUuid)
                    .flatMap(Optional::stream)
                    .findFirst();
            if (parsedCookie.isPresent()) {
                return parsedCookie;
            }
        }

        String cookieHeader = request.getHeader(HttpHeaders.COOKIE);
        if (cookieHeader == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .filter(part -> part.startsWith(COOKIE_NAME + "="))
                .map(part -> part.substring(COOKIE_NAME.length() + 1))
                .map(VisitorIdentityService::parseUuid)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
