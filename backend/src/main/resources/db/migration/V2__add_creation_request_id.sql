ALTER TABLE game_round
    ADD COLUMN creation_request_id UUID;

UPDATE game_round
SET creation_request_id = id
WHERE creation_request_id IS NULL;

ALTER TABLE game_round
    ALTER COLUMN creation_request_id SET NOT NULL;

ALTER TABLE game_round
    ADD CONSTRAINT game_round_creation_request_id_unique
        UNIQUE (creation_request_id);
