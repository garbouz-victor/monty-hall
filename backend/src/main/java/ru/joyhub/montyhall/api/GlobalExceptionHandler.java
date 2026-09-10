package ru.joyhub.montyhall.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.joyhub.montyhall.application.GameNotFoundException;
import ru.joyhub.montyhall.application.InvalidGameTransitionException;
import ru.joyhub.montyhall.application.TooManyOpenGamesException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GameNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(GameNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Партия не найдена", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidGameTransitionException.class)
    ResponseEntity<ProblemDetail> conflict(InvalidGameTransitionException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "INVALID_GAME_STATE", "Действие недоступно", exception.getMessage(), request);
    }

    @ExceptionHandler(TooManyOpenGamesException.class)
    ResponseEntity<ProblemDetail> tooManyOpenGames(TooManyOpenGamesException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "TOO_MANY_OPEN_GAMES",
                "Слишком много незавершённых партий",
                "Завершите одну из начатых партий или попробуйте позже.",
                request
        );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ProblemDetail> badRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Некорректный запрос",
                "Проверьте переданные значения.",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled_request_error path={}", request.getRequestURI(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Внутренняя ошибка",
                "Попробуйте ещё раз немного позже.",
                request
        );
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://joy-hub.ru/problems/" + code.toLowerCase()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
