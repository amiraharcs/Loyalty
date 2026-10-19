package com.amiraharcs.loyalty;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffCustomerLookupSummaryTests extends StaffCustomerLookupTestSupport {
    @ParameterizedTest @CsvSource({"0,7","1,7","2,0","-1,0"})
    void displayedStreakUsesBusinessDatesWithoutMutation(int daysAgo,int expected) throws Exception {
        var s=lookupSetup();fund(s.customer(),2450);
        jdbc.update("UPDATE loyalty.streaks SET current_count=7,longest_count=9,last_qualifying_date=? WHERE customer_id=?",
                com.amiraharcs.loyalty.visit.model.Visit.businessDateAt(NOW).minusDays(daysAgo),s.identifier());
        var before=state(s.identifier());var result=lookup(s,200);var body=json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("balance").asLong()).isEqualTo(2450);assertThat(body.get("currentStreak").asInt()).isEqualTo(expected);
        assertThat(state(s.identifier())).isEqualTo(before);
    }
    @Test void londonMidnightProjectsStreakInSummerTime() throws Exception {
        var s=lookupSetup();when(clock.instant()).thenReturn(Instant.parse("2026-10-14T23:30:00Z"));
        jdbc.update("UPDATE loyalty.streaks SET current_count=3,longest_count=3,last_qualifying_date='2026-10-13' WHERE customer_id=?",s.identifier());
        // Oct 15 in London; Oct 13 is no longer yesterday. Fresh login avoids the unrelated session idle timeout.
        var staff=login(com.amiraharcs.loyalty.auth.model.RoleCode.STAFF);assign(staff,s.store());
        var result=lookup(staff,s.identifier().toString(),s.store().getId().toString(),200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("currentStreak").asInt()).isZero();
    }
    @Test void pendingRedemptionsAreLimitedToThisCustomerAndStoreAndPreserveSnapshots() throws Exception {
        var s=lookupSetup();fund(s.customer(),100);var reward=offer();
        var id=redeem(s.customer(),reward,s.store().getId());
        var other=store(s.staff());var foreign=redeem(s.customer(),reward,other.getId());
        var another=login(com.amiraharcs.loyalty.auth.model.RoleCode.CUSTOMER);fund(another,100);
        var unrelated=redeem(another,reward,s.store().getId());
        jdbc.update("UPDATE loyalty.rewards SET name='Changed catalogue',description='Changed description' WHERE id=?",reward);
        var before=state(s.identifier());var result=lookup(s,200);var body=json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("balance").asLong()).isEqualTo(98);assertThat(body.get("pendingRedemptions").size()).isEqualTo(1);
        var pending=body.get("pendingRedemptions").get(0);
        assertThat(pending.get("redemptionId").asText()).isEqualTo(id.toString());
        assertThat(pending.get("rewardName").asText()).isEqualTo("Lookup coffee");
        assertThat(pending.get("rewardDescription").asText()).isEqualTo("Immutable service benefit");
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        assertThat(pending.get("expiresAt").asText()).isEqualTo(NOW.plus(Duration.ofHours(24)).toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain(foreign.toString(),unrelated.toString(),other.getId().toString(),"Changed catalogue");
        assertThat(state(s.identifier())).isEqualTo(before);
    }
    @Test void expiredAndFulfilledRedemptionsAreNotServicePending() throws Exception {
        var s=lookupSetup();fund(s.customer(),100);var reward=offer();
        var fulfilled=redeem(s.customer(),reward,s.store().getId());
        s.staff().send(post("/api/v1/staff/redemptions/"+fulfilled+"/fulfilments").header("X-CSRF-TOKEN",s.staff().csrf)
                .header("Idempotency-Key",UUID.randomUUID()),200);
        redeem(s.customer(),reward,s.store().getId());
        // Exact expiry: unchanged DB status PENDING still must be hidden by the server clock.
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(24)));
        var fresh=login(com.amiraharcs.loyalty.auth.model.RoleCode.STAFF);assign(fresh,s.store());
        var before=state(s.identifier());var result=lookup(fresh,s.identifier().toString(),s.store().getId().toString(),200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("pendingRedemptions").size()).isZero();
        assertThat(state(s.identifier())).isEqualTo(before);
    }
    @Test void boundedPendingSummaryHasAnExplicitMoreFlag() throws Exception {
        var s=lookupSetup();fund(s.customer(),100);var reward=offer();
        for(int i=0;i<6;i++) {redeem(s.customer(),reward,s.store().getId());}
        var result=lookup(s,200);var body=json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("pendingRedemptions").size()).isEqualTo(5);
        assertThat(body.get("hasMorePendingRedemptions").asBoolean()).isTrue();
    }
    @Test void summaryUsesExactlyTwoPreparedQueriesRegardlessOfPendingCount() throws Exception {
        var s=lookupSetup();fund(s.customer(),100);var reward=offer();
        for(int i=0;i<6;i++) {redeem(s.customer(),reward,s.store().getId());}
        var logger=(Logger)LoggerFactory.getLogger("org.springframework.jdbc.core.JdbcTemplate");
        Level previous=logger.getLevel();
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);logger.setLevel(Level.DEBUG);
        try {
            var reads=new org.springframework.transaction.support.TransactionTemplate(manager);reads.setReadOnly(true);
            var result=reads.execute(tx->queryBean.find(s.identifier(),s.store().getId(),NOW));
            assertThat(result.pendingRedemptions()).hasSize(5);
            assertThat(appender.list.stream().map(event->event.getFormattedMessage())
                    .filter(message->message.startsWith("Executing prepared SQL statement ["))).hasSize(2);
        } finally {logger.setLevel(previous);logger.detachAppender(appender);appender.stop();}
    }
    @org.springframework.beans.factory.annotation.Autowired
    com.amiraharcs.loyalty.customer.service.CustomerServiceLookupQuery queryBean;

    @Test void serializedAllowlistContainsNoIdentityOrUnrelatedHistory() throws Exception {
        var s=lookupSetup();var text=lookup(s,200).getResponse().getContentAsString();
        assertThat(text).doesNotContain("email","canonicalEmail","password","passwordHash","roles","session","version",
                "userId","customerId","accountId","transactions","visits","game","prizes",s.identifier().toString(),s.customer().userId.toString(),s.store().getId().toString());
        assertThat(json.readTree(text).propertyNames()).containsExactlyInAnyOrder("displayName","balance","currentStreak","pendingRedemptions","hasMorePendingRedemptions");
    }
    @Test void rawQueryIsAbsentFromFrameworkDebugLogsAndFailureAudit(CapturedOutput output) throws Exception {
        var s=lookupSetup();var logger=(Logger)LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet");
        Level previous=logger.getLevel();String raw="private-lookup-value@example.org";int offset=output.getAll().length();
        try {logger.setLevel(Level.DEBUG);var result=lookup(s.staff(),raw,s.store().getId().toString(),400);
            assertThat(event(result).toString()).doesNotContain(raw);
            assertThat(output.getAll().substring(offset)).doesNotContain(raw,"identifier=");
        } finally {logger.setLevel(previous);}
    }
    @Test void lookupDoesNotConsumeExistingQrOrScanResolution() throws Exception {
        var s=setup();var resolution=scan(s);var id=customer(s.customer());var before=state(id);
        lookup(s.staff(),id.toString(),s.store().getId().toString(),200);assertThat(state(id)).isEqualTo(before);
        confirmHttp(s.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);awardedOnce(s);
    }
    @Test void auditFailurePreventsDisclosureAndDoesNotChangeBusinessState() throws Exception {
        var s=lookupSetup();var before=state(s.identifier());
        var target=org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.audit.service.CustomerLookupAudit>getUltimateTargetObject(lookupAudit);
        doThrow(new IllegalStateException("private internal database detail")).when(target).record(any(),any(),any(),any(),any());
        try {var result=lookup(s,500);assertThat(result.getResponse().getContentAsString()).doesNotContain("private internal database detail","Private synthetic member");
            assertThat(state(s.identifier())).isEqualTo(before);
        } finally {reset(lookupAudit);}
    }
}
