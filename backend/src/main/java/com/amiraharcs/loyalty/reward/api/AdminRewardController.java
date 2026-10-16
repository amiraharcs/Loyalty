package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.service.RewardService;
import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/rewards")
public class AdminRewardController {
    private final RewardService rewards;
    public AdminRewardController(RewardService rewards) { this.rewards = rewards; }
    @GetMapping
    public ResponseEntity<RewardPage<AdminRewardResponse>> catalogue(@RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        RewardController.validateQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rewards.adminCatalogue(size,cursor));
    }
    @GetMapping("/{id}")
    public ResponseEntity<AdminRewardResponse> reward(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rewards.adminReward(id));
    }
    @PostMapping
    public ResponseEntity<AdminRewardResponse> create(@Valid @RequestBody RewardRequest body, HttpServletRequest request) {
        var response = rewards.create(body,UUID.fromString(RequestIdFilter.requestId(request)));
        return ResponseEntity.created(URI.create("/api/v1/admin/rewards/"+response.id())).cacheControl(CacheControl.noStore()).body(response);
    }
    @PatchMapping("/{id}")
    public ResponseEntity<AdminRewardResponse> update(@PathVariable UUID id, @RequestBody RewardPatchRequest body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(rewards.update(id,body,UUID.fromString(RequestIdFilter.requestId(request))));
    }
}
