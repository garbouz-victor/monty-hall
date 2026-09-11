package ru.joyhub.competition.application;

public class CompetitionException extends RuntimeException {
    private final String code;
    private final int status;

    public CompetitionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public int status() { return status; }

    public static CompetitionException unauthorized() {
        return new CompetitionException(401, "COMPETITION_SESSION_REQUIRED", "Сессия участника не найдена");
    }

    public static CompetitionException notFound() {
        return new CompetitionException(404, "COMPETITION_NOT_FOUND", "Попытка не найдена");
    }

    public static CompetitionException conflict(String message) {
        return new CompetitionException(409, "COMPETITION_STATE_CONFLICT", message);
    }

    public static CompetitionException quota() {
        return new CompetitionException(429, "COMPETITION_DAILY_LIMIT", "Все попытки на сегодня использованы");
    }

    public static CompetitionException expired() {
        return new CompetitionException(409, "COMPETITION_RUN_EXPIRED", "Срок попытки истёк");
    }
}
