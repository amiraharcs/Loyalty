package com.amiraharcs.loyalty;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPrizeCatalogueTests extends AdminPrizeTestSupport {
    String definition() {return "{\"name\":\"Future coffee\",\"active\":false,\"benefitType\":\"REWARD\",\"benefitDescription\":\"One free coffee.\",\"rewardId\":\""+COFFEE+"\",\"triggerType\":\"STREAK_MILESTONE\"}";}
    AdminPrizeResponse create(Browser browser) throws Exception {
        return json.readValue(browser.send(post(ADMIN).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content(definition()),201).getResponse().getContentAsString(),AdminPrizeResponse.class);
    }
    AdminPrizeResponse update(Browser browser,UUID id,String body,int status) throws Exception {
        var result=browser.send(patch(ADMIN+"/"+id).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content(body),status);
        return status==200?json.readValue(result.getResponse().getContentAsString(),AdminPrizeResponse.class):null;
    }
    @Test void adminCreatesReadsUpdatesAndAuditsOnlySafeFieldNames() throws Exception {
        var admin=login(RoleCode.ADMIN);var p=create(admin);assertThat(p.version()).isZero();
        assertThat(json.readValue(admin.send(get(ADMIN+"/"+p.id()),200).getResponse().getContentAsString(),AdminPrizeResponse.class)).isEqualTo(p);
        var updated=update(admin,p.id(),"{\"expectedVersion\":0,\"name\":\"Revised future coffee\"}",200);
        assertThat(updated.name()).isEqualTo("Revised future coffee");assertThat(updated.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action IN ('PRIZE_CREATED','PRIZE_UPDATED')",Long.class,admin.userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM loyalty.audit_logs WHERE target_id=? AND action='PRIZE_UPDATED'",String.class,p.id())).contains("name").doesNotContain("Revised future coffee","password");
        update(admin,p.id(),"{\"expectedVersion\":0,\"name\":\"Stale overwrite\"}",409);
    }
    @Test void replacementRequiresDeactivationAndActivationDoesNotRewriteIssuedSnapshots() throws Exception {
        var setup=setup();days(setup,10);freshLogin(setup.customer());var snapshot=history(setup.customer(),"").items().getFirst();
        var admin=login(RoleCode.ADMIN);var replacement=create(admin);
        update(admin,STREAK_PRIZE,"{\"expectedVersion\":0,\"active\":false,\"name\":\"Old coffee definition\"}",200);
        var active=update(admin,replacement.id(),"{\"expectedVersion\":0,\"active\":true}",200);assertThat(active.active()).isTrue();
        assertThat(history(setup.customer(),"").items().getFirst()).isEqualTo(snapshot);
        for(int day=10;day<20;day++) {confirmDay(setup,day);}
        assertThat(prizeCount(setup.customer())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT prize_id FROM loyalty.customer_prizes WHERE customer_id=? ORDER BY awarded_at DESC LIMIT 1",UUID.class,customer(setup.customer()))).isEqualTo(replacement.id());
    }
    @Test void activeSlotCollisionIs409WithoutReplacingExistingDefinition() throws Exception {
        var admin=login(RoleCode.ADMIN);var p=create(admin);update(admin,p.id(),"{\"expectedVersion\":0,\"active\":true}",409);
    }
    @Test void deactivatedStreakDefinitionNeverBlocksVisitEarnAndRecordsSkip() throws Exception {
        var setup=setup();var admin=login(RoleCode.ADMIN);update(admin,STREAK_PRIZE,"{\"expectedVersion\":0,\"active\":false}",200);
        days(setup,10);assertThat(balance(setup.customer())).isEqualTo(1000);assertThat(prizeCount(setup.customer())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='STREAK_PRIZE_SKIPPED'",Long.class,setup.staff().userId)).isEqualTo(1);
    }
    @Test void triggerAndRankAreImmutableEvenWithoutAssignmentsAndUnknownRewardsAre404() throws Exception {
        var admin=login(RoleCode.ADMIN);var p=create(admin);
        update(admin,p.id(),"{\"expectedVersion\":0,\"triggerType\":\"WEEKLY_RANK\",\"weeklyRank\":4}",409);
        update(admin,p.id(),"{\"expectedVersion\":0,\"rewardId\":\""+UUID.randomUUID()+"\"}",404);
        admin.send(delete(ADMIN+"/"+p.id()).header("X-CSRF-TOKEN",admin.csrf),405);
        admin.send(get(ADMIN+"/"+UUID.randomUUID()),404);
    }
    @ParameterizedTest @ValueSource(strings={"{\"expectedVersion\":0,\"benefitValue\":50}","{\"expectedVersion\":0,\"active\":null}","{\"expectedVersion\":0,\"name\":\" \"}","{}","{\"name\":\"Missing version\"}","{\"expectedVersion\":-1,\"name\":\"Bad\"}","{\"expectedVersion\":0,\"version\":20}","{\"expectedVersion\":0,\"benefitType\":\"CASH\"}","{\"expectedVersion\":0,\"benefitValue\":2.5}","{\"expectedVersion\":0,\"benefitValue\":\"50\"}"})
    void rejectsInvalidPatches(String body) throws Exception {var admin=login(RoleCode.ADMIN);var p=create(admin);update(admin,p.id(),body,400);}
    @ParameterizedTest @ValueSource(strings={"0","101","-1","null","2.5","\"50\""})
    void percentageMustBeExactBoundedInteger(String percentage) throws Exception {
        var admin=login(RoleCode.ADMIN);String body="{\"name\":\"Discount\",\"active\":false,\"benefitType\":\"PERCENT_DISCOUNT\",\"benefitDescription\":\"Coffee discount\",\"benefitValue\":"+percentage+",\"triggerType\":\"WEEKLY_RANK\",\"weeklyRank\":2}";
        admin.send(post(ADMIN).header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content(body),400);
    }
    @Test void weeklyDefinitionCanChangeFutureBenefitTypeWithExplicitNullClears() throws Exception {
        var admin=login(RoleCode.ADMIN);
        var p=update(admin,UUID.fromString("f0330000-0000-4000-8000-000000000002"),"{\"expectedVersion\":0,\"benefitType\":\"REWARD\",\"benefitValue\":null,\"rewardId\":\""+COFFEE+"\"}",200);
        assertThat(p.benefitValue()).isNull();assertThat(p.rewardId()).isEqualTo(COFFEE);
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","STAFF"})
    void nonAdminCannotReadOrMutate(String role) throws Exception {
        var browser=login(RoleCode.valueOf(role));browser.send(get(ADMIN),403);browser.send(get(ADMIN+"/"+STREAK_PRIZE),403);
        browser.send(post(ADMIN).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content(definition()),403);
        browser.send(patch(ADMIN+"/"+STREAK_PRIZE).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content("{\"expectedVersion\":0,\"active\":false}"),403);
    }
    @Test void anonymousDisabledRevokedAndMissingCsrfAreDenied() throws Exception {
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());var admin=login(RoleCode.ADMIN);
        admin.send(post(ADMIN).contentType("application/json").content(definition()),403);
        admin.send(patch(ADMIN+"/"+STREAK_PRIZE).contentType("application/json").content("{\"expectedVersion\":0,\"active\":false}"),403);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",admin.userId);admin.send(get(ADMIN),401);
        var revoked=login(RoleCode.ADMIN);jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);revoked.send(get(ADMIN),403);
    }
    @Test void cataloguePaginationIsDeterministicAndRejectsUnknownQueries() throws Exception {
        var admin=login(RoleCode.ADMIN);var first=json.readTree(admin.send(get(ADMIN+"?size=2"),200).getResponse().getContentAsString());
        var next=json.readTree(admin.send(get(ADMIN).param("size","2").param("cursor",first.get("nextCursor").asText()),200).getResponse().getContentAsString());
        assertThat(next.get("items").get(0).get("id").asText()).isNotEqualTo(first.get("items").get(0).get("id").asText());
        for(String query:List.of("size=0","size=101","cursor=bad","size=1&size=2","sort=name")) {admin.send(get(ADMIN+"?"+query),400);}
    }
}
