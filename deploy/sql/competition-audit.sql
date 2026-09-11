-- Read-only competition integrity and product-interest report.
-- Run with a PostgreSQL role that has SELECT only.

-- Stored score must equal the uninterrupted wins before the first loss.
WITH ordered AS (
  SELECT competition_run_id, competition_round_number, won,
         BOOL_OR(NOT won) OVER (
           PARTITION BY competition_run_id ORDER BY competition_round_number
           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
         ) AS lost_before
  FROM game_round
  WHERE competition_run_id IS NOT NULL AND state = 'COMPLETED'
), reconstructed AS (
  SELECT competition_run_id,
         COUNT(*) FILTER (WHERE won AND COALESCE(lost_before, FALSE) = FALSE) AS expected_score,
         COUNT(*) FILTER (WHERE COALESCE(lost_before, FALSE)) AS rounds_after_loss
  FROM ordered GROUP BY competition_run_id
)
SELECT r.id AS run_id, r.score, COALESCE(a.expected_score, 0) AS expected_score,
       COALESCE(a.rounds_after_loss, 0) AS rounds_after_loss
FROM competition_run r LEFT JOIN reconstructed a ON a.competition_run_id = r.id
WHERE r.score <> COALESCE(a.expected_score, 0) OR COALESCE(a.rounds_after_loss, 0) > 0;

-- Missing or duplicated logical round numbers (expected result: zero rows).
SELECT competition_run_id, COUNT(*) AS rounds, MAX(competition_round_number) AS max_round_number
FROM game_round WHERE competition_run_id IS NOT NULL
GROUP BY competition_run_id
HAVING COUNT(*) <> MAX(competition_round_number);

-- Daily product-interest cohorts. These are browser profiles, not verified people.
WITH activity AS (
  SELECT player_id, MIN(competition_date) AS first_day,
         COUNT(*) AS attempts, COUNT(DISTINCT competition_date) AS active_days
  FROM competition_run GROUP BY player_id
)
SELECT
  COUNT(*) AS profiles_that_started,
  COUNT(*) FILTER (WHERE attempts >= 2) AS profiles_with_repeat_attempt,
  COUNT(*) FILTER (WHERE active_days >= 2) AS profiles_returned_on_later_day
FROM activity;

-- Attempt distribution by Moscow competition date.
SELECT competition_date, COUNT(DISTINCT player_id) AS started_profiles,
       COUNT(*) AS attempts, COUNT(*) FILTER (WHERE score >= 1) AS attempts_with_win,
       MAX(score) AS best_streak
FROM competition_run
GROUP BY competition_date
ORDER BY competition_date DESC;

-- Recovery frequency is intentionally measured from structured logs, not inferred
-- from HTTP request counts. Count events ending in `_replayed`; they are replay
-- signals, not a count of people or definitive proof of a network failure.
