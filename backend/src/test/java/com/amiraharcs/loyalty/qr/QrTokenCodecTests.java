package com.amiraharcs.loyalty.qr;

import com.amiraharcs.loyalty.qr.config.QrSigningSettings;
import com.amiraharcs.loyalty.qr.service.*;
import com.amiraharcs.loyalty.qr.repository.QrTokenRepository;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QrTokenCodecTests {
    static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final byte[] secret = random(64);
    final String jti = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    final QrSigningSettings settings = settings("current", secret, Map.of());
    final QrTokenCodec codec = new QrTokenCodec(settings, CLOCK);
    static byte[] random(int size) { byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes); return bytes; }
    static QrSigningSettings settings(String active, byte[] secret, Map<String, javax.crypto.SecretKey> old) {
        var keys = new HashMap<>(old); keys.put(active, new SecretKeySpec(secret, "HmacSHA256"));
        return new QrSigningSettings("loyalty-test", "loyalty-identity-test", active, keys);
    }
    Map<String,Object> claims() {
        return new HashMap<>(Map.of("iss", "loyalty-test", "aud", "loyalty-identity-test", "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(60).getEpochSecond(), "jti", jti));
    }
    String signed(Map<String,Object> claims, String kid, JWSAlgorithm algorithm, byte[] key) throws Exception {
        var object = new JWSObject(new JWSHeader.Builder(algorithm).type(new JOSEObjectType("loyalty-qr+jwt")).keyID(kid).build(), new Payload(claims));
        object.sign(new MACSigner(key)); return object.serialize();
    }
    String signed(Map<String,Object> claims) throws Exception { return signed(claims, "current", JWSAlgorithm.HS256, secret); }
    void invalid(String token) {
        assertThatThrownBy(() -> codec.verify(token)).isInstanceOf(InvalidQrTokenException.class)
                .hasMessage("QR identity token is not valid").hasNoCause();
    }
    @Test void signsMinimalHs256ClaimsWithExactLifetimeAndCorrectSignature() throws Exception {
        String token = codec.sign(jti, NOW); var jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(secret))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("current");
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("iss","aud","iat","exp","jti");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(settings.issuer());
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(settings.audience());
        assertThat(codec.verify(token)).isEqualTo(new QrTokenCodec.VerifiedReference(jti,"current",NOW,NOW.plusSeconds(60)));
    }
    @ParameterizedTest @ValueSource(strings={"HS384","HS512"})
    void rejectsUnexpectedAlgorithmsEvenWithValidMac(String algorithm) throws Exception { invalid(signed(claims(),"current",new JWSAlgorithm(algorithm),secret)); }
    @Test void rejectsNoneAndRsaSubstitution() throws Exception {
        invalid(new PlainJWT(new JWTClaimsSet.Builder().jwtID(jti).build()).serialize());
        var token = codec.sign(jti, NOW).split("\\.");
        String header = com.nimbusds.jose.util.Base64URL.encode("{\"alg\":\"RS256\",\"typ\":\"loyalty-qr+jwt\",\"kid\":\"current\"}").toString();
        invalid(header + "." + token[1] + "." + token[2]);
    }
    @Test void rejectsUnknownKeyAndTamperedSignatureAndTamperedPayload() throws Exception {
        invalid(signed(claims(), "unknown", JWSAlgorithm.HS256, secret));
        invalid(signed(claims(), "current", JWSAlgorithm.HS256, random(64)));
        String token = codec.sign(jti, NOW); var parts = token.split("\\.");
        String modified = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        invalid(parts[0] + "." + parts[1] + "." + modified);
        var altered = claims(); altered.put("jti", Base64.getUrlEncoder().withoutPadding().encodeToString(random(32)));
        invalid(parts[0] + "." + com.nimbusds.jose.util.Base64URL.encode(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(altered)) + "." + parts[2]);
    }
    @ParameterizedTest @ValueSource(strings={"", "not-a-token", "a.b.c", "eyJhbGciOiJub25lIn0.e30.", "null"})
    void malformedTokensReturnOnlySanitizedOutcome(String token) { invalid(token); }
    @Test void rejectsNullAndOversizedTokens() { invalid(null); invalid("a".repeat(2049)); }
    @ParameterizedTest @ValueSource(strings={"issuer","audience","future","tooLong","tooShort","fractionalIat","fractionalExp","missingExp","missingIat","extraClaim","badJti","multipleAudiences","stringTime"})
    void rejectsInvalidClaims(String mutation) throws Exception {
        var payload = claims();
        switch (mutation) {
            case "issuer" -> payload.put("iss", "foreign-issuer");
            case "audience" -> payload.put("aud", "foreign-audience");
            case "future" -> { payload.put("iat", NOW.plusSeconds(1).getEpochSecond()); payload.put("exp", NOW.plusSeconds(61).getEpochSecond()); }
            case "tooLong" -> payload.put("exp", NOW.plusSeconds(61).getEpochSecond());
            case "tooShort" -> payload.put("exp", NOW.plusSeconds(59).getEpochSecond());
            case "fractionalIat" -> payload.put("iat", NOW.getEpochSecond() + 0.5);
            case "fractionalExp" -> payload.put("exp", NOW.plusSeconds(60).getEpochSecond() + 0.5);
            case "missingExp" -> payload.remove("exp");
            case "missingIat" -> payload.remove("iat");
            case "extraClaim" -> payload.put("customerId", UUID.randomUUID().toString());
            case "badJti" -> payload.put("jti", "short");
            case "multipleAudiences" -> payload.put("aud", List.of("loyalty-identity-test", "other"));
            case "stringTime" -> payload.put("iat", "2026-10-14T12:00:00Z");
        }
        invalid(signed(payload));
    }
    @Test void expiresAtExactExclusiveDeadlineWithNoClockLeeway() {
        String token = codec.sign(jti, NOW);
        assertThat(new QrTokenCodec(settings, Clock.fixed(NOW.plusSeconds(59).plusNanos(999999999), ZoneOffset.UTC)).verify(token)).isNotNull();
        assertThatThrownBy(() -> new QrTokenCodec(settings, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)).verify(token))
                .isInstanceOfSatisfying(InvalidQrTokenException.class, ex -> assertThat(ex.reason()).isEqualTo(InvalidQrTokenException.Reason.EXPIRED));
    }
    @Test void rotatesKeysWhileOnlySigningWithActiveKeyAndRejectsRemovedKeys() {
        String oldToken = codec.sign(jti, NOW);
        byte[] next = random(32);
        var rotated = settings("next", next, Map.of("current", new SecretKeySpec(secret,"HmacSHA256")));
        assertThat(new QrTokenCodec(rotated, CLOCK).verify(oldToken).keyId()).isEqualTo("current");
        String newToken = new QrTokenCodec(rotated, CLOCK).sign(jti,NOW);
        assertThat(new QrTokenCodec(rotated, CLOCK).verify(newToken).keyId()).isEqualTo("next");
        assertThatThrownBy(() -> new QrTokenCodec(settings("next",next,Map.of()),CLOCK).verify(oldToken)).isInstanceOf(InvalidQrTokenException.class);
    }
    @Test void signatureAndClaimValidationPrecedeAllDatabaseAccess() throws Exception {
        var repository = mock(QrTokenRepository.class); var identities = mock(AuthenticationIdentityService.class);
        var validator = new QrTokenValidationService(codec,repository,identities,CLOCK);
        var bad = claims(); bad.put("iss","foreign");
        for (String token : List.of("bad-token", signed(bad), signed(claims(),"current",JWSAlgorithm.HS256,random(64)))) {
            assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(InvalidQrTokenException.class);
        }
        verifyNoInteractions(repository,identities);
    }
}
