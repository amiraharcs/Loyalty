package com.amiraharcs.loyalty.competition.api;
import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/prize-assignments")
public class AdminPrizeAssignmentController {
    private final PrizeAssignmentAdminService service;
    public AdminPrizeAssignmentController(PrizeAssignmentAdminService service) {this.service=service;}
    @GetMapping public ResponseEntity<AdminPrizePage<AdminAssignmentResponse>> list(@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) UUID customerId,@RequestParam(required=false) UUID prizeId,
            @RequestParam(required=false) CustomerPrizeStatus status,@RequestParam(required=false) PrizeTriggerType triggerType,
            @RequestParam(required=false) Instant awardedFrom,@RequestParam(required=false) Instant awardedTo,HttpServletRequest r) {
        AdminPrizeController.parameters(r,Set.of("size","cursor","customerId","prizeId","status","triggerType","awardedFrom","awardedTo"));
        return AdminPrizeController.response(service.list(new PrizeAssignmentFilter(customerId,prizeId,status,triggerType,awardedFrom,awardedTo),size,cursor));
    }
    @GetMapping("/{id}") public ResponseEntity<AdminAssignmentResponse> get(@PathVariable UUID id,HttpServletRequest r) {
        AdminPrizeController.parameters(r,Set.of());return AdminPrizeController.response(service.get(id));
    }
    @PostMapping("/{id}/revocations") public ResponseEntity<AdminAssignmentResponse> revoke(@PathVariable UUID id,@Valid @RequestBody PrizeRevocationRequest body,HttpServletRequest r) {
        AdminPrizeController.parameters(r,Set.of());return AdminPrizeController.response(service.revoke(id,body,UUID.fromString(RequestIdFilter.requestId(r))));
    }
}
