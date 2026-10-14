-- Add safe workflow correlation without rewriting immutable V10 history.
ALTER TABLE loyalty.qr_scan_events
    ADD COLUMN resolution_id UUID REFERENCES loyalty.scan_resolutions(id) ON DELETE RESTRICT,
    ADD COLUMN visit_id UUID REFERENCES loyalty.visits(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_scan_event_visit_context CHECK
        (visit_id IS NULL OR (outcome = 'VISIT_CONFIRMED' AND resolution_id IS NOT NULL));
-- Existing token/time and requestId indexes remain useful. The old staff/store/time index
-- cannot serve time-ordered staff-only queries because store_id sits between those fields.
CREATE INDEX ix_scan_event_staff_time ON loyalty.qr_scan_events(staff_user_id,occurred_at DESC,id DESC);
CREATE INDEX ix_scan_event_store_time ON loyalty.qr_scan_events(store_id,occurred_at DESC,id DESC);
CREATE INDEX ix_scan_event_outcome_time ON loyalty.qr_scan_events(outcome,occurred_at DESC,id DESC);
