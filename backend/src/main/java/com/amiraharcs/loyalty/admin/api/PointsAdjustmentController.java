package com.amiraharcs.loyalty.admin.api;

import com.amiraharcs.loyalty.admin.service.PointsAdjustmentService;
import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/customers")
public class PointsAdjustmentController {
    private final PointsAdjustmentService adjustments;
    public PointsAdjustmentController(PointsAdjustmentService adjustments) { this.adjustments = adjustments; }
    @PostMapping("/{id}/points-adjustments")
    public ResponseEntity<PointsAdjustmentResponse> adjust(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody PointsAdjustmentRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store")
                .body(adjustments.adjust(id, body, key, UUID.fromString(RequestIdFilter.requestId(request))));
    }
}
