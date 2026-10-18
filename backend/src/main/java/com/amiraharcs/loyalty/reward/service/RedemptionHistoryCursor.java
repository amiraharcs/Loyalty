package com.amiraharcs.loyalty.reward.service;

import com.amiraharcs.loyalty.reward.model.RewardRedemption;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded opaque timestamp/UUID tuple bound to the server-resolved customer scope. */
final class RedemptionHistoryCursor {
    private RedemptionHistoryCursor() { }
    record Position(boolean after,Instant time,UUID id) { }
    static Position decode(String value,UUID owner) {
        if(value==null) { return new Position(false,Instant.EPOCH,new UUID(0,0)); }
        if(value.isBlank() || value.length()>256) { throw invalid(); }
        try(var input=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(value)))) {
            if(input.readUnsignedByte()!=1 || !owner.equals(uuid(input))) { throw invalid(); }
            long seconds=input.readLong();int nanos=input.readInt();
            if(nanos<0 || nanos>=1_000_000_000 || nanos%1000!=0) { throw invalid(); }
            var time=Instant.ofEpochSecond(seconds,nanos);var id=uuid(input);
            if(input.available()!=0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) { throw invalid(); }
            return new Position(true,time,id);
        } catch(IOException | IllegalArgumentException | DateTimeException malformed) { throw invalid(); }
    }
    static String encode(RewardRedemption row,UUID owner) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)) {
                output.writeByte(1);uuid(output,owner);output.writeLong(row.getRequestedAt().getEpochSecond());
                output.writeInt(row.getRequestedAt().getNano());uuid(output,row.getId());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException("Unable to encode history cursor",impossible); }
    }
    private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(),input.readLong()); }
    private static void uuid(DataOutputStream output,UUID id) throws IOException { output.writeLong(id.getMostSignificantBits());output.writeLong(id.getLeastSignificantBits()); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
