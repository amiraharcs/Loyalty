package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.reward.model.Reward;
import com.amiraharcs.loyalty.reward.service.RewardService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class RewardTestSupport extends StaffQrTestSupport {
    static final String ADMIN = "/api/v1/admin/rewards";
    static final String CATALOGUE = "/api/v1/rewards";
    @Autowired RewardService rewards;
    @Autowired EntityManager entities;
    Reward fixture(String name, long cost, boolean active, Instant from, Instant until) {
        return new TransactionTemplate(manager).execute(tx -> {
            var reward = new Reward(name,"Synthetic catalogue description",cost,active,from,until,NOW);
            entities.persist(reward); entities.flush(); return reward;
        });
    }
    String body(String name,long cost) throws Exception {
        return json.writeValueAsString(Map.of("name",name,"description","Synthetic description","pointCost",cost,"active",true));
    }
    JsonNode create(Browser admin,String body,int status) throws Exception {
        return json.readTree(admin.send(post(ADMIN).header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content(body),status).getResponse().getContentAsString());
    }
    JsonNode patch(Browser admin,UUID id,String body,int status) throws Exception {
        return json.readTree(admin.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(ADMIN+"/"+id)
                .header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content(body),status).getResponse().getContentAsString());
    }
    List<JsonNode> all(Browser browser,String path,int size) throws Exception {
        var items = new ArrayList<JsonNode>(); String cursor = null;
        for (int page=0;page<100;page++) {
            var request = get(path).param("size",String.valueOf(size));
            if(cursor!=null) { request.param("cursor",cursor); }
            var response = json.readTree(browser.send(request,200).getResponse().getContentAsString());
            response.get("items").forEach(items::add);
            if (!response.get("hasNext").asBoolean()) { return items; }
            cursor = response.get("nextCursor").asText();
        }
        throw new AssertionError("Catalogue cursor did not terminate");
    }
}
