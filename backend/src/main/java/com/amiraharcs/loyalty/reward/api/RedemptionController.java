package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.reward.service.RedemptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/me/redemptions")
public class RedemptionController {
    private final RedemptionService redemptions;
    public RedemptionController(RedemptionService redemptions) { this.redemptions=redemptions; }
    @PostMapping
    public ResponseEntity<RedemptionResponse> redeem(@Valid @RequestBody RedemptionRequest body,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) {
        var response=redemptions.redeem(body,key,UUID.fromString(RequestIdFilter.requestId(request)));
        return ResponseEntity.created(URI.create("/api/v1/customers/me/redemptions/"+response.redemptionId()))
                .cacheControl(CacheControl.noStore()).body(response);
    }
    @GetMapping("/{id}")
    public ResponseEntity<RedemptionDetails> details(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(redemptions.details(id));
    }
}
