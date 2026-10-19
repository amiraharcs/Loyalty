package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.leaderboard.service.LeaderboardCursor;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LeaderboardCursorTests {
    @Test void roundTripPreservesFullRankingTupleAndRejectsAlteredBytes() {
        var codec=new LeaderboardCursor("");var position=new LeaderboardCursor.Position(9007199254740991L,Instant.parse("2026-10-19T12:00:00.123456Z"),UUID.randomUUID());
        String encoded=codec.encode(position,"weekly:2026-10-19");assertThat(codec.decode(encoded,"weekly:2026-10-19")).isEqualTo(position);
        assertThatThrownBy(()->codec.decode(encoded,"all-time")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        byte[] bytes=Base64.getUrlDecoder().decode(encoded);bytes[20]^=1;
        assertThatThrownBy(()->codec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),"weekly:2026-10-19")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void configuredKeyWorksAcrossInstancesAndRotationRejectsOldTokens() {
        String key=Base64.getEncoder().encodeToString(new byte[32]);var first=new LeaderboardCursor(key);var second=new LeaderboardCursor(key);
        var position=new LeaderboardCursor.Position(0,Instant.EPOCH,UUID.randomUUID());String token=first.encode(position,"all-time");
        assertThat(second.decode(token,"all-time")).isEqualTo(position);
        assertThatThrownBy(()->new LeaderboardCursor("").decode(token,"all-time")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->new LeaderboardCursor("invalid")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsTruncatedOversizedNoncanonicalAndMalformedCursors() {
        var codec=new LeaderboardCursor("");
        for(String value:List.of(""," ","%bad", "abc=","a".repeat(513),"abc")) {
            assertThatThrownBy(()->codec.decode(value,"all-time")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(codec.decode(null,"all-time")).isNull();
    }
}
