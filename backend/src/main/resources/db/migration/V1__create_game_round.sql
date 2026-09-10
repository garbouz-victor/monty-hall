CREATE TABLE game_round (
    id UUID PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    key_box INTEGER NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    commitment VARCHAR(64) NOT NULL,
    initial_choice INTEGER,
    opened_box INTEGER,
    strategy VARCHAR(10),
    final_choice INTEGER,
    won BOOLEAN,
    visitor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    choice_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT game_round_state_check CHECK (state IN ('CREATED', 'CHOICE_MADE', 'COMPLETED')),
    CONSTRAINT game_round_key_box_check CHECK (key_box BETWEEN 1 AND 3),
    CONSTRAINT game_round_initial_choice_check CHECK (initial_choice IS NULL OR initial_choice BETWEEN 1 AND 3),
    CONSTRAINT game_round_opened_box_check CHECK (opened_box IS NULL OR opened_box BETWEEN 1 AND 3),
    CONSTRAINT game_round_strategy_check CHECK (strategy IS NULL OR strategy IN ('SWITCH', 'STAY')),
    CONSTRAINT game_round_final_choice_check CHECK (final_choice IS NULL OR final_choice BETWEEN 1 AND 3),
    CONSTRAINT game_round_commitment_unique UNIQUE (commitment),
    CONSTRAINT game_round_nonce_format CHECK (nonce ~ '^[0-9a-f]{64}$'),
    CONSTRAINT game_round_commitment_format CHECK (commitment ~ '^[0-9a-f]{64}$'),
    CONSTRAINT game_round_choice_consistency CHECK (
        (state = 'CREATED'
            AND initial_choice IS NULL AND opened_box IS NULL AND choice_at IS NULL)
        OR (state IN ('CHOICE_MADE', 'COMPLETED')
            AND initial_choice IS NOT NULL AND opened_box IS NOT NULL AND choice_at IS NOT NULL)
    ),
    CONSTRAINT game_round_completion_consistency CHECK (
        (state IN ('CREATED', 'CHOICE_MADE')
            AND strategy IS NULL AND final_choice IS NULL AND won IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED'
            AND strategy IS NOT NULL AND final_choice IS NOT NULL
            AND won IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT game_round_host_move_check CHECK (
        opened_box IS NULL OR (opened_box <> key_box AND opened_box <> initial_choice)
    ),
    CONSTRAINT game_round_final_choice_check_opened CHECK (
        final_choice IS NULL OR final_choice <> opened_box
    ),
    CONSTRAINT game_round_strategy_result_check CHECK (
        state <> 'COMPLETED'
        OR (strategy = 'STAY' AND final_choice = initial_choice)
        OR (strategy = 'SWITCH' AND final_choice <> initial_choice AND final_choice <> opened_box)
    ),
    CONSTRAINT game_round_won_check CHECK (
        won IS NULL OR won = (final_choice = key_box)
    )
);

CREATE INDEX idx_game_round_completed_at
    ON game_round (completed_at DESC)
    WHERE state = 'COMPLETED';

CREATE INDEX idx_game_round_strategy_won
    ON game_round (strategy, won)
    WHERE state = 'COMPLETED';

CREATE INDEX idx_game_round_visitor_open
    ON game_round (visitor_id, created_at DESC)
    WHERE state <> 'COMPLETED';
