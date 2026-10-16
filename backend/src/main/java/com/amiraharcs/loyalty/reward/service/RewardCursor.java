package com.amiraharcs.loyalty.reward.service;

import com.amiraharcs.loyalty.reward.model.Reward;
import java.io.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Opaque, bounded keyset tuple, explicitly bound to customer/staff versus admin listing scope. */
final class RewardCursor {
    private RewardCursor() { }
    record Position(long cost, String name, UUID id) { }
    static Position decode(String value, boolean admin) {
        if (value == null) { return new Position(-1, "", new UUID(0,0)); }
        if (value.isBlank() || value.length() > 2048) { throw invalid(); }
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(value)))) {
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != (admin ? 1 : 0)) { throw invalid(); }
            long cost = input.readLong(); String name = input.readUTF(); UUID id = new UUID(input.readLong(),input.readLong());
            if (input.available() != 0 || cost < 0 || cost > 9007199254740991L || name.isBlank() || name.length() > 200 || name.indexOf('\0') >= 0) { throw invalid(); }
            return new Position(cost,name,id);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }
    static String encode(Reward reward, boolean admin) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeByte(1); output.writeByte(admin ? 1 : 0); output.writeLong(reward.getPointCost());
                output.writeUTF(reward.getName()); output.writeLong(reward.getId().getMostSignificantBits()); output.writeLong(reward.getId().getLeastSignificantBits());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException("Unable to create catalogue cursor",impossible); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
