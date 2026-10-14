package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.qr.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class QrAbuseProtectionTests extends StaffQrTestSupport {
    QrScanEvent rejection(org.springframework.test.web.servlet.MvcResult response) {
        var events=history.byRequestId(UUID.fromString(response.getResponse().getHeader("X-Request-ID")),NOW.minusSeconds(1),NOW.plusSeconds(300),10);
        assertThat(events).hasSize(1);assertThat(events.getFirst().outcome()).isEqualTo(ScanEventOutcome.REJECTED);return events.getFirst();
    }
    @Test void rapidFailedScansEnforceExistingStaffThresholdAndResetWithoutAwarding() throws Exception {
        var setup=setup();
        for(int i=0;i<60;i++){assertThat(code(scanHttp(setup.staff(),"invalid-qr",setup.store().getId(),409))).isEqualTo("QR_INVALID");}
        var limited=scanHttp(setup.staff(),"invalid-qr",setup.store().getId(),429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        var event=rejection(limited);assertThat(event.reasonCode()).isEqualTo("RATE_LIMITED");assertThat(event.staffUserId()).isEqualTo(setup.staff().userId);
        assertThat(event.storeId()).isEqualTo(setup.store().getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));assertThat(code(scanHttp(setup.staff(),"invalid-qr",setup.store().getId(),409))).isEqualTo("QR_INVALID");
        unchanged(customer(setup.customer()));assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.scan_resolutions WHERE staff_user_id=?",Long.class,setup.staff().userId)).isZero();
    }
    @Test void socketIpScanRejectionIsRecordedAndForwardedHeaderCannotBypassIt() throws Exception {
        var setup=setup();String socket="198.51.100.44";setup.staff().ip=socket;
        for(int i=0;i<240;i++){scanLimiter.reserveAddress(socket);}
        var response=setup.staff().send(post("/api/v1/staff/scans").header("X-CSRF-TOKEN",setup.staff().csrf)
                .header("X-Forwarded-For","203.0.113.5").contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",setup.token(),"storeId",setup.store().getId()))),429);
        assertThat(code(response)).isEqualTo("RATE_LIMITED");assertThat(response.getResponse().getHeader("Retry-After")).isEqualTo("60");
        var event=rejection(response);assertThat(event.staffUserId()).isEqualTo(setup.staff().userId);assertThat(event.storeId()).isEqualTo(setup.store().getId());
        assertThat(event.reasonCode()).isEqualTo("RATE_LIMITED");unchanged(customer(setup.customer()));
    }
    @Test void rapidConfirmationFailuresAreTemporarilyLimitedAndResetUsingServerClock() throws Exception {
        var setup=setup();var resolution=scan(setup);
        for(int i=0;i<60;i++){assertThat(code(confirmHttp(setup.staff(),UUID.randomUUID(),UUID.randomUUID(),404))).isEqualTo("RESOURCE_NOT_FOUND");}
        var blocked=confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),429);
        assertThat(code(blocked)).isEqualTo("RATE_LIMITED");assertThat(blocked.getResponse().getHeader("Retry-After")).isEqualTo("60");
        var event=rejection(blocked);assertThat(event.staffUserId()).isEqualTo(setup.staff().userId);assertThat(event.resolutionId()).isEqualTo(resolution.scanResolutionId());
        unchanged(customer(setup.customer()));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);awardedOnce(setup);
    }
    @Test void confirmationIpBudgetRecordsRejectionsAndReleasesSuccessfulRetries() throws Exception {
        var setup=setup();var resolution=scan(setup);String address=setup.staff().ip;
        // Reserve distinct actors to simulate failures sharing one socket address without exhausting this staff's budget.
        for(int i=0;i<240;i++){confirmationLimiter.reserve(UUID.randomUUID(),address);}
        var blocked=confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),429);
        assertThat(rejection(blocked).reasonCode()).isEqualTo("RATE_LIMITED");assertThat(blocked.getResponse().getHeader("Retry-After")).isEqualTo("60");
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));var key=UUID.randomUUID();
        String original=confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201).getResponse().getContentAsString();
        for(int i=0;i<65;i++){assertThat(confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201).getResponse().getContentAsString()).isEqualTo(original);}
        awardedOnce(setup);
        var recorded=history.byStaff(setup.staff().userId,NOW.minusSeconds(1),NOW.plusSeconds(120),100);
        assertThat(recorded).extracting(QrScanEvent::outcome).containsExactlyInAnyOrder(ScanEventOutcome.RESOLUTION_CREATED,ScanEventOutcome.REJECTED,ScanEventOutcome.VISIT_CONFIRMED);
    }
    @Test void scanHistoryHasNoAdminOrStaffBrowsingHttpEndpoint() throws Exception {
        var staff=login(RoleCode.STAFF);var admin=login(RoleCode.ADMIN);
        staff.send(get("/api/v1/staff/scan-events"),404);admin.send(get("/api/v1/admin/scan-events"),404);
    }
}
