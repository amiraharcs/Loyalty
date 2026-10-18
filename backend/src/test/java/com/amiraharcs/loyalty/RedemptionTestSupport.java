package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.reward.api.RedemptionRequest;
import com.amiraharcs.loyalty.reward.model.*;
import com.amiraharcs.loyalty.reward.repository.RewardRedemptionRepository;
import com.amiraharcs.loyalty.reward.service.RedemptionService;
import com.amiraharcs.loyalty.store.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class RedemptionTestSupport extends RewardTestSupport {
    static final String REDEMPTIONS = "/api/v1/customers/me/redemptions";
    @Autowired RedemptionService redemptionService;
    @Autowired RewardRedemptionRepository redemptions;
    void fund(Browser browser,long amount) {
        if (amount == 0) { return; }
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var account = accounts.lockForMutation(customer(browser));
            pointTransactions.append(new PointTransaction(account,amount,PointTransactionType.ADJUSTMENT,
                    users.findById(browser.userId).orElseThrow(),"Synthetic test starting balance",clock.instant()));
        });
    }
    Store shop(StoreStatus status) { return stores.save(new Store("Synthetic redemption shop","Synthetic address",status,clock.instant())); }
    RedemptionSetup redemptionSetup(long balance,long cost) throws Exception {
        var customer = login(RoleCode.CUSTOMER); fund(customer,balance);
        var reward = new TransactionTemplate(manager).execute(tx->{
            var offer = new Reward("Coffee","Synthetic catalogue description",cost,true,null,null,clock.instant());
            entities.persist(offer); entities.flush(); return offer;
        });
        return new RedemptionSetup(customer,reward,shop(StoreStatus.ACTIVE));
    }
    record RedemptionSetup(Browser browser,Reward reward,Store store) {
        RedemptionRequest request() { return new RedemptionRequest(reward.getId(),store.getId()); }
    }
    String payload(RedemptionSetup setup) throws Exception { return json.writeValueAsString(setup.request()); }
    MvcResult redeem(Browser browser,String body,UUID key,int expected) throws Exception {
        return browser.send(post(REDEMPTIONS).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",key)
                .header("X-Request-ID","untrusted").header("X-Client-Time","2099-01-01T00:00:00Z")
                .contentType("application/json").content(body),expected);
    }
    MvcResult redeem(RedemptionSetup setup,UUID key,int expected) throws Exception { return redeem(setup.browser(),payload(setup),key,expected); }
    long pending(Browser browser) { return jdbc.queryForObject("SELECT count(*) FROM loyalty.reward_redemptions WHERE customer_id=?",Long.class,customer(browser)); }
    long debits(Browser browser) {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=? AND t.type='REDEMPTION'",Long.class,customer(browser));
    }
    void nothingRedeemed(Browser browser,long balance) {
        assertThat(pending(browser)).isZero(); assertThat(debits(browser)).isZero();
        assertThat(accounts.currentBalance(customer(browser))).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='REWARD_REDEEMED'",Long.class,browser.userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND operation='CUSTOMER_REWARD_REDEMPTION'",Long.class,browser.userId)).isZero();
    }
}
