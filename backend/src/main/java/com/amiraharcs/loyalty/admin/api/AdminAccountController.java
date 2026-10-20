package com.amiraharcs.loyalty.admin.api;

import com.amiraharcs.loyalty.admin.api.AdminAccountDtos.*;
import com.amiraharcs.loyalty.audit.service.AdminAccountReadAudit;
import com.amiraharcs.loyalty.audit.service.AdminAccountReadAudit.Action;
import com.amiraharcs.loyalty.admin.service.*;
import com.amiraharcs.loyalty.auth.service.AdminAccountManagement;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAccountController {
    private final AdminAccountQuery query;
    private final AdminAccountManagement management;
    private final AdminAccountReadAudit reads;
    public AdminAccountController(AdminAccountQuery query,AdminAccountManagement management,AdminAccountReadAudit reads) {this.query=query;this.management=management;this.reads=reads;}
    @GetMapping("/customers")
    public ResponseEntity<Page<Customer>> customers(@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) AccountStatus status,@RequestParam(required=false) UUID customerId,HttpServletRequest request) {
        parameters(request,Set.of("size","cursor","status","customerId"));return audited(query.customers(size,cursor,status,customerId),Action.ADMIN_CUSTOMERS_LIST,null,request);
    }
    @GetMapping("/customers/{id}") public ResponseEntity<Customer> customer(@PathVariable UUID id,HttpServletRequest request) {var result=query.customer(id);return audited(result,Action.ADMIN_CUSTOMER_VIEW,result.userId(),request);}
    @GetMapping("/staff")
    public ResponseEntity<Page<Staff>> staff(@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) AccountStatus status,@RequestParam(required=false) UUID userId,HttpServletRequest request) {
        parameters(request,Set.of("size","cursor","status","userId"));return audited(query.staff(size,cursor,status,userId),Action.ADMIN_STAFF_LIST,null,request);
    }
    @GetMapping("/staff/{id}") public ResponseEntity<Staff> staff(@PathVariable UUID id,HttpServletRequest request) {return audited(query.staff(id),Action.ADMIN_STAFF_VIEW,id,request);}
    @PatchMapping("/users/{id}") public ResponseEntity<UserState> status(@PathVariable UUID id,@Valid @RequestBody StatusPatch body,HttpServletRequest request) {
        return ok(management.status(id,body.status(),requestId(request)));
    }
    @PostMapping("/users/{id}/roles/{role}") public ResponseEntity<UserState> grant(@PathVariable UUID id,@PathVariable RoleCode role,@RequestBody(required=false) RoleCommandBody body,HttpServletRequest request) {
        if(body!=null) {throw new InvalidRequestException();}
        return ok(management.role(id,role,true,requestId(request)));
    }
    @DeleteMapping("/users/{id}/roles/{role}") public ResponseEntity<UserState> revoke(@PathVariable UUID id,@PathVariable RoleCode role,@RequestBody(required=false) RoleCommandBody body,HttpServletRequest request) {
        if(body!=null) {throw new InvalidRequestException();}
        return ok(management.role(id,role,false,requestId(request)));
    }
    @PostMapping("/staff-store-assignments") public ResponseEntity<Assignment> assign(@Valid @RequestBody AssignmentRequest body,HttpServletRequest request) {
        return ok(management.assign(body.staffUserId(),body.storeId(),requestId(request)));
    }
    @PatchMapping("/staff-store-assignments/{staffUserId}/{storeId}")
    public ResponseEntity<Assignment> assignment(@PathVariable UUID staffUserId,@PathVariable UUID storeId,
            @Valid @RequestBody AssignmentPatch body,HttpServletRequest request) {
        return ok(management.assignment(staffUserId,storeId,body.active(),requestId(request)));
    }
    private <T> ResponseEntity<T> audited(T body,Action action,UUID target,HttpServletRequest request) {
        reads.record(action,target,requestId(request));return ok(body);
    }
    private static UUID requestId(HttpServletRequest request) {return UUID.fromString(RequestIdFilter.requestId(request));}
    private static <T> ResponseEntity<T> ok(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    private static void parameters(HttpServletRequest request,Set<String> names) {
        if (request.getParameterMap().entrySet().stream().anyMatch(entry->!names.contains(entry.getKey()) || entry.getValue().length!=1)) {throw new InvalidRequestException();}
    }
}
