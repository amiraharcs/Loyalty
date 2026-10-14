package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.qr.model.QrTokenStatus;
import com.amiraharcs.loyalty.qr.repository.QrTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal identity resolution only. Future scan/confirmation services must enforce staff/store scope and recheck under locks. */
@Service
public class QrTokenValidationService {
    private final QrTokenCodec codec;
    private final QrTokenRepository tokens;
    private final AuthenticationIdentityService identities;
    private final Clock clock;
    public QrTokenValidationService(QrTokenCodec codec, QrTokenRepository tokens, AuthenticationIdentityService identities, Clock clock) {
        this.codec = codec; this.tokens = tokens; this.identities = identities; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public ValidatedIdentity validate(String encoded) {
        var reference = codec.verify(encoded); // No token database access until signature and claims pass.
        var token = tokens.findById(reference.jti()).orElseThrow(() -> invalid(InvalidQrTokenException.Reason.INVALID_TOKEN));
        if (!token.getIssuedAt().equals(reference.issuedAt()) || !token.getExpiresAt().equals(reference.expiresAt())
                || !token.getKeyId().equals(reference.keyId())) { throw invalid(InvalidQrTokenException.Reason.INVALID_TOKEN); }
        if (token.getStatus() != QrTokenStatus.ACTIVE) { throw invalid(InvalidQrTokenException.Reason.REVOKED); }
        if (!clock.instant().isBefore(token.getExpiresAt())) { throw invalid(InvalidQrTokenException.Reason.EXPIRED); }
        var grants = identities.activeRoleGrants(token.getCustomer().getUser().getId())
                .orElseThrow(() -> invalid(InvalidQrTokenException.Reason.INACTIVE_CUSTOMER));
        if (!grants.contains(RoleCode.CUSTOMER)) { throw invalid(InvalidQrTokenException.Reason.INACTIVE_CUSTOMER); }
        return new ValidatedIdentity(token.getJti(), token.getCustomer().getId(), token.getExpiresAt());
    }
    private static InvalidQrTokenException invalid(InvalidQrTokenException.Reason reason) { return new InvalidQrTokenException(reason); }
    public record ValidatedIdentity(String jti, UUID customerId, Instant expiresAt) { }
}
