CREATE TABLE competition_player (
    id UUID PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    public_tag VARCHAR(12) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    credential_hash VARCHAR(64) NOT NULL UNIQUE,
    credential_expires_at TIMESTAMPTZ NOT NULL,
    excluded_from_leaderboard BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT competition_player_credential_hash_format
        CHECK (credential_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE competition_run (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES competition_player(id) ON DELETE RESTRICT,
    start_request_id UUID NOT NULL,
    competition_date DATE NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    score_reached_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    rules_version INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT competition_run_status_check
        CHECK (status IN ('ACTIVE', 'LOST', 'ABANDONED', 'EXPIRED')),
    CONSTRAINT competition_run_score_check CHECK (score >= 0),
    CONSTRAINT competition_run_attempt_check CHECK (attempt_number >= 1),
    CONSTRAINT competition_run_rules_check CHECK (rules_version >= 1),
    CONSTRAINT competition_run_time_check CHECK (expires_at > started_at),
    CONSTRAINT competition_run_end_check CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL)
        OR (status <> 'ACTIVE' AND ended_at IS NOT NULL)
    ),
    CONSTRAINT competition_run_start_request_unique UNIQUE (player_id, start_request_id),
    CONSTRAINT competition_run_daily_attempt_unique UNIQUE (player_id, competition_date, attempt_number)
);

CREATE UNIQUE INDEX idx_competition_run_one_active
    ON competition_run (player_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_competition_run_today_score
    ON competition_run (competition_date, score DESC)
    WHERE score >= 1;

CREATE INDEX idx_competition_run_all_time_score
    ON competition_run (rules_version, score DESC)
    WHERE score >= 1;

ALTER TABLE game_round
    ADD COLUMN competition_run_id UUID REFERENCES competition_run(id) ON DELETE RESTRICT,
    ADD COLUMN competition_round_number INTEGER;

ALTER TABLE game_round
    ADD CONSTRAINT game_round_competition_link_check CHECK (
        (competition_run_id IS NULL AND competition_round_number IS NULL)
        OR (competition_run_id IS NOT NULL AND competition_round_number >= 1)
    );

CREATE UNIQUE INDEX idx_game_round_competition_number
    ON game_round (competition_run_id, competition_round_number)
    WHERE competition_run_id IS NOT NULL;

CREATE UNIQUE INDEX idx_game_round_competition_unresolved
    ON game_round (competition_run_id)
    WHERE competition_run_id IS NOT NULL AND state <> 'COMPLETED';

CREATE INDEX idx_game_round_competition_completed
    ON game_round (competition_run_id, competition_round_number, won)
    WHERE competition_run_id IS NOT NULL AND state = 'COMPLETED';
