package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.game.api.*;
import com.amiraharcs.loyalty.game.service.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class GameScoreTestSupport extends GameSessionTestSupport {
    @Autowired GameScoreService scoring;
    @Autowired ScoreRateLimiter scoreLimiter;
    @Autowired GameScoreQueryService scoreQueries;
    String scorePath(UUID id) {return SESSIONS+"/"+id+"/scores";}
    MvcResult submit(Browser browser,UUID session,long value,UUID key,int expected) throws Exception {
        return scoreBody(browser,session,"{\"score\":"+value+"}",key,expected);
    }
    MvcResult scoreBody(Browser browser,UUID session,String body,UUID key,int expected) throws Exception {
        var request=post(scorePath(session)).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content(body);
        if(key!=null) {request.header("Idempotency-Key",key);}
        return browser.send(request,expected);
    }
    ScoreResponse score(MvcResult result) throws Exception {return json.readValue(result.getResponse().getContentAsString(),ScoreResponse.class);}
    long scoreRows(UUID session) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.game_scores WHERE session_id=?",Long.class,session);}
    String state(UUID session) {return jdbc.queryForObject("SELECT status FROM loyalty.game_sessions WHERE id=?",String.class,session);}
    long rejections(UUID actor,String reason) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.game_submission_events WHERE actor_user_id=? AND reason_code=?",Long.class,actor,reason);}
    long scoreRetries(UUID actor) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND operation='CUSTOMER_GAME_SCORE'",Long.class,actor);}
    Long best(Browser browser) throws Exception {
        var response=json.readTree(browser.send(get("/api/v1/customers/me/game-summary"),200).getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(1);return response.get("personalBest").isNull()?null:response.get("personalBest").asLong();
    }
    void untouchedLife(Browser browser) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id IN (SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?)",Long.class,customer(browser))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.reward_redemptions WHERE customer_id=?",Long.class,customer(browser))).isZero();
        assertThat(consumed(customer(browser))).isEqualTo(1);assertThat(version(customer(browser))).isEqualTo(1);unchanged(customer(browser));
    }
}
