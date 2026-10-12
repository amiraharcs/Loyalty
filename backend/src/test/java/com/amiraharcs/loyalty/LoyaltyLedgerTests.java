package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.AccountStatus;
import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.loyalty.repository.*;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.store.model.*;
import com.amiraharcs.loyalty.store.repository.StoreRepository;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.repository.VisitRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LoyaltyLedgerTests {
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    @Autowired UserRepository users;
    @Autowired CustomerProfileRepository profiles;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired PointTransactionRepository ledger;
    @Autowired LoyaltyAccountService service;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreRepository stores;
    @Autowired VisitRepository visits;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private User user() {
        return users.save(new User(UUID.randomUUID() + "@example.org", "synthetic-hash", AccountStatus.ACTIVE, NOW, Set.of()));
    }
    private LoyaltyAccount account() {
        return accounts.save(new LoyaltyAccount(profiles.save(new CustomerProfile(user(), "Member", "Member", true)), NOW));
    }
    private PointTransaction append(LoyaltyAccount account, long amount, PointTransactionType type, Instant at) {
        if (type == PointTransactionType.EARN) {
            var store = stores.save(new Store("Synthetic earning source", "Synthetic address", StoreStatus.ACTIVE, at));
            var visit = visits.append(new Visit(account.getCustomer(), account, store, user(), at, amount));
            return ledger.append(PointTransaction.forVisit(visit));
        }
        return ledger.append(new PointTransaction(account, amount, type, null, null, at));
    }
    private void integrity(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mapsAccountOwnerVersionAndTimeWithoutBalanceOrCascades() {
        var account = account(); entities.flush(); entities.clear();
        var loaded = service.resolve(account.getCustomer().getId());
        assertThat(loaded.getId()).isEqualTo(account.getId());
        assertThat(loaded.getCustomer().getId()).isEqualTo(account.getCustomer().getId());
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(service.currentBalance(loaded.getCustomer().getId())).isZero();
        assertThat(service.history(loaded.getCustomer().getId(), 0, 10)).isEmpty();
        assertThat(LoyaltyAccount.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("balance", "points");
    }

    @Test
    void positiveNegativeAndMixedEntriesDeriveExactBalanceAndDoNotMixCustomers() {
        var account = account(); var other = account();
        append(account, 4_000_000_000L, PointTransactionType.EARN, NOW);
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(4_000_000_000L);
        append(account, -3_000_000_000L, PointTransactionType.REDEMPTION, NOW);
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(1_000_000_000L);
        append(account, 30, PointTransactionType.REFUND, NOW);
        ledger.append(new PointTransaction(account, -10, PointTransactionType.ADJUSTMENT, user(), "Correction", NOW));
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(1_000_000_020L);
        assertThat(service.currentBalance(other.getCustomer().getId())).isZero();
    }

    @Test
    void historyPersistsTypesAndUsesTimestampThenUuidDescendingWithBoundedProjection() {
        var account = account();
        var first = append(account, 100, PointTransactionType.EARN, NOW);
        var second = append(account, -40, PointTransactionType.REDEMPTION, NOW);
        var refund = append(account, 20, PointTransactionType.REFUND, NOW.plusSeconds(1));
        var actor = user();
        var adjustment = ledger.append(new PointTransaction(account, 2, PointTransactionType.ADJUSTMENT, actor, "Audited reason", NOW.plusSeconds(2)));
        entities.clear();
        // PostgreSQL orders UUID bytes unsigned; java UUID.compareTo uses signed longs.
        var tied = java.util.stream.Stream.of(first.getId(), second.getId())
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        var history = service.history(account.getCustomer().getId(), 0, 10);
        assertThat(history).extracting(e -> e.id()).containsExactly(adjustment.getId(), refund.getId(), tied.get(0), tied.get(1));
        assertThat(history).extracting(e -> e.type()).containsExactlyInAnyOrder(PointTransactionType.values());
        assertThat(service.history(account.getCustomer().getId(), 1, 2)).hasSize(2);
        assertThatThrownBy(() -> service.history(account.getCustomer().getId(), 0, 101)).isInstanceOf(IllegalArgumentException.class);
        var loaded = ledger.findByAccountIdOrderByCreatedAtDescIdDesc(account.getId(), PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(loaded.getActor().getId()).isEqualTo(actor.getId());
        assertThat(loaded.getReason()).isEqualTo("Audited reason");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void enforcesExactlyOneAccountPerCustomer() {
        var account = account(); entities.flush();
        integrity("INSERT INTO loyalty.loyalty_accounts(id,customer_id,created_at) VALUES (?,?,now())", UUID.randomUUID(), account.getCustomer().getId());
    }

    @Test
    void rejectsMissingCustomerForeignKey() {
        integrity("INSERT INTO loyalty.loyalty_accounts(id,customer_id,created_at) VALUES (?,?,now())", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void rejectsMissingAccountForeignKey() {
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES (?,?,1,'REFUND',now())", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void customerCannotBeDeletedWithAccount() {
        var account = account(); entities.flush();
        integrity("DELETE FROM loyalty.customer_profiles WHERE id = ?", account.getCustomer().getId());
    }

    @Test
    void accountCannotBeDeletedWithHistory() {
        var account = account(); append(account, 5, PointTransactionType.EARN, NOW);
        integrity("DELETE FROM loyalty.loyalty_accounts WHERE id = ?", account.getId());
    }

    @Test
    void actorCannotBeDeletedWithHistory() {
        var account = account(); var actor = user();
        ledger.append(new PointTransaction(account, 5, PointTransactionType.ADJUSTMENT, actor, "Correction", NOW));
        integrity("DELETE FROM loyalty.users WHERE id = ?", actor.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE loyalty.point_transactions SET signed_amount = 99 WHERE id = ?",
            "DELETE FROM loyalty.point_transactions WHERE id = ?"})
    void databaseRejectsHistoricalUpdatesAndDeletes(String sql) {
        var entry = append(account(), 5, PointTransactionType.EARN, NOW);
        integrity(sql, entry.getId());
    }

    @Test
    void databaseRejectsTruncationIncludingCascade() {
        append(account(), 5, PointTransactionType.EARN, NOW);
        integrity("TRUNCATE loyalty.point_transactions CASCADE");
    }

    @Test
    void entityAndRepositoryExposeNoHistoricalMutationAndCannotAppendExistingRecord() {
        var entry = append(account(), 5, PointTransactionType.EARN, NOW);
        assertThat(PointTransaction.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        assertThat(PointTransaction.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.startsWith("set") || name.startsWith("delete"));
        assertThat(PointTransactionRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("save", "saveAll", "delete", "deleteAll", "deleteById");
        assertThatThrownBy(() -> ledger.append(entry)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
    }

    @Test
    void hibernateDoesNotRewriteAnImmutableLoadedRecord() throws Exception {
        var account = account(); var entry = append(account, 5, PointTransactionType.EARN, NOW);
        var field = PointTransaction.class.getDeclaredField("signedAmount");
        field.setAccessible(true); field.setLong(entry, 99); // Simulate accidental internal field mutation.
        entities.flush(); entities.clear();
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(5);
        assertThat(service.history(account.getCustomer().getId(), 0, 10).getContent().getFirst().signedAmount()).isEqualTo(5);
    }

    @Test
    void persistsMinimumSafeIntegerExactlyWithoutIntroducingAWorkflow() {
        var account = account();
        append(account, -PointValues.MAX_SAFE_INTEGER, PointTransactionType.REDEMPTION, NOW);
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(-PointValues.MAX_SAFE_INTEGER);
    }

    @Test
    void aggregateUnderflowIsRejectedBeforeCommit() {
        var account = account(); append(account, -PointValues.MAX_SAFE_INTEGER, PointTransactionType.REDEMPTION, NOW);
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES (?,?, -1,'REDEMPTION',now())", UUID.randomUUID(), account.getId());
    }

    @Test
    void persistsMaximumSafeIntegerExactly() {
        var account = account(); append(account, PointValues.MAX_SAFE_INTEGER, PointTransactionType.EARN, NOW);
        assertThat(service.currentBalance(account.getCustomer().getId())).isEqualTo(PointValues.MAX_SAFE_INTEGER);
        append(account, -PointValues.MAX_SAFE_INTEGER, PointTransactionType.REDEMPTION, NOW);
        assertThat(service.currentBalance(account.getCustomer().getId())).isZero();
    }

    @Test
    void aggregateOverflowIsRejectedBeforeCommitEvenWhenEachAmountIsSafe() {
        var account = account(); append(account, PointValues.MAX_SAFE_INTEGER, PointTransactionType.EARN, NOW);
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES (?,?,1,'REFUND',now())", UUID.randomUUID(), account.getId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 9007199254740992L, -9007199254740992L, Long.MAX_VALUE, Long.MIN_VALUE})
    void databaseRejectsZeroAndUnsafeAmounts(long amount) {
        var account = account(); var actor = user(); entities.flush();
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) VALUES (?,?,?,'ADJUSTMENT',?,'Test',now())", UUID.randomUUID(), account.getId(), amount, actor.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "REDEMPTION"})
    void databaseRejectsUnknownTypesAndWrongSign(String type) {
        var account = account(); entities.flush();
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES (?,?,1,?,now())", UUID.randomUUID(), account.getId(), type);
    }

    @Test
    void databaseRequiresAdjustmentActorAndReason() {
        var account = account(); entities.flush();
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES (?,?,1,'ADJUSTMENT',now())", UUID.randomUUID(), account.getId());
    }

    @Test
    void databaseRejectsWhitespaceOnlyReason() {
        var account = account(); var actor = user(); entities.flush();
        integrity("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) VALUES (?,?,1,'ADJUSTMENT',?,'   ',now())", UUID.randomUUID(), account.getId(), actor.getId());
    }

    @Test
    void unknownCustomerDoesNotResolveAnAccountOrZeroBalance() {
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }
}
