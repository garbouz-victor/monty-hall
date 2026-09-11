package ru.joyhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class SameOriginMutationFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (SAFE.contains(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/competition/")
                || (path.startsWith("/api/v1/games/")
                    && (path.endsWith("/choice") || path.endsWith("/decision"))));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String csrf = request.getHeader("X-JoyHub-CSRF");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (!"1".equals(csrf) || "cross-site".equalsIgnoreCase(fetchSite)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Запрос отклонён\",\"detail\":\"Проверка источника запроса не пройдена.\",\"code\":\"CSRF_REJECTED\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
