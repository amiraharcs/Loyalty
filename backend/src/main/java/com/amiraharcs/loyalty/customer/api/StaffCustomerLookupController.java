package com.amiraharcs.loyalty.customer.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.customer.service.StaffCustomerLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/customers")
public class StaffCustomerLookupController {
    private final StaffCustomerLookupService service;
    public StaffCustomerLookupController(StaffCustomerLookupService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<StaffCustomerLookupResponse> lookup(@RequestParam(required = false) String identifier,
            @RequestParam(required = false) String storeId, HttpServletRequest request) {
        // Parse inside the service so malformed attempts also consume a budget and leave safe evidence.
        boolean validQuery = request.getParameterMap().entrySet().stream().allMatch(entry ->
                (entry.getKey().equals("identifier") || entry.getKey().equals("storeId")) && entry.getValue().length == 1);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(identifier, storeId,
                UUID.fromString(RequestIdFilter.requestId(request)), request.getRemoteAddr(), validQuery));
    }
}
