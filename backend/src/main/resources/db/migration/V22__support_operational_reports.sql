-- Existing history indexes lead with customer/account/store, so they do not narrow
-- global timestamp cohorts. EXPLAIN ANALYZE on representative joined fixtures
-- confirmed these four range predicates benefit from dedicated time indexes.
-- No summary tables, new domain state or historical rewrites.
CREATE INDEX ix_report_point_transactions_time ON loyalty.point_transactions(created_at);
CREATE INDEX ix_report_reward_redemptions_time ON loyalty.reward_redemptions(requested_at);
CREATE INDEX ix_report_game_sessions_time ON loyalty.game_sessions(started_at);
CREATE INDEX ix_report_game_scores_time ON loyalty.game_scores(accepted_at);
