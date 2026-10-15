package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.repository.*;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.model.LoyaltyAccount;
import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import com.amiraharcs.loyalty.loyalty.repository.PointTransactionRepository;
import com.amiraharcs.loyalty.loyalty.repository.LoyaltyAccountRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.store.model.*;
import com.amiraharcs.loyalty.store.repository.*;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.repository.VisitRepository;
import com.amiraharcs.loyalty.visit.service.VisitService;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.doReturn;

/** Disposable PostgreSQL fixtures; no fixture enters production code or the local Compose database. */
@SpringBootTest
abstract class VisitTestSupport {
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    static final Instant CREATED = Instant.parse("2025-01-01T00:00:00Z");
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CustomerProfileRepository profiles;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired LoyaltyAccountService loyalty;
    @Autowired com.amiraharcs.loyalty.loyalty.service.StreakService streaks;
    @Autowired StoreRepository stores;
    @Autowired StaffStoreRepository assignments;
    @MockitoSpyBean VisitRepository visits;
    @MockitoSpyBean PointTransactionRepository ledger;
    @Autowired VisitService service;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean Clock clock;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }
    @BeforeEach
    void serverClock() { at(NOW); SecurityContextHolder.clearContext(); }
    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }
    void at(Instant instant) { doReturn(instant).when(clock).instant(); }
    User user(RoleCode... grants) {
        var catalogue = new HashSet<Role>();
        for (var grant : grants) { catalogue.add(roles.findById(grant).orElseThrow()); }
        return users.save(new User(UUID.randomUUID() + "@example.org", "synthetic-hash", AccountStatus.ACTIVE, CREATED, catalogue));
    }
    LoyaltyAccount customer() {
        return new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx -> {
            var profile=profiles.save(new CustomerProfile(user(RoleCode.CUSTOMER), "Member", "Member", true));
            var account=accounts.save(new LoyaltyAccount(profile,CREATED));streaks.createForRegistration(profile.getId());return account;
        });
    }
    Store assignedStore(User staff, StoreStatus status) {
        var store = stores.save(new Store("Synthetic shop", "Synthetic address", status, CREATED));
        assignments.save(new StaffStore(staff, store, staff, CREATED));
        return store;
    }
    Setup fixture() {
        var account = customer(); var staff = user(RoleCode.STAFF);
        return new Setup(account.getCustomer(), account, assignedStore(staff, StoreStatus.ACTIVE), staff);
    }
    void authenticate(User actor, RoleCode... grants) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(actor.getId()), null,
                RoleAuthorities.forRoles(Set.of(grants))));
        SecurityContextHolder.setContext(context);
    }
    Visit append(Setup setup, Instant at) {
        var visit = visits.append(new Visit(setup.customer(), setup.account(), setup.store(), setup.staff(), at, 100));
        ledger.append(PointTransaction.forVisit(visit));
        return visit;
    }
    record Setup(CustomerProfile customer, LoyaltyAccount account, Store store, User staff) { }
}
