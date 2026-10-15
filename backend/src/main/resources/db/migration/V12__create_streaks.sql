-- Streak is a maintained visit projection; immutable visits remain source evidence.
CREATE TABLE loyalty.streaks (
    customer_id UUID PRIMARY KEY REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    current_count INTEGER NOT NULL DEFAULT 0 CHECK (current_count >= 0),
    longest_count INTEGER NOT NULL DEFAULT 0 CHECK (longest_count >= current_count),
    last_qualifying_date DATE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_streak_state CHECK (
        (last_qualifying_date IS NULL AND current_count = 0 AND longest_count = 0)
        OR (last_qualifying_date IS NOT NULL AND isfinite(last_qualifying_date) AND current_count > 0))
);

-- Date islands compute both the final run and the longest run, independent of INSERT order
-- and migration day. Do not expire current_count here: display expiry never rewrites history.
WITH dated AS (
    SELECT customer_id,business_date,
        LAG(business_date) OVER (PARTITION BY customer_id ORDER BY business_date) AS previous_date
    FROM loyalty.visits
), islands AS (
    SELECT customer_id,business_date,
        SUM(CASE WHEN business_date - previous_date = 1 THEN 0 ELSE 1 END)
            OVER (PARTITION BY customer_id ORDER BY business_date) AS island
    FROM dated
), runs AS (
    SELECT customer_id,MAX(business_date) AS last_date,COUNT(*)::INTEGER AS run_count
    FROM islands GROUP BY customer_id,island
), ranked AS (
    SELECT customer_id,last_date,run_count,
        MAX(run_count) OVER (PARTITION BY customer_id) AS longest_count,
        row_number() OVER (PARTITION BY customer_id ORDER BY last_date DESC) AS latest
    FROM runs
)
INSERT INTO loyalty.streaks(customer_id,current_count,longest_count,last_qualifying_date,version)
SELECT customer.id,COALESCE(ranked.run_count,0),COALESCE(ranked.longest_count,0),ranked.last_date,0
FROM loyalty.customer_profiles customer
LEFT JOIN ranked ON ranked.customer_id=customer.id AND ranked.latest=1;
