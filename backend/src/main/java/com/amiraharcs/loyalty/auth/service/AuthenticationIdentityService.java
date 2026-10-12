package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.auth.api.CurrentUserResponse;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import java.time.Clock;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationIdentityService {
    private final UserRepository users;
    private final CustomerProfileRepository profiles;
    private final Clock clock;
    private final EntityManager entities;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AuthenticationIdentityService(UserRepository users, CustomerProfileRepository profiles, Clock clock, EntityManager entities, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.users = users; this.profiles = profiles; this.clock = clock; this.entities = entities; this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> credentials(String email) {
        return users.findByCanonicalEmail(email).map(user -> new Credentials(user.getId(), user.getPasswordHash(), user.getStatus()));
    }

    @Transactional(readOnly = true)
    public Optional<CurrentIdentity> current(UUID id) {
        return users.findById(id).filter(user -> user.getStatus() == AccountStatus.ACTIVE).map(user -> {
            var roles = new HashSet<RoleCode>();
            user.getRoles().forEach(role -> roles.add(role.getCode()));
            String name = profiles.findByUserId(id).map(profile -> profile.getDisplayName()).orElse(null);
            return new CurrentIdentity(new CurrentUserResponse(id, name, roles), passwordVersion(user.getPasswordHash()));
        });
    }

    /** Fresh database projection for permission rechecks after waiting for a business aggregate lock. */
    @Transactional(readOnly = true)
    public Optional<Set<RoleCode>> activeRoleGrants(UUID id) {
        var rows = jdbc.query("SELECT u.status, r.role_code FROM loyalty.users u LEFT JOIN loyalty.user_roles r ON r.user_id=u.id WHERE u.id=?",
                (rs, row) -> new GrantRow(rs.getString("status"), rs.getString("role_code")), id);
        if (rows.isEmpty() || !rows.getFirst().status().equals("ACTIVE")) { return Optional.empty(); }
        var grants = new HashSet<RoleCode>();
        rows.stream().map(GrantRow::role).filter(Objects::nonNull).map(RoleCode::valueOf).forEach(grants::add);
        return Optional.of(Set.copyOf(grants));
    }
    private record GrantRow(String status, String role) { }

    @Transactional
    public boolean upgradeHash(UUID id, String previousHash, String upgradedHash) {
        return entities.createQuery("update User user set user.passwordHash = :next, user.updatedAt = :now "
                        + "where user.id = :id and user.passwordHash = :previous and user.status = :active")
                .setParameter("next", upgradedHash).setParameter("now", clock.instant()).setParameter("id", id)
                .setParameter("previous", previousHash).setParameter("active", AccountStatus.ACTIVE).executeUpdate() == 1;
    }

    public static String passwordVersion(String encoded) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    public record Credentials(UUID id, String hash, AccountStatus status) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record CurrentIdentity(CurrentUserResponse response, String passwordVersion) { }
}
