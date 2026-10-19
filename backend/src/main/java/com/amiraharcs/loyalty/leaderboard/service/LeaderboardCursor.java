package com.amiraharcs.loyalty.leaderboard.service;

import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated opaque keyset tuple: hides customer IDs and rejects changes and cross-period reuse. */
@Component
public class LeaderboardCursor {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public LeaderboardCursor(@Value("${loyalty.leaderboards.cursor-key-base64:}") String configured) {
        byte[] bytes;
        try {
            bytes=configured.isBlank()?new byte[32]:Base64.getDecoder().decode(configured);
            if(configured.isBlank()) { random.nextBytes(bytes); }
            if(bytes.length!=32) { throw new IllegalArgumentException(); }
        } catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Leaderboard cursor key must be base64-encoded 32 bytes"); }
        key=new SecretKeySpec(bytes,"AES");
    }
    public record Position(long score, Instant acceptedAt, UUID customerId) { }
    public String encode(Position position,String scope) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)) {
                output.writeByte(1);output.writeUTF(scope);output.writeLong(position.score());
                output.writeLong(position.acceptedAt().getEpochSecond());output.writeInt(position.acceptedAt().getNano());
                output.writeLong(position.customerId().getMostSignificantBits());output.writeLong(position.customerId().getLeastSignificantBits());
            }
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            var result=new ByteArrayOutputStream();result.write(nonce);result.write(cipher.doFinal(bytes.toByteArray()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(result.toByteArray());
        } catch(IOException|GeneralSecurityException failure) { throw new IllegalStateException("Cannot create leaderboard cursor",failure); }
    }
    public Position decode(String value,String scope) {
        if(value==null) { return null; }
        if(value.isBlank() || value.length()>512 || !value.matches("[A-Za-z0-9_-]+")) { throw invalid(); }
        try {
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if(bytes.length<29 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) { throw invalid(); }
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            try(var input=new DataInputStream(new ByteArrayInputStream(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length))))) {
                if(input.readUnsignedByte()!=1 || !input.readUTF().equals(scope)) { throw invalid(); }
                long score=input.readLong();long seconds=input.readLong();int nanos=input.readInt();
                UUID customer=new UUID(input.readLong(),input.readLong());
                if(input.available()!=0 || score<0 || score>9007199254740991L || nanos<0 || nanos>=1000000000) { throw invalid(); }
                return new Position(score,Instant.ofEpochSecond(seconds,nanos),customer);
            }
        } catch(IOException|GeneralSecurityException|IllegalArgumentException|java.time.DateTimeException failure) { throw invalid(); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
