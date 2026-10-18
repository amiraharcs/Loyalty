-- V14 supplies fields/FKs and immutable source evidence. Add the missing terminal-state guard.
CREATE FUNCTION loyalty.guard_redemption_terminal_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('FULFILLED','CANCELLED','EXPIRED')
        AND ROW(NEW.status,NEW.fulfilled_by,NEW.fulfilled_at)
            IS DISTINCT FROM ROW(OLD.status,OLD.fulfilled_by,OLD.fulfilled_at) THEN
        RAISE EXCEPTION 'Terminal redemption state and fulfilment evidence are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_redemptions_terminal';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER redemption_terminal_guard BEFORE UPDATE ON loyalty.reward_redemptions
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_redemption_terminal_state();
