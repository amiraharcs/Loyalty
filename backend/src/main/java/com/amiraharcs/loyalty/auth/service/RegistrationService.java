package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.AccountStatus;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.repository.RoleRepository;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.loyalty.service.StreakService;
import com.amiraharcs.loyalty.game.service.GameLifeService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RegistrationService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerProfileRepository profiles;
    private final LoyaltyAccountService loyaltyAccounts;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final EntityManager entities;
    private final TransactionTemplate transactions;
    private final StreakService streaks;
    private final GameLifeService gameLives;

    public RegistrationService(UserRepository users, RoleRepository roles, CustomerProfileRepository profiles,
            PasswordEncoder encoder, Clock clock, EntityManager entities, PlatformTransactionManager manager,
            LoyaltyAccountService loyaltyAccounts, StreakService streaks, GameLifeService gameLives) {
        this.users = users;
        this.roles = roles;
        this.profiles = profiles;
        this.loyaltyAccounts = loyaltyAccounts;
        this.streaks = streaks;
        this.gameLives = gameLives;
        this.encoder = encoder;
        this.clock = clock;
        this.entities = entities;
        this.transactions = new TransactionTemplate(manager);
    }

    public void register(@NotNull @Valid RegistrationRequest request) {
        // Validate via the service proxy; hash before opening a DB transaction/connection.
        String hash = encoder.encode(request.password());
        try {
            transactions.executeWithoutResult(status -> {
                var role = roles.findById(RoleCode.CUSTOMER)
                        .orElseThrow(() -> new IllegalStateException("Required role catalogue entry is missing"));
                var user = users.save(new User(User.canonicalizeEmail(request.email()), hash,
                        AccountStatus.ACTIVE, clock.instant(), Set.of(role)));
                // Privacy-first default: no email-derived alias or public name until explicitly chosen later.
                var profile = profiles.save(new CustomerProfile(user, request.displayName(), "Member", true));
                loyaltyAccounts.createForRegistration(profile, clock.instant());
                streaks.createForRegistration(profile.getId());
                gameLives.createForRegistration(profile.getId());
                entities.flush(); // Surface constraints inside the atomic persistence boundary.
            });
        } catch (RuntimeException exception) {
            // Inspect structured constraint metadata after rollback, not SQL/messages or rejected values.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_users_canonical_email".equals(violation.getConstraintName())) {
                    throw new ConflictException();
                }
            }
            throw exception;
        }
    }
}
