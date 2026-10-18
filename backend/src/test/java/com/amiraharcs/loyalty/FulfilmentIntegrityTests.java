package com.amiraharcs.loyalty;

import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class FulfilmentIntegrityTests extends FulfilmentTestSupport {
    @ParameterizedTest @ValueSource(strings={"missingActor","missingTime","unknownActor","tooEarly","atExpiry","actorWhilePending"})
    void databaseRequiresConsistentValidFulfilmentEvidence(String scenario) throws Exception {
        var pending=pending();Object actor=pending.staff().userId;Object at=Timestamp.from(NOW.plusSeconds(1));String status="FULFILLED";
        switch(scenario) {
            case "missingActor" -> actor=null;
            case "missingTime" -> at=null;
            case "unknownActor" -> actor=UUID.randomUUID();
            case "tooEarly" -> at=Timestamp.from(NOW.minusSeconds(1));
            case "atExpiry" -> at=Timestamp.from(NOW.plusSeconds(86400));
            case "actorWhilePending" -> status="PENDING";
        }
        Object[] args={status,actor,at,pending.id()};
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.reward_redemptions SET status=?,fulfilled_by=?,fulfilled_at=? WHERE id=?",args)).isInstanceOf(DataIntegrityViolationException.class);
        stillPending(pending);
    }
    @ParameterizedTest @ValueSource(strings={"FULFILLED","CANCELLED","EXPIRED"})
    void terminalStatesCannotBeReversedAndFulfilmentEvidenceCannotBeRewritten(String status) throws Exception {
        var pending=pending();
        if(status.equals("FULFILLED")) { fulfil(pending.staff(),pending.id(),UUID.randomUUID(),200); }
        else { jdbc.update("UPDATE loyalty.reward_redemptions SET status=? WHERE id=?",status,pending.id()); }
        var before=state(pending.id());
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.reward_redemptions SET status='PENDING',fulfilled_by=NULL,fulfilled_at=NULL WHERE id=?",pending.id())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("Terminal redemption");
        if(status.equals("FULFILLED")) {
            assertThatThrownBy(()->jdbc.update("UPDATE loyalty.reward_redemptions SET fulfilled_at=fulfilled_at+interval '1 second' WHERE id=?",pending.id())).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE loyalty.reward_redemptions SET fulfilled_by=? WHERE id=?",pending.setup().browser().userId,pending.id())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(state(pending.id())).isEqualTo(before);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.reward_redemptions WHERE id=?",pending.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void fulfilledStaffReferenceIsRestrictiveAndSnapshotsStayImmutable() throws Exception {
        var pending=pending();fulfil(pending.staff(),pending.id(),UUID.randomUUID(),200);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.users WHERE id=?",pending.staff().userId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.reward_redemptions SET reward_name_snapshot='Changed' WHERE id=?",pending.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='loyalty.reward_redemptions'::regclass AND contype='f' AND confdeltype='r' AND confupdtype='r'",Long.class)).isEqualTo(5);
    }
}
