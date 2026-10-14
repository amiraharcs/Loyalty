package com.amiraharcs.loyalty.qr.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.qr.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff")
public class StaffQrController {
    private final StaffQrService service;
    public StaffQrController(StaffQrService service) { this.service=service; }
    @PostMapping("/scans")
    public ResponseEntity<StaffScanResponse> scan(@Valid @RequestBody StaffScanRequest body,HttpServletRequest request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.scan(body,requestId(request),request.getRemoteAddr()));
    }
    @PostMapping("/visits")
    public ResponseEntity<StaffVisitResponse> confirm(@Valid @RequestBody VisitConfirmationRequest body,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.confirm(body,key,requestId(request),request.getRemoteAddr()));
    }
    private static UUID requestId(HttpServletRequest request) { return UUID.fromString(RequestIdFilter.requestId(request)); }
}
