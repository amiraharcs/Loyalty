package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class GameSessionConcurrencyTests extends GameSessionTestSupport {
    @ParameterizedTest @ValueSource(ints={1,3})
    void competingHttpStartsCreateExactlyOneRunAndConsumeOneLife(int available) throws Exception {
        var browser=login(RoleCode.CUSTOMER);for(int i=available;i<3;i++) {consume(browser);}
        var owner=customer(browser);long before=consumed(owner);var ready=new CountDownLatch(2);var begin=new CountDownLatch(1);
        var first=copy(browser);var second=copy(browser);
        try(var pool=Executors.newFixedThreadPool(2)) {
            // Either worker may win, so collect raw responses instead of prescribing success per thread.
            Callable<org.springframework.test.web.servlet.MvcResult> a=attempt(first,ready,begin,UUID.randomUUID());
            Callable<org.springframework.test.web.servlet.MvcResult> b=attempt(second,ready,begin,UUID.randomUUID());
            var one=pool.submit(a);var two=pool.submit(b);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();begin.countDown();
            var responses=java.util.List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
            assertThat(responses).extracting(r->r.getResponse().getStatus()).containsExactlyInAnyOrder(201,409);
            assertThat(code(responses.stream().filter(r->r.getResponse().getStatus()==409).findFirst().orElseThrow())).isEqualTo("GAME_SESSION_ACTIVE");
        } finally {begin.countDown();}
        assertThat(sessionRows(owner)).isEqualTo(before+1);assertThat(activeRows(owner)).isEqualTo(1);assertThat(consumed(owner)).isEqualTo(before+1);
        assertThat(starts(browser.userId)).isEqualTo(1);assertThat(retries(browser.userId)).isEqualTo(1);
    }
    private Callable<org.springframework.test.web.servlet.MvcResult> attempt(Browser browser,CountDownLatch ready,CountDownLatch begin,UUID key) {
        return ()->{ready.countDown();if(!begin.await(10,TimeUnit.SECONDS)) {throw new IllegalStateException("Race did not start");}
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(SESSIONS)
                    .cookie(browser.cookies.values().toArray(jakarta.servlet.http.Cookie[]::new))
                    .header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",key)).andReturn();};
    }
    @org.junit.jupiter.api.Test void simultaneousSameKeyStartsReturnTheSameCreation() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();var ready=new CountDownLatch(2);var begin=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(attempt(copy(browser),ready,begin,key));var two=pool.submit(attempt(copy(browser),ready,begin,key));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();begin.countDown();
            var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);
            assertThat(a.getResponse().getStatus()).isEqualTo(201);assertThat(b.getResponse().getStatus()).isEqualTo(201);
            assertThat(a.getResponse().getContentAsString()).isEqualTo(b.getResponse().getContentAsString());
            assertThat(a.getResponse().getHeader("Location")).isEqualTo(b.getResponse().getHeader("Location"));
        } finally {begin.countDown();}
        assertThat(sessionRows(customer(browser))).isEqualTo(1);assertThat(consumed(customer(browser))).isEqualTo(1);
        assertThat(starts(browser.userId)).isEqualTo(1);assertThat(retries(browser.userId)).isEqualTo(1);
    }
}
