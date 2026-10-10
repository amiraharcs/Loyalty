package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.repository.*;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class IdentityPersistenceTests {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CustomerProfileRepository profiles;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private User user(String email, Set<Role> grants) {
        return users.save(new User(email, "synthetic-hash-not-a-real-credential", AccountStatus.ACTIVE,
                Instant.parse("2026-10-10T12:00:00Z"), grants));
    }

    @Test
    void mapsUserMultipleExplicitRolesProfileAndUtcTimestamps() {
        var customer = roles.findById(RoleCode.CUSTOMER).orElseThrow();
        var staff = roles.findById(RoleCode.STAFF).orElseThrow();
        var user = user("  MEMBER@Example.org  ", Set.of(customer, staff));
        var profile = profiles.save(new CustomerProfile(user, "Member", "Anonymous member", true));
        entities.flush();
        UUID userId = user.getId();
        UUID profileId = profile.getId();
        entities.clear();
        var loaded = users.findById(userId).orElseThrow();
        assertThat(loaded.getCanonicalEmail()).isEqualTo("member@example.org");
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(loaded.getRoles()).extracting(Role::getCode).containsExactlyInAnyOrder(RoleCode.CUSTOMER, RoleCode.STAFF);
        assertThat(profiles.findByUserId(userId).orElseThrow().getId()).isEqualTo(profileId);
        var loadedProfile = profiles.findById(profileId).orElseThrow();
        assertThat(loadedProfile.getUser().getId()).isEqualTo(userId);
        assertThat(loadedProfile.getDisplayName()).isEqualTo("Member");
        assertThat(loadedProfile.getLeaderboardAlias()).isEqualTo("Anonymous member");
        assertThat(loadedProfile.isLeaderboardPrivate()).isTrue();
    }

    @Test
    void canonicalLookupFindsUserAndExistenceWithoutRoleInheritance() {
        user("ADMIN@example.org", Set.of(roles.findById(RoleCode.ADMIN).orElseThrow()));
        entities.flush();
        var loaded = users.findByCanonicalEmail(User.canonicalizeEmail(" ADMIN@EXAMPLE.ORG ")).orElseThrow();
        assertThat(users.existsByCanonicalEmail("admin@example.org")).isTrue();
        assertThat(users.existsByCanonicalEmail("absent@example.org")).isFalse();
        assertThat(loaded.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.ADMIN);
        assertThat(roles.findById(RoleCode.CUSTOMER)).isPresent();
        assertThat(roles.findById(RoleCode.STAFF)).isPresent();
    }

    @Test
    void duplicateEmailIsRejectedAcrossCaseVariants() {
        user("duplicate@example.org", Set.of());
        entities.flush();
        user("DUPLICATE@EXAMPLE.ORG", Set.of());
        assertThatThrownBy(entities::flush).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void persistsDisabledStatusAndExplicitUpdateTime() {
        var user = user("status@example.org", Set.of());
        Instant changedAt = Instant.parse("2026-10-10T12:05:00Z");
        user.setStatus(AccountStatus.DISABLED, changedAt);
        entities.flush();
        entities.clear();
        var loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(changedAt);
        assertThatThrownBy(() -> loaded.setStatus(AccountStatus.ACTIVE, changedAt.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordHashDoesNotAppearInSerializationToStringOrEquality() throws Exception {
        var user = user("private@example.org", Set.of());
        assertThat(user.toString()).doesNotContain("synthetic-hash", "private@example.org");
        assertThat(json.writeValueAsString(user)).doesNotContain("passwordHash", "password_hash", "synthetic-hash");
        assertThat(user).isNotEqualTo(new User("private@example.org", "other-synthetic-hash", AccountStatus.ACTIVE,
                Instant.now(), Set.of()));
    }

    @Test
    void duplicateRoleGrantIsRejected() {
        var user = user("grants@example.org", Set.of(roles.findById(RoleCode.CUSTOMER).orElseThrow()));
        entities.flush();
        assertIntegrityFailure("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'CUSTOMER')", user.getId());
    }

    @Test
    void invalidUserForeignKeyIsRejected() {
        assertIntegrityFailure("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'CUSTOMER')", UUID.randomUUID());
    }

    @Test
    void invalidRoleForeignKeyIsRejected() {
        var user = user("foreign-role@example.org", Set.of());
        entities.flush();
        assertIntegrityFailure("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'UNKNOWN')", user.getId());
    }

    @Test
    void systemRoleIsRejectedByDatabaseCatalogueConstraint() {
        assertIntegrityFailure("INSERT INTO loyalty.roles (code) VALUES ('SYSTEM')");
    }

    @Test
    void invalidAccountStatusIsRejected() {
        var user = user("invalid-status@example.org", Set.of());
        entities.flush();
        assertIntegrityFailure("UPDATE loyalty.users SET status = 'LOCKED' WHERE id = ?", user.getId());
    }

    @Test
    void databaseExceptionsDoNotEchoCredentialBearingRowDetails() {
        String marker = "sensitive-synthetic-hash-marker";
        assertThatThrownBy(() -> jdbc.update("INSERT INTO loyalty.users "
                + "(id, canonical_email, password_hash, status, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), "safe-error@example.org", marker, "INVALID"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> {
                    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                        assertThat(cause.getMessage()).doesNotContain(marker, "Failing row contains");
                    }
                });
    }

    @Test
    void referencedRoleCodeCannotBeChanged() {
        user("role-change@example.org", Set.of(roles.findById(RoleCode.STAFF).orElseThrow()));
        entities.flush();
        assertIntegrityFailure("UPDATE loyalty.roles SET code = 'CUSTOMER' WHERE code = 'STAFF'");
    }

    @Test
    void requiredUserFieldsAreEnforcedByDatabase() {
        assertIntegrityFailure("INSERT INTO loyalty.users (id, canonical_email, status, created_at, updated_at) "
                + "VALUES (?, 'required@example.org', 'ACTIVE', now(), now())", UUID.randomUUID());
    }

    @Test
    void noncanonicalEmailIsRejectedEvenWhenBypassingJpa() {
        assertIntegrityFailure("INSERT INTO loyalty.users (id, canonical_email, password_hash, status, created_at, updated_at) "
                + "VALUES (?, 'UPPER@example.org', 'synthetic-hash', 'ACTIVE', now(), now())", UUID.randomUUID());
    }

    @Test
    void requiredProfileFieldsAreEnforced() {
        var user = user("required-profile@example.org", Set.of());
        entities.flush();
        assertIntegrityFailure("INSERT INTO loyalty.customer_profiles (id, user_id, display_name, leaderboard_private) "
                + "VALUES (?, ?, 'Member', true)", UUID.randomUUID(), user.getId());
    }

    @Test
    void profileUserForeignKeyIsEnforced() {
        assertIntegrityFailure("INSERT INTO loyalty.customer_profiles "
                + "(id, user_id, display_name, leaderboard_alias, leaderboard_private) VALUES (?, ?, 'Member', 'Alias', true)",
                UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void oneProfilePerUserIsEnforced() {
        var user = user("one-profile@example.org", Set.of());
        profiles.save(new CustomerProfile(user, "Member", "Alias", true));
        entities.flush();
        profiles.save(new CustomerProfile(user, "Another", "Another", false));
        assertThatThrownBy(entities::flush).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void referencedUserCannotBeDeletedThroughSql() {
        var user = user("retained@example.org", Set.of());
        profiles.save(new CustomerProfile(user, "Member", "Alias", true));
        entities.flush();
        assertIntegrityFailure("DELETE FROM loyalty.users WHERE id = ?", user.getId());
    }

    @Test
    void referencedRoleCannotBeDeletedOrRenamed() {
        user("retained-role@example.org", Set.of(roles.findById(RoleCode.STAFF).orElseThrow()));
        entities.flush();
        assertIntegrityFailure("DELETE FROM loyalty.roles WHERE code = 'STAFF'");
    }

    @Test
    void referencedUserPrimaryKeyCannotBeChanged() {
        var user = user("retained-id@example.org", Set.of());
        profiles.save(new CustomerProfile(user, "Member", "Alias", true));
        entities.flush();
        assertIntegrityFailure("UPDATE loyalty.users SET id = ? WHERE id = ?", UUID.randomUUID(), user.getId());
    }

    @Test
    void removingProfileDoesNotCascadeToUserOrRoleCatalogue() {
        var user = user("profile-removal@example.org", Set.of(roles.findById(RoleCode.CUSTOMER).orElseThrow()));
        var profile = profiles.save(new CustomerProfile(user, "Member", "Alias", true));
        entities.flush();
        entities.remove(profile);
        entities.flush();
        entities.clear();
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(roles.findById(RoleCode.CUSTOMER)).isPresent();
    }

    @Test
    void profileDoesNotCascadePersistAnUnsavedIdentity() {
        var unsaved = new User("unsaved@example.org", "synthetic-hash", AccountStatus.ACTIVE, Instant.now(), Set.of());
        assertThatThrownBy(() -> {
            profiles.save(new CustomerProfile(unsaved, "Member", "Alias", true));
            entities.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    private void assertIntegrityFailure(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
