package com.amiraharcs.loyalty.competition.service;

import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded opaque timestamp/UUID tuple bound to the server-resolved customer scope. */
final class PrizeAdminCursor {
    private PrizeAdminCursor() { }
    record Position(boolean after,Instant time,UUID id) { }
    static Position decode(String value,String scope) {
        if(value==null) { return new Position(false,Instant.EPOCH,new UUID(0,0)); }
        if(value.isBlank() || value.length()>256 || !value.matches("[A-Za-z0-9_-]+")) { throw invalid(); }
        try(var input=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(value)))) {
            if(input.readUnsignedByte()!=1 || !java.security.MessageDigest.isEqual(scopeDigest(scope),input.readNBytes(32))) { throw invalid(); }
            long seconds=input.readLong();int nanos=input.readInt();
            if(nanos<0 || nanos>=1_000_000_000 || nanos%1000!=0) { throw invalid(); }
            var time=Instant.ofEpochSecond(seconds,nanos);var id=uuid(input);
            if(input.available()!=0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) { throw invalid(); }
            return new Position(true,time,id);
        } catch(IOException | IllegalArgumentException | DateTimeException malformed) { throw invalid(); }
    }
    static String encode(Instant time,UUID id,String scope) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)) {
                output.writeByte(1);output.write(scopeDigest(scope));output.writeLong(time.getEpochSecond());
                output.writeInt(time.getNano());uuid(output,id);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException("Unable to encode history cursor",impossible); }
    }
    private static byte[] scopeDigest(String scope) {
        try {return java.security.MessageDigest.getInstance("SHA-256").digest(("admin-prizes:"+scope).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(),input.readLong()); }
    private static void uuid(DataOutputStream output,UUID id) throws IOException { output.writeLong(id.getMostSignificantBits());output.writeLong(id.getLeastSignificantBits()); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
