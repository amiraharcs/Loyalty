package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.qr.api.QrTokenResponse;
import com.amiraharcs.loyalty.qr.config.QrSigningSettings;
import com.amiraharcs.loyalty.qr.model.QrIdentityToken;
import com.amiraharcs.loyalty.qr.repository.QrTokenRepository;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final LoyaltyAccountService accounts;
    private final QrTokenRepository tokens;
    private final EntityManager entities;
    private final QrTokenCodec codec;
    private final QrSigningSettings signing;
    private final Clock clock;
    private final QrIssuanceRateLimiter limiter;
    public QrTokenService(AuthenticationIdentityService identities, CustomerProfileRepository profiles,
            LoyaltyAccountService accounts, QrTokenRepository tokens, EntityManager entities, QrTokenCodec codec,
            QrSigningSettings signing, Clock clock, QrIssuanceRateLimiter limiter) {
        this.identities = identities; this.profiles = profiles; this.accounts = accounts; this.tokens = tokens;
        this.entities = entities; this.codec = codec; this.signing = signing; this.clock = clock; this.limiter = limiter;
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    @Transactional
    public QrTokenResponse issue() {
        UUID actorId = currentCustomer();
        var profile = profiles.findByUserId(actorId).orElseThrow(ResourceNotFoundException::new);
        accounts.lockForMutation(profile.getId());
        currentCustomer(); // Fresh database grants after waiting for the aggregate lock.
        long retryAfter = limiter.retryAfter(profile.getId());
        if (retryAfter > 0) { throw new com.amiraharcs.loyalty.common.api.RateLimitException(retryAfter); }
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        tokens.revokeCurrent(profile.getId(), now);
        byte[] nonce = new byte[32]; RANDOM.nextBytes(nonce);
        String jti = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        var token = new QrIdentityToken(jti, profile, now, signing.activeKeyId());
        entities.persist(token); entities.flush();
        String encoded = codec.sign(jti, now);
        return new QrTokenResponse(encoded, token.getExpiresAt(), now);
    }
    private UUID currentCustomer() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var roles = identities.activeRoleGrants(principal.userId()).orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!roles.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
