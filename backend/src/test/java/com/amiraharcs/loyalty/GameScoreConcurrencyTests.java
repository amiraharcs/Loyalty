package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class GameScoreConcurrencyTests extends GameScoreTestSupport {
    @ParameterizedTest @ValueSource(strings={"same-score","different-score","same-key"})
    void concurrentHttpSubmissionsPermanentlyAcceptOnlyOneScore(String mode) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var ready=new CountDownLatch(2);var begin=new CountDownLatch(1);var key=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(attempt(copy(browser),run.sessionId(),50,key,ready,begin));
            var b=pool.submit(attempt(copy(browser),run.sessionId(),mode.equals("different-score")?60:50,mode.equals("same-key")?key:UUID.randomUUID(),ready,begin));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();begin.countDown();var one=a.get(20,TimeUnit.SECONDS);var two=b.get(20,TimeUnit.SECONDS);
            assertThat(java.util.List.of(one.getResponse().getStatus(),two.getResponse().getStatus())).containsExactlyInAnyOrder(201,mode.equals("different-score")?409:mode.equals("same-key")?201:200);
            if(mode.equals("different-score")) {assertThat(code(one.getResponse().getStatus()==409?one:two)).isEqualTo("SCORE_ALREADY_SUBMITTED");}
            else {assertThat(score(one)).isEqualTo(score(two));}
        } finally {begin.countDown();}
        assertThat(scoreRows(run.sessionId())).isEqualTo(1);assertThat(state(run.sessionId())).isEqualTo("COMPLETED");untouchedLife(browser);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE action='GAME_SCORE_ACCEPTED' AND actor_user_id=?",Long.class,browser.userId)).isEqualTo(1);
    }
    private Callable<org.springframework.test.web.servlet.MvcResult> attempt(Browser browser,UUID id,long value,UUID key,CountDownLatch ready,CountDownLatch begin) {
        return ()->{ready.countDown();if(!begin.await(10,TimeUnit.SECONDS)) {throw new IllegalStateException("Race did not start");}
            return mvc.perform(post(scorePath(id)).cookie(browser.cookies.values().toArray(jakarta.servlet.http.Cookie[]::new))
                    .header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",key).contentType("application/json").content("{\"score\":"+value+"}")).andReturn();};
    }
}
