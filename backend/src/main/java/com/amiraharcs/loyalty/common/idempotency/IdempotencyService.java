package com.amiraharcs.loyalty.common.idempotency;

import com.amiraharcs.loyalty.common.api.IdempotencyKeyReusedException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Call after authorization and before aggregate locking. Results commit with the business mutation. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<String> previous(UUID actorId, String operation, UUID key, String payloadHash) {
        // Transaction-scoped PostgreSQL lock also serializes retries targeting different accounts.
        long lock = ByteBuffer.wrap(digest(actorId + ":" + operation + ":" + key)).getLong();
        jdbc.execute((org.springframework.jdbc.core.PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)");
            statement.setLong(1, lock); return statement;
        }, statement -> { statement.execute(); return null; });
        var records = jdbc.query("SELECT request_hash,response_body FROM loyalty.idempotency_records WHERE actor_id=? AND operation=? AND key=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), actorId, operation, key);
        if (records.isEmpty()) { return Optional.empty(); }
        var record = records.getFirst();
        if (!record.hash().equals(payloadHash)) { throw new IdempotencyKeyReusedException(); }
        return Optional.of(record.response());
    }
    public void complete(UUID actorId, String operation, UUID key, String payloadHash, String response, Instant now) {
        jdbc.update("INSERT INTO loyalty.idempotency_records(actor_id,operation,key,request_hash,response_body,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                actorId, operation, key, payloadHash, response, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(86400)));
    }
    public static String hash(String canonicalPayload) { return HexFormat.of().formatHex(digest(canonicalPayload)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private record Stored(String hash, String response) { }
}
