package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.reward.service.FulfilmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/redemptions")
public class FulfilmentController {
    private final FulfilmentService fulfilments;
    public FulfilmentController(FulfilmentService fulfilments) { this.fulfilments=fulfilments; }
    @PostMapping("/{id}/fulfilments")
    public ResponseEntity<FulfilmentResponse> fulfil(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody(required=false) FulfilmentRequest body,HttpServletRequest request) {
        if(!request.getParameterMap().isEmpty()) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(fulfilments.fulfil(id,key,UUID.fromString(RequestIdFilter.requestId(request))));
    }
}
