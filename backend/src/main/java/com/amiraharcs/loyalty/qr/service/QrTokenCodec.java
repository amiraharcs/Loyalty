package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.qr.config.QrSigningSettings;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Nimbus parses/verifies JWS. Claims are accessed only after the fixed HS256 signature check. */
@Component
public class QrTokenCodec {
    private static final JOSEObjectType TYPE = new JOSEObjectType("loyalty-qr+jwt");
    private final QrSigningSettings settings;
    private final Clock clock;
    public QrTokenCodec(QrSigningSettings settings, Clock clock) { this.settings = settings; this.clock = clock; }
    public String sign(String jti, Instant issuedAt) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(settings.issuer()).audience(settings.audience())
                    .issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plusSeconds(60))).jwtID(jti).build();
            var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(settings.activeKeyId()).type(TYPE).build(), claims);
            signed.sign(new MACSigner(settings.signingKey()));
            return signed.serialize();
        } catch (JOSEException failure) { throw new IllegalStateException("QR signing failed"); }
    }
    public VerifiedReference verify(String encoded) {
        try {
            if (encoded == null || encoded.length() > 2048) { throw invalid(); }
            var signed = SignedJWT.parse(encoded);
            var header = signed.getHeader();
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm()) || !TYPE.equals(header.getType())
                    || !header.toJSONObject().keySet().equals(Set.of("alg", "kid", "typ"))) { throw invalid(); }
            var key = settings.verificationKey(header.getKeyID());
            if (key == null || !signed.verify(new MACVerifier(key))) { throw invalid(); }
            var payload = signed.getPayload().toJSONObject();
            if (payload == null || !payload.keySet().equals(Set.of("iss", "aud", "iat", "exp", "jti"))
                    || !integer(payload.get("iat")) || !integer(payload.get("exp"))) { throw invalid(); }
            var claims = signed.getJWTClaimsSet();
            if (!settings.issuer().equals(claims.getIssuer()) || !List.of(settings.audience()).equals(claims.getAudience())
                    || claims.getJWTID() == null || !claims.getJWTID().matches("[A-Za-z0-9_-]{43}")) { throw invalid(); }
            var issuedAt = claims.getIssueTime().toInstant();
            var expiresAt = claims.getExpirationTime().toInstant();
            var now = clock.instant();
            if (issuedAt.isAfter(now) || !issuedAt.plusSeconds(60).equals(expiresAt)) { throw invalid(); }
            if (!now.isBefore(expiresAt)) { throw new InvalidQrTokenException(InvalidQrTokenException.Reason.EXPIRED); }
            return new VerifiedReference(claims.getJWTID(), header.getKeyID(), issuedAt, expiresAt);
        } catch (InvalidQrTokenException expected) { throw expected; }
        catch (Exception invalid) { throw invalid(); } // Never retain parse/crypto exceptions or the encoded token.
    }
    private static boolean integer(Object value) { return value instanceof Integer || value instanceof Long; }
    private static InvalidQrTokenException invalid() { return new InvalidQrTokenException(InvalidQrTokenException.Reason.INVALID_TOKEN); }
    public record VerifiedReference(String jti, String keyId, Instant issuedAt, Instant expiresAt) { }
}
