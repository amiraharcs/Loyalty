package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.repository.VisitRepository;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Transactional
class VisitPersistenceTests extends VisitTestSupport {
    private static final String INSERT = "INSERT INTO loyalty.visits(id,customer_id,account_id,store_id,confirmed_by,business_date,confirmed_at) VALUES (?,?,?,?,?,?,?)";
    private void integrity(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
    }
    private Object[] values(Setup setup) {
        return new Object[]{UUID.randomUUID(), setup.customer().getId(), setup.account().getId(), setup.store().getId(),
                setup.staff().getId(), LocalDate.of(2026, 10, 12), java.sql.Timestamp.from(NOW)};
    }

    @Test
    void mapsAllRelationshipsAndServerTimestampWithoutPointsOrExtraCreationTime() {
        var setup = fixture(); var visit = append(setup, NOW); entities.clear();
        var loaded = visits.findById(visit.getId()).orElseThrow();
        assertThat(loaded.getCustomer().getId()).isEqualTo(setup.customer().getId());
        assertThat(loaded.getAccount().getId()).isEqualTo(setup.account().getId());
        assertThat(loaded.getStore().getId()).isEqualTo(setup.store().getId());
        assertThat(loaded.getConfirmedBy().getId()).isEqualTo(setup.staff().getId());
        assertThat(loaded.getConfirmedAt()).isEqualTo(NOW);
        assertThat(loaded.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(visits.existsByCustomerIdAndBusinessDate(setup.customer().getId(), loaded.getBusinessDate())).isTrue();
        assertThat(visits.findByCustomerIdAndBusinessDate(setup.customer().getId(), loaded.getBusinessDate())).isPresent();
        assertThat(loyalty.currentBalance(setup.customer().getId())).isZero();
    }

    @Test
    void databaseRejectsSameCustomerDateEvenAtAnotherStore() {
        var setup = fixture(); append(setup, NOW);
        var otherStore = assignedStore(setup.staff(), StoreStatus.ACTIVE); entities.flush();
        var args = values(setup); args[3] = otherStore.getId();
        integrity(INSERT, args);
    }

    @Test
    void differentCustomersCanQualifyOnSameDate() {
        var setup = fixture(); var other = customer(); append(setup, NOW);
        var second = visits.append(new Visit(other.getCustomer(), other, setup.store(), setup.staff(), NOW));
        assertThat(second.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(second.getCustomer().getId()).isNotEqualTo(setup.customer().getId());
    }

    @Test
    void databaseAndModelRejectCrossCustomerAccountCombination() {
        var setup = fixture(); var other = customer(); entities.flush();
        assertThatThrownBy(() -> new Visit(setup.customer(), other, setup.store(), setup.staff(), NOW)).isInstanceOf(IllegalArgumentException.class);
        var args = values(setup); args[2] = other.getId(); integrity(INSERT, args);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void databaseRejectsMissingCustomerAccountStoreOrStaff(int field) {
        var setup = fixture(); entities.flush(); var args = values(setup); args[field] = UUID.randomUUID(); integrity(INSERT, args);
    }

    @Test
    void databaseRejectsClientChosenDateDifferentFromLondonConfirmationDate() {
        var setup = fixture(); entities.flush(); var args = values(setup); args[5] = LocalDate.of(2050, 1, 1); integrity(INSERT, args);
    }

    @Test
    void customerCannotBeDeletedWithRetainedAccountAndVisit() {
        var setup = fixture(); append(setup, NOW);
        integrity("DELETE FROM loyalty.customer_profiles WHERE id = ?", setup.customer().getId());
    }

    @Test
    void accountCannotBeDeletedWithVisitEvenWithoutPointHistory() {
        var setup = fixture(); append(setup, NOW);
        integrity("DELETE FROM loyalty.loyalty_accounts WHERE id = ?", setup.account().getId());
    }

    @Test
    void storeCannotBeDeletedAfterAssignmentsRemovedWhileVisitExists() {
        var setup = fixture(); append(setup, NOW);
        jdbc.update("DELETE FROM loyalty.staff_store WHERE store_id = ?", setup.store().getId());
        integrity("DELETE FROM loyalty.stores WHERE id = ?", setup.store().getId());
    }

    @Test
    void confirmingStaffCannotBeDeletedAfterGrantsRemovedWhileVisitExists() {
        var setup = fixture(); append(setup, NOW);
        jdbc.update("DELETE FROM loyalty.staff_store WHERE staff_user_id = ?", setup.staff().getId());
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ?", setup.staff().getId());
        integrity("DELETE FROM loyalty.users WHERE id = ?", setup.staff().getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE loyalty.visits SET business_date = business_date WHERE id = ?", "DELETE FROM loyalty.visits WHERE id = ?"})
    void databaseRejectsHistoricalUpdateAndDeletion(String sql) {
        integrity(sql, append(fixture(), NOW).getId());
    }

    @Test
    void rejectsTruncationIncludingCascade() { append(fixture(), NOW); integrity("TRUNCATE loyalty.visits CASCADE"); }

    @Test
    void hibernateDoesNotRewriteLoadedVisitAndRepositoryExposesNoMutation() throws Exception {
        var visit = append(fixture(), NOW);
        assertThat(Visit.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        assertThat(Visit.class.getMethods()).extracting(java.lang.reflect.Method::getName).noneMatch(name -> name.startsWith("set"));
        assertThat(VisitRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName).doesNotContain("save", "delete", "deleteAll", "deleteById");
        var time = Visit.class.getDeclaredField("confirmedAt"); time.setAccessible(true); time.set(visit, NOW.plusSeconds(100));
        entities.flush(); entities.clear();
        assertThat(visits.findById(visit.getId()).orElseThrow().getConfirmedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> visits.append(visit)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
    }

    @Test
    void historyOrdersConfirmedTimeDescendingAndSupportsSafeBoundedPages() {
        var setup = fixture(); var first = append(setup, NOW); var second = append(setup, NOW.plusSeconds(86400));
        var third = append(setup, NOW.plusSeconds(172800));
        var another = fixture(); append(another, NOW);
        var history = service.history(setup.customer().getId(), 0, 2);
        assertThat(history).extracting(item -> item.id()).containsExactly(third.getId(), second.getId());
        assertThat(history.getTotalElements()).isEqualTo(3);
        assertThat(service.history(setup.customer().getId(), 1, 2)).extracting(item -> item.id()).containsExactly(first.getId());
        assertThatThrownBy(() -> service.history(setup.customer().getId(), 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(visits.findByCustomerIdOrderByConfirmedAtDescIdDesc(setup.customer().getId(), PageRequest.of(0, 10))).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname='loyalty' AND indexname='ix_visits_customer_history'", String.class))
                .contains("customer_id, confirmed_at DESC, id DESC");
    }
}
