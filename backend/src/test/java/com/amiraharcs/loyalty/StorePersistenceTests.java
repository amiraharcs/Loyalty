package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.AccountStatus;
import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.store.model.StaffStore;
import com.amiraharcs.loyalty.store.model.StaffStoreId;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.store.repository.StaffStoreRepository;
import com.amiraharcs.loyalty.store.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class StorePersistenceTests {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    @Autowired StoreRepository stores;
    @Autowired StaffStoreRepository assignments;
    @Autowired UserRepository users;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private User identity() {
        return users.save(new User(UUID.randomUUID() + "@example.org", "synthetic-hash", AccountStatus.ACTIVE, NOW, Set.of()));
    }
    private Store store(String name, StoreStatus status) {
        return stores.save(new Store(name, "Synthetic test address", status, NOW));
    }
    private StaffStore assignment(User staff, Store store, User assigner) {
        return assignments.save(new StaffStore(staff, store, assigner, NOW));
    }

    @Test
    void mapsStoreAssignmentNaturalKeyRelationshipsAndUtcTimesWithoutGrantingRoles() {
        var staff = identity(); var assigner = identity(); var store = store("Test shop", StoreStatus.ACTIVE);
        var assignment = assignment(staff, store, assigner);
        entities.flush(); entities.clear();
        var loadedStore = stores.findById(store.getId()).orElseThrow();
        assertThat(loadedStore.getName()).isEqualTo("Test shop");
        assertThat(loadedStore.getAddress()).isEqualTo("Synthetic test address");
        assertThat(loadedStore.getStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(loadedStore.getCreatedAt()).isEqualTo(NOW);
        assertThat(loadedStore.getUpdatedAt()).isEqualTo(NOW);
        var loaded = assignments.findById(new StaffStoreId(staff.getId(), store.getId())).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(assignment.getId());
        assertThat(loaded.getStaffUser().getId()).isEqualTo(staff.getId());
        assertThat(loaded.getStore().getId()).isEqualTo(store.getId());
        assertThat(loaded.getAssignedBy().getId()).isEqualTo(assigner.getId());
        assertThat(loaded.getAssignedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.isNew()).isFalse();
        assertThat(users.findById(staff.getId()).orElseThrow().getRoles()).isEmpty();
    }

    @Test
    void uniqueStaffStorePairIsEnforcedByPostgres() {
        var staff = identity(); var assigner = identity(); var store = store("Duplicate pair", StoreStatus.ACTIVE);
        assignment(staff, store, assigner); entities.flush();
        integrity("INSERT INTO loyalty.staff_store (staff_user_id, store_id, active, assigned_by, assigned_at, updated_at) "
                + "VALUES (?, ?, true, ?, now(), now())", staff.getId(), store.getId(), assigner.getId());
    }

    @Test
    void newAssignmentCannotSilentlyMergeAndReactivateAnExistingPair() {
        var staff = identity(); var assigner = identity(); var store = store("Retained pair", StoreStatus.ACTIVE);
        var original = assignment(staff, store, assigner);
        original.setActive(false, NOW.plusSeconds(1)); entities.flush(); entities.clear();
        assertThatThrownBy(() -> {
            assignments.save(new StaffStore(staff, store, assigner, NOW.plusSeconds(2)));
            entities.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void queriesOnlyActiveAssignedStoresForTheRequestedStaffAndSortsThem() {
        var staff = identity(); var other = identity(); var assigner = identity();
        var alpha = store("Alpha", StoreStatus.ACTIVE); var beta = store("Beta", StoreStatus.ACTIVE);
        var inactiveStore = store("Inactive", StoreStatus.INACTIVE); var inactiveAssignment = store("Unassigned", StoreStatus.ACTIVE);
        assignment(staff, beta, assigner); assignment(staff, alpha, assigner); assignment(staff, inactiveStore, assigner);
        assignment(staff, inactiveAssignment, assigner).setActive(false, NOW);
        entities.flush();
        assertThat(assignments.findAssignedStores(staff.getId(), StoreStatus.ACTIVE)).extracting(Store::getId)
                .containsExactly(alpha.getId(), beta.getId());
        assertThat(assignments.findAssignedStores(other.getId(), StoreStatus.ACTIVE)).isEmpty();
        assertThat(assignments.findAssignedStore(staff.getId(), alpha.getId(), StoreStatus.ACTIVE)).isPresent();
        assertThat(assignments.findAssignedStore(other.getId(), alpha.getId(), StoreStatus.ACTIVE)).isEmpty();
        assertThat(assignments.findAssignedStore(staff.getId(), inactiveStore.getId(), StoreStatus.ACTIVE)).isEmpty();
        assertThat(assignments.findAssignedStore(staff.getId(), inactiveAssignment.getId(), StoreStatus.ACTIVE)).isEmpty();
        assertThat(stores.findByIdAndStatus(inactiveStore.getId(), StoreStatus.ACTIVE)).isEmpty();
    }

    @Test
    void activeStoreCatalogueQueryExcludesInactiveRowsAndUsesStableNameOrdering() {
        var prefix = UUID.randomUUID().toString();
        var beta = store(prefix + "-Beta", StoreStatus.ACTIVE); var alpha = store(prefix + "-Alpha", StoreStatus.ACTIVE);
        var inactive = store(prefix + "-Inactive", StoreStatus.INACTIVE); entities.flush();
        var results = stores.findAllByStatusOrderByNameAscIdAsc(StoreStatus.ACTIVE);
        assertThat(results).extracting(Store::getId).contains(alpha.getId(), beta.getId()).doesNotContain(inactive.getId());
        assertThat(results.stream().filter(item -> item.getName().startsWith(prefix)).map(Store::getId).toList())
                .containsExactly(alpha.getId(), beta.getId());
    }

    @Test
    void deactivationAndReactivationRetainIdentityAndOriginalAssignmentMetadata() {
        var staff = identity(); var assigner = identity(); var store = store("Lifecycle", StoreStatus.ACTIVE);
        var link = assignment(staff, store, assigner);
        link.setActive(false, NOW.plusSeconds(1)); store.setStatus(StoreStatus.INACTIVE, NOW.plusSeconds(1));
        entities.flush(); entities.clear();
        var loaded = assignments.findById(link.getId()).orElseThrow();
        var shop = stores.findById(store.getId()).orElseThrow();
        assertThat(loaded.isActive()).isFalse(); assertThat(shop.getStatus()).isEqualTo(StoreStatus.INACTIVE);
        loaded.setActive(true, NOW.plusSeconds(2)); shop.setStatus(StoreStatus.ACTIVE, NOW.plusSeconds(2));
        entities.flush(); entities.clear();
        var active = assignments.findById(link.getId()).orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(active.getAssignedAt()).isEqualTo(NOW);
        assertThat(active.getAssignedBy().getId()).isEqualTo(assigner.getId());
        assertThat(active.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(stores.findById(store.getId()).orElseThrow().getCreatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> active.setActive(false, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shop.setStatus(StoreStatus.INACTIVE, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff_user_id", "store_id", "assigned_by"})
    void everyAssignmentForeignKeyRequiresAnExistingParent(String column) {
        var staff = identity(); var assigner = identity(); var store = store("Foreign key", StoreStatus.ACTIVE);
        assignment(staff, store, assigner); entities.flush();
        integrity("UPDATE loyalty.staff_store SET " + column + " = ? WHERE staff_user_id = ? AND store_id = ?",
                UUID.randomUUID(), staff.getId(), store.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"store", "staff", "assigner"})
    void assignmentReferencesRestrictParentDeletionEvenAfterDeactivation(String parent) {
        var staff = identity(); var assigner = identity(); var store = store("Retained parent", StoreStatus.ACTIVE);
        assignment(staff, store, assigner).setActive(false, NOW.plusSeconds(1)); entities.flush();
        if (parent.equals("store")) { integrity("DELETE FROM loyalty.stores WHERE id = ?", store.getId()); }
        else { integrity("DELETE FROM loyalty.users WHERE id = ?", parent.equals("staff") ? staff.getId() : assigner.getId()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"store", "staff", "assigner"})
    void referencedParentIdentifiersCannotBeRewritten(String parent) {
        var staff = identity(); var assigner = identity(); var store = store("Retained identifier", StoreStatus.ACTIVE);
        assignment(staff, store, assigner); entities.flush();
        if (parent.equals("store")) { integrity("UPDATE loyalty.stores SET id = ? WHERE id = ?", UUID.randomUUID(), store.getId()); }
        else { integrity("UPDATE loyalty.users SET id = ? WHERE id = ?", UUID.randomUUID(), parent.equals("staff") ? staff.getId() : assigner.getId()); }
    }

    @Test
    void removingAnAssignmentThroughJpaDoesNotCascadeToEitherUserOrStore() {
        var staff = identity(); var assigner = identity(); var store = store("No cascade", StoreStatus.ACTIVE);
        var assignment = assignment(staff, store, assigner); entities.flush();
        entities.remove(assignment); entities.flush(); entities.clear();
        assertThat(assignments.findById(assignment.getId())).isEmpty();
        assertThat(stores.findById(store.getId())).isPresent();
        assertThat(users.findById(staff.getId())).isPresent();
        assertThat(users.findById(assigner.getId())).isPresent();
    }

    @Test
    void deletingAReferencedStoreThroughJpaIsRestrictedAndCannotCascadeAwayHistory() {
        var staff = identity(); var assigner = identity(); var store = store("No parent cascade", StoreStatus.ACTIVE);
        assignment(staff, store, assigner); entities.flush(); entities.clear();
        entities.remove(stores.findById(store.getId()).orElseThrow());
        assertThatThrownBy(entities::flush).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "name", "address", "updated_at"})
    void databaseEnforcesStoreStatusTextAndTimestampConstraints(String field) {
        var store = store("Validated", StoreStatus.ACTIVE); entities.flush();
        String value = switch (field) {
            case "status" -> "'DELETED'";
            case "updated_at" -> "created_at - interval '1 second'";
            default -> "'   '";
        };
        integrity("UPDATE loyalty.stores SET " + field + " = " + value + " WHERE id = ?", store.getId());
    }

    @Test
    void databaseEnforcesAssignmentTimestampOrdering() {
        var staff = identity(); var assigner = identity(); var store = store("Assignment time", StoreStatus.ACTIVE);
        assignment(staff, store, assigner); entities.flush();
        integrity("UPDATE loyalty.staff_store SET updated_at = assigned_at - interval '1 second' "
                + "WHERE staff_user_id = ? AND store_id = ?", staff.getId(), store.getId());
    }

    @Test
    void unsavedAssignmentReferencesCannotCascadeIntoInventedIdentitiesOrStores() {
        var staff = identity(); var assigner = identity(); var store = store("Existing", StoreStatus.ACTIVE);
        var unsavedUser = new User("unsaved@example.org", "synthetic-hash", AccountStatus.ACTIVE, NOW, Set.of());
        var unsavedStore = new Store("Unsaved", "Synthetic address", StoreStatus.ACTIVE, NOW);
        assertThatThrownBy(() -> new StaffStore(unsavedUser, store, assigner, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffStore(staff, unsavedStore, assigner, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffStore(staff, store, unsavedUser, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private void integrity(String sql, Object... parameters) {
        assertThatThrownBy(() -> jdbc.update(sql, parameters)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
