package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.visit.service.VisitAlreadyRecordedException;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@Transactional
class VisitServiceTests extends VisitTestSupport {
    private void noAward(Setup setup) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?", Long.class, setup.customer().getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id=?", Long.class, setup.account().getId())).isZero();
    }

    @Test
    void trustedConfirmationCreatesVisitAndMatchingEarnWithCurrentStaff() {
        var setup = fixture(); authenticate(setup.staff(), RoleCode.STAFF);
        var result = service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        var visit = visits.findById(result.visitId()).orElseThrow();
        assertThat(visit.getConfirmedBy().getId()).isEqualTo(setup.staff().getId());
        assertThat(visit.getAccount().getId()).isEqualTo(setup.account().getId());
        assertThat(result.confirmedAt()).isEqualTo(NOW);
        assertThat(result.earnedPoints()).isEqualTo(100);
        assertThat(result.balance()).isEqualTo(100);
        assertThat(loyalty.currentBalance(setup.customer().getId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id = ?", Long.class, setup.account().getId())).isEqualTo(1);
    }

    @Test
    void serviceDuplicateAcrossStoresHasStableSafeOutcome() {
        var setup = fixture(); var another = assignedStore(setup.staff(), StoreStatus.ACTIVE); authenticate(setup.staff(), RoleCode.STAFF);
        service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), another.getId()))
                .isInstanceOf(VisitAlreadyRecordedException.class);
    }

    @Test
    void namedPostgresDuplicateIsTranslatedToVisitAlreadyRecorded() {
        var setup = fixture(); append(setup, NOW); authenticate(setup.staff(), RoleCode.STAFF);
        doReturn(false).when(visits).existsByCustomerIdAndBusinessDate(setup.customer().getId(), LocalDate.of(2026, 10, 12));
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()))
                .isInstanceOf(VisitAlreadyRecordedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "ADMIN"})
    void nonStaffIncludingAdminOnlyCannotConfirmEvenIfAssigned(String role) {
        var setup = fixture(); var actor = user(RoleCode.valueOf(role)); var store = assignedStore(actor, StoreStatus.ACTIVE);
        authenticate(actor, RoleCode.valueOf(role));
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), store.getId())).isInstanceOf(AccessDeniedException.class);
        noAward(setup);
    }

    @Test
    void staleStaffAuthorityDoesNotReplaceDatabaseStaffGrant() {
        var setup = fixture(); entities.flush();
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ? AND role_code = 'STAFF'", setup.staff().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(AccessDeniedException.class);
        noAward(setup);
    }

    @Test
    void disabledStaffCannotUseOldAuthenticatedContext() {
        var setup = fixture(); entities.flush(); jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?", setup.staff().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(BadCredentialsException.class);
        noAward(setup);
    }

    @Test
    void disabledCustomerCannotQualify() {
        var setup = fixture(); entities.flush(); jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?", setup.customer().getUser().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void missingCustomerGrantCannotQualifyThroughRetainedProfile() {
        var setup = fixture(); entities.flush(); jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?", setup.customer().getUser().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void inactiveStoreCannotQualify() {
        var setup = fixture(); var inactive = assignedStore(setup.staff(), StoreStatus.INACTIVE); authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), inactive.getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void inactiveAssignmentCannotQualify() {
        var setup = fixture(); entities.flush(); jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE store_id=?", setup.store().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void unassignedStaffCannotBorrowAnotherStaffScope() {
        var setup = fixture(); var other = user(RoleCode.STAFF); authenticate(other, RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void missingLoyaltyAccountCannotQualify() {
        var setup = fixture(); entities.flush(); jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE id=?", setup.account().getId()); entities.clear();
        authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(ResourceNotFoundException.class);
        noAward(setup);
    }

    @Test
    void selfConfirmationIsDeniedEvenWithCustomerAndStaffGrants() {
        var setup = fixture(); var actor = setup.customer().getUser();
        entities.flush();
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?, 'STAFF')", actor.getId());
        var store = assignedStore(actor, StoreStatus.ACTIVE); entities.flush(); entities.clear();
        authenticate(actor, RoleCode.CUSTOMER, RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), store.getId())).isInstanceOf(AccessDeniedException.class);
        noAward(setup);
    }

    @ParameterizedTest
    @CsvSource({"2026-07-01T22:59:59.999999999Z,2026-07-01", "2026-07-01T23:00:00Z,2026-07-02",
            "2026-01-01T23:59:59.999999999Z,2026-01-01", "2026-01-02T00:00:00Z,2026-01-02",
            "2026-03-29T00:59:59Z,2026-03-29", "2026-03-29T01:00:00Z,2026-03-29",
            "2026-10-25T00:30:00Z,2026-10-25", "2026-10-25T01:30:00Z,2026-10-25"})
    void serverInstantDerivesLondonDateAcrossMidnightAndDst(String instant, String expectedDate) {
        var setup = fixture(); authenticate(setup.staff(), RoleCode.STAFF); at(Instant.parse(instant));
        doReturn(ZoneId.of("Pacific/Auckland")).when(clock).getZone();
        var result = service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        assertThat(result.businessDate()).isEqualTo(LocalDate.parse(expectedDate));
        assertThat(result.confirmedAt()).isEqualTo(Instant.parse(instant).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void londonMidnightAllowsNewBusinessDayWithoutWaitingForUtcMidnight() {
        var setup = fixture(); authenticate(setup.staff(), RoleCode.STAFF);
        at(Instant.parse("2026-07-01T22:59:59Z")); var first = service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        at(Instant.parse("2026-07-01T23:00:00Z")); var second = service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        assertThat(second.businessDate()).isEqualTo(first.businessDate().plusDays(1));
    }

    @Test
    void repeatedDstHourDoesNotCreateASecondQualifyingDate() {
        var setup = fixture(); authenticate(setup.staff(), RoleCode.STAFF);
        at(Instant.parse("2026-10-25T00:30:00Z")); service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
        at(Instant.parse("2026-10-25T01:30:00Z"));
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId())).isInstanceOf(VisitAlreadyRecordedException.class);
    }
}
