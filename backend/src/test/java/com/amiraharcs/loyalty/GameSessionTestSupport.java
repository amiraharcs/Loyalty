package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.game.api.GameSessionResponse;
import com.amiraharcs.loyalty.game.service.GameSessionService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class GameSessionTestSupport extends GameLifeTestSupport {
    static final String SESSIONS = "/api/v1/customers/me/game-sessions";
    @Autowired GameSessionService sessions;
    MvcResult startHttp(Browser browser, UUID key, String body, int expected) throws Exception {
        var request=post(SESSIONS).header("X-CSRF-TOKEN",browser.csrf);
        if(key!=null) {request.header("Idempotency-Key",key);}
        if(body!=null) {request.contentType("application/json").content(body);}
        return browser.send(request,expected);
    }
    GameSessionResponse start(Browser browser) throws Exception {
        return json.readValue(startHttp(browser,UUID.randomUUID(),null,201).getResponse().getContentAsString(),GameSessionResponse.class);
    }
    GameSessionResponse recovery(Browser browser,UUID id) throws Exception {
        return json.readValue(browser.send(get(SESSIONS+"/"+id),200).getResponse().getContentAsString(),GameSessionResponse.class);
    }
    long sessionRows(UUID owner) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.game_sessions WHERE customer_id=?",Long.class,owner);}
    long activeRows(UUID owner) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.game_sessions WHERE customer_id=? AND status='ACTIVE'",Long.class,owner);}
    long starts(UUID actor) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='GAME_SESSION_STARTED'",Long.class,actor);}
    long retries(UUID actor) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND operation='CUSTOMER_GAME_START'",Long.class,actor);}
    Browser copy(Browser browser) {
        var copy=new Browser();copy.cookies.putAll(browser.cookies);copy.userId=browser.userId;copy.csrf=browser.csrf;return copy;
    }
    void noStart(Browser browser) {
        var owner=customer(browser);assertThat(sessionRows(owner)).isZero();assertThat(consumed(owner)).isZero();
        assertThat(starts(browser.userId)).isZero();assertThat(retries(browser.userId)).isZero();assertThat(version(owner)).isZero();
    }
}
