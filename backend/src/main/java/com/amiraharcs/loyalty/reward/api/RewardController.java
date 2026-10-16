package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.service.RewardService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/rewards")
public class RewardController {
    private final RewardService rewards;
    public RewardController(RewardService rewards) { this.rewards = rewards; }
    @GetMapping
    public ResponseEntity<RewardPage<RewardResponse>> catalogue(@RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        validateQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rewards.catalogue(size,cursor));
    }
    static void validateQuery(HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !Set.of("size","cursor").contains(entry.getKey()) || entry.getValue().length != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
