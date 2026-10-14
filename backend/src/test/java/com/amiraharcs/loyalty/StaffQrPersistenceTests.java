package com.amiraharcs.loyalty;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class StaffQrPersistenceTests extends StaffQrTestSupport {
    @ParameterizedTest @ValueSource(strings={"customer","staff","store","token","created","expires","id","consumedEarly","consumedLate"})
    void resolutionBindingsAndConsumptionDeadlineAreGuardedByPostgres(String invalid) throws Exception {
        var setup=setup();var resolution=scan(setup);
        String mutation=switch(invalid){
            case "customer"->"customer_id='"+UUID.randomUUID()+"'";
            case "staff"->"staff_user_id='"+UUID.randomUUID()+"'";
            case "store"->"store_id='"+UUID.randomUUID()+"'";
            case "token"->"token_jti='"+nonce()+"'";
            case "created"->"created_at=created_at+interval '1 second'";
            case "expires"->"expires_at=expires_at+interval '1 second'";
            case "id"->"id='"+UUID.randomUUID()+"'";
            case "consumedEarly"->"consumed_at=created_at-interval '1 second'";
            default->"consumed_at=expires_at";
        };
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.scan_resolutions SET "+mutation+" WHERE id=?",resolution.scanResolutionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        unchanged(customer(setup.customer()));
    }
    @Test void consumedResolutionCannotBeConsumedAgainEvenWithSameTimestamp() throws Exception {
        var setup=setup();var resolution=scan(setup);confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.scan_resolutions SET consumed_at=consumed_at WHERE id=?",resolution.scanResolutionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("Invalid scan resolution transition");awardedOnce(setup);
    }
    @ParameterizedTest @ValueSource(strings={"customerMismatch","missingToken","missingStaff","missingStore","wrongExpiry","duplicateId"})
    void resolutionForeignKeysAndLifetimeRejectInvalidInserts(String invalid) throws Exception {
        var setup=setup();var resolution=scan(setup);
        UUID customer=invalid.equals("customerMismatch")?customer(login(com.amiraharcs.loyalty.auth.model.RoleCode.CUSTOMER)):customer(setup.customer());
        String token=invalid.equals("missingToken")?nonce():jti(setup.token());
        UUID staff=invalid.equals("missingStaff")?UUID.randomUUID():setup.staff().userId;
        UUID shop=invalid.equals("missingStore")?UUID.randomUUID():setup.store().getId();
        UUID id=invalid.equals("duplicateId")?resolution.scanResolutionId():UUID.randomUUID();
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.scan_resolutions(id,token_jti,customer_id,staff_user_id,store_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                id,token,customer,staff,shop,java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW.plusSeconds(invalid.equals("wrongExpiry")?121:120))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void resolutionReferencesAreRestrictiveAndScanEventsAppendOnly() throws Exception {
        var setup=setup();scan(setup);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.qr_identity_tokens WHERE jti=?",jti(setup.token())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String sql:List.of("UPDATE loyalty.qr_scan_events SET id=id","DELETE FROM loyalty.qr_scan_events WHERE false","TRUNCATE loyalty.qr_scan_events")){
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
