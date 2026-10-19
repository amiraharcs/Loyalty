package com.amiraharcs.loyalty;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PrizePersistenceTests extends PrizeTestSupport {
    private void rejected(String sql,String state) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            var savepoint=connection.setSavepoint();
            try(var statement=connection.createStatement()) {
                assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(SQLException.class)
                        .satisfies(error->assertThat(((SQLException)error).getSQLState()).isEqualTo(state));
            } finally {connection.rollback(savepoint);connection.releaseSavepoint(savepoint);}
            return null;
        });
    }
    @Test void productionCatalogueContainsOnlyFourWeeklyBenefitsAndOneStreakDefinition() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.prizes",Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT weekly_rank FROM loyalty.prizes WHERE trigger_type='WEEKLY_RANK' ORDER BY weekly_rank",Integer.class)).containsExactly(1,2,3,4);
        assertThat(jdbc.queryForObject("SELECT reward_id FROM loyalty.prizes WHERE trigger_type='STREAK_MILESTONE'",UUID.class)).isEqualTo(COFFEE);
        assertThat(jdbc.queryForList("SELECT benefit_value FROM loyalty.prizes WHERE benefit_type='PERCENT_DISCOUNT' ORDER BY weekly_rank",Integer.class)).containsExactly(50,25,10);
    }
    @ParameterizedTest @ValueSource(strings={"benefit_type='CASH'","trigger_type='ARBITRARY'","benefit_value=0","benefit_value=101","benefit_value=NULL","reward_id='e83bb76e-b4bf-470b-a3cc-5fc67495f162'","weekly_rank=NULL","weekly_rank=0","name=' '","version=-1","updated_at=created_at-interval '1 second'"})
    void rejectsInvalidDiscountAndCatalogueDefinitions(String assignment) {
        rejected("UPDATE loyalty.prizes SET "+assignment+" WHERE weekly_rank=2","23514");
    }
    @Test void uniqueDefinitionsAndRestrictiveRewardForeignKeyAreEnforced() {
        rejected("UPDATE loyalty.prizes SET weekly_rank=1 WHERE weekly_rank=2","23505");
        rejected("DELETE FROM loyalty.rewards WHERE id='"+COFFEE+"'","23503");
        rejected("UPDATE loyalty.prizes SET reward_id='00000000-0000-0000-0000-000000000000' WHERE id='"+STREAK_PRIZE+"'","23503");
    }
    @Test void entitlementHistoryCannotBeUpdatedDeletedOrTruncated() throws Exception {
        var setup=setup();days(setup,10);
        rejected("UPDATE loyalty.customer_prizes SET status='REDEEMED'","23514");
        rejected("UPDATE loyalty.customer_prizes SET benefit_description_snapshot='Rewritten'","23514");
        rejected("DELETE FROM loyalty.customer_prizes","23514");
        rejected("TRUNCATE loyalty.customer_prizes","23514");
        rejected("DELETE FROM loyalty.prizes WHERE id='"+STREAK_PRIZE+"'","23503");
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","foreign-customer","missing-visit","weekly-source","no-source","wrong-benefit","invalid-expiry","invalid-status","reserved","invalid-reward"})
    void invalidOrDuplicateEntitlementSourcesCannotCommit(String mode) throws Exception {
        var setup=setup();days(setup,10);var existing=jdbc.queryForMap("SELECT * FROM loyalty.customer_prizes WHERE customer_id=?",customer(setup.customer()));
        UUID nextVisit=confirmDay(setup,10);
        UUID other=customer(login(com.amiraharcs.loyalty.auth.model.RoleCode.CUSTOMER));
        String customer=mode.equals("foreign-customer")?"'"+other+"'":"customer_id";
        String visit=mode.equals("missing-visit")?"'"+UUID.randomUUID()+"'":mode.equals("no-source")?"NULL":mode.equals("duplicate") || mode.equals("foreign-customer")?"trigger_visit_id":"'"+nextVisit+"'";
        String prize=mode.equals("weekly-source")?"'f0330000-0000-4000-8000-000000000001'":"prize_id";
        String trigger=mode.equals("weekly-source")?"'WEEKLY_RANK'":"trigger_type";
        String benefit=mode.equals("wrong-benefit")?"'PERCENT_DISCOUNT'":"benefit_type";
        String reward=mode.equals("invalid-reward")?"'"+UUID.randomUUID()+"'":"reward_id";
        String expiry=mode.equals("invalid-expiry")?"awarded_at":"expires_at";
        String status=mode.equals("invalid-status")?"'INVALID'":mode.equals("reserved")?"'RESERVED'":"status";
        String sql="INSERT INTO loyalty.customer_prizes(id,customer_id,prize_id,trigger_type,trigger_visit_id,prize_name_snapshot,benefit_type,benefit_description_snapshot,benefit_value,reward_id,reward_name_snapshot,reward_description_snapshot,status,awarded_at,expires_at) SELECT '"+UUID.randomUUID()+"',"+customer+","+prize+","+trigger+","+visit+",prize_name_snapshot,"+benefit+",benefit_description_snapshot,benefit_value,"+reward+",reward_name_snapshot,reward_description_snapshot,"+status+",awarded_at,"+expiry+" FROM loyalty.customer_prizes WHERE id='"+existing.get("id")+"'";
        rejected(sql,mode.equals("duplicate")?"23505":Set.of("foreign-customer","missing-visit","invalid-reward").contains(mode)?"23503":"23514");
    }
}
