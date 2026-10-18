package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import com.amiraharcs.loyalty.reward.model.*;
import com.amiraharcs.loyalty.store.model.Store;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class RedemptionPersistenceTests extends RedemptionTestSupport {
    private RewardRedemption appendRequest(RedemptionSetup setup) {
        var account = accounts.lockForMutation(customer(setup.browser()));
        var reward = entities.find(Reward.class,setup.reward().getId());
        return redemptions.append(new RewardRedemption(account.getCustomer(),account,reward,
                entities.getReference(Store.class,setup.store().getId()),NOW));
    }
    @Test void deferredConstraintRejectsRequestWithoutDebitAtActualCommit() throws Exception {
        var setup = redemptionSetup(3000,3000);
        var failure = catchThrowable(()->new TransactionTemplate(manager).executeWithoutResult(tx->appendRequest(setup)));
        assertThat(failure).isNotNull();
        assertThat(postgres(failure).getServerErrorMessage().getConstraint()).isEqualTo("ck_redemptions_required_debit");
        nothingRedeemed(setup.browser(),3000);
    }
    @ParameterizedTest @ValueSource(strings={"missingSource","wrongAccount","wrongCost","positive","wrongActor","wrongTime","duplicate","wrongType","overspend"})
    void rawSqlCannotCommitUnmatchedDuplicateForeignOrOverdrawnDeduction(String scenario) throws Exception {
        long balance = scenario.equals("overspend") ? 2999 : 6000;
        var setup = redemptionSetup(balance,3000); var other = redemptionSetup(6000,3000);
        var otherAccount = jdbc.queryForObject("SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?",UUID.class,customer(other.browser()));
        var failure = catchThrowable(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            var redemption = appendRequest(setup);
            if(scenario.equals("duplicate")) { pointTransactions.append(PointTransaction.forRedemption(redemption)); }
            UUID account = scenario.equals("wrongAccount") ? otherAccount : redemption.getAccount().getId();
            UUID actor = scenario.equals("wrongActor") ? other.browser().userId : setup.browser().userId;
            long amount = scenario.equals("wrongCost") ? -2999 : scenario.equals("positive") ? 3000 : -3000;
            String type = scenario.equals("wrongType") ? "ADJUSTMENT" : "REDEMPTION";
            jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at,redemption_id) VALUES (?,?,?,?,?,'Synthetic constraint fixture',?,?)",
                    UUID.randomUUID(),account,amount,type,actor,Timestamp.from(scenario.equals("wrongTime")?NOW.plusSeconds(1):NOW),
                    scenario.equals("missingSource")?null:redemption.getId());
        }));
        assertThat(failure).isNotNull();
        var error = postgres(failure);
        String expected = switch(scenario) {
            case "wrongActor" -> "ck_redemption_debit_actor";
            case "duplicate" -> "uq_point_transactions_redemption_debit";
            case "wrongType" -> "ck_point_transactions_redemption_type";
            case "overspend" -> "ck_redemption_nonnegative_total";
            default -> "ck_redemption_debit_source";
        };
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(expected);
        nothingRedeemed(setup.browser(),balance); nothingRedeemed(other.browser(),6000);
    }
    @ParameterizedTest @ValueSource(strings={"foreignAccount","foreignCustomer","foreignStore","foreignReward","invalidSource","invalidStatus","zeroCost","negativeCost","unsafeCost","equalExpiry","earlierExpiry","nonfiniteExpiry","missingTime","fulfilledPending","blankBenefit"})
    void schemaRejectsInvalidSourceStateOwnershipAndSnapshotValues(String scenario) throws Exception {
        var setup = redemptionSetup(6000,3000); var other = redemptionSetup(6000,3000);
        UUID customer = customer(setup.browser());
        UUID account = jdbc.queryForObject("SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?",UUID.class,customer);
        UUID otherAccount = jdbc.queryForObject("SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?",UUID.class,customer(other.browser()));
        var values = new Object[]{UUID.randomUUID(),customer,account,setup.store().getId(),setup.reward().getId(),"POINTS","Coffee","Description",3000L,"PENDING",Timestamp.from(NOW),Timestamp.from(NOW.plus(Duration.ofHours(24))),null};
        switch(scenario) {
            case "foreignAccount" -> values[2]=otherAccount;
            case "foreignCustomer" -> values[1]=UUID.randomUUID();
            case "foreignStore" -> values[3]=UUID.randomUUID();
            case "foreignReward" -> values[4]=UUID.randomUUID();
            case "invalidSource" -> values[5]="PRIZE";
            case "invalidStatus" -> values[9]="UNKNOWN";
            case "zeroCost" -> values[8]=0L;
            case "negativeCost" -> values[8]=-1L;
            case "unsafeCost" -> values[8]=9007199254740992L;
            case "equalExpiry" -> values[11]=Timestamp.from(NOW);
            case "earlierExpiry" -> values[11]=Timestamp.from(NOW.minusSeconds(1));
            case "nonfiniteExpiry" -> values[11]=new Timestamp(org.postgresql.PGStatement.DATE_POSITIVE_INFINITY);
            case "missingTime" -> values[10]=null;
            case "fulfilledPending" -> values[12]=setup.browser().userId;
            case "blankBenefit" -> values[6]=" ";
        }
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->jdbc.update("INSERT INTO loyalty.reward_redemptions "
                +"(id,customer_id,account_id,store_id,reward_id,source,reward_name_snapshot,reward_description_snapshot,point_cost_snapshot,status,requested_at,expires_at,fulfilled_by) "
                +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",values))).isInstanceOf(DataIntegrityViolationException.class);
        nothingRedeemed(setup.browser(),6000);
    }
    @ParameterizedTest @ValueSource(strings={"UPDATE loyalty.reward_redemptions SET reward_name_snapshot='Changed' WHERE id=?",
            "UPDATE loyalty.reward_redemptions SET point_cost_snapshot=1 WHERE id=?",
            "UPDATE loyalty.reward_redemptions SET expires_at=expires_at+interval '1 hour' WHERE id=?",
            "DELETE FROM loyalty.reward_redemptions WHERE id=?",
            "UPDATE loyalty.point_transactions SET signed_amount=-1 WHERE redemption_id=?",
            "DELETE FROM loyalty.point_transactions WHERE redemption_id=?"})
    void successfulSourceHistoryAndLedgerRemainImmutable(String sql) throws Exception {
        var setup = redemptionSetup(3000,3000);
        var id = json.readValue(redeem(setup,UUID.randomUUID(),201).getResponse().getContentAsString(),com.amiraharcs.loyalty.reward.api.RedemptionResponse.class).redemptionId();
        assertThatThrownBy(()->jdbc.update(sql,id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(pending(setup.browser())).isEqualTo(1); assertThat(debits(setup.browser())).isEqualTo(1);
        assertThat(accounts.currentBalance(customer(setup.browser()))).isZero();
    }
    @Test void mappingAndRestrictiveReferencesPreserveTheOwnedRecord() throws Exception {
        var setup = redemptionSetup(3000,3000);
        var id = json.readValue(redeem(setup,UUID.randomUUID(),201).getResponse().getContentAsString(),com.amiraharcs.loyalty.reward.api.RedemptionResponse.class).redemptionId();
        var loaded = redemptions.findByIdAndCustomerId(id,customer(setup.browser())).orElseThrow();
        assertThat(loaded.getVersion()).isZero(); assertThat(loaded.getStatus()).isEqualTo(RedemptionStatus.PENDING);
        assertThat(loaded.getSource()).isEqualTo(RedemptionSource.POINTS); assertThat(loaded.getFulfilledAt()).isNull();
        assertThat(loaded.getFulfilledBy()).isNull(); assertThat(loaded.getRequestedAt()).isEqualTo(NOW);
        assertThat(redemptions.findByIdAndCustomerId(id,UUID.randomUUID())).isEmpty();
        for(String table:List.of("rewards","stores")) {
            UUID target = table.equals("rewards")?setup.reward().getId():setup.store().getId();
            assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty."+table+" WHERE id=?",target)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='loyalty.reward_redemptions'::regclass AND contype='f' AND confdeltype='r' AND confupdtype='r'",Long.class)).isEqualTo(5);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE loyalty.reward_redemptions CASCADE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(RewardRedemption.class.getMethods()).extracting(java.lang.reflect.Method::getName).noneMatch(name->name.startsWith("set")||name.startsWith("delete")||name.startsWith("cancel"));
    }
    private org.postgresql.util.PSQLException postgres(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) { if(cause instanceof org.postgresql.util.PSQLException postgres) { return postgres; } }
        throw new AssertionError("Expected a real PostgreSQL constraint failure",error);
    }
}
