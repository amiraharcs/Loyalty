package com.amiraharcs.loyalty.admin.api;

import com.amiraharcs.loyalty.admin.api.OperationalReportDtos.*;
import com.amiraharcs.loyalty.admin.service.OperationalReportService;
import com.amiraharcs.loyalty.common.api.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class OperationalReportController {
    private final OperationalReportService service;
    public OperationalReportController(OperationalReportService service) {this.service=service;}
    @GetMapping("/overview") public ResponseEntity<Overview> overview(HttpServletRequest request) {filters(request,Set.of());return ok(service.overview());}
    @GetMapping("/visits") public ResponseEntity<Visits> visits(@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) UUID storeId,HttpServletRequest request) {
        filters(request,Set.of("from","to","storeId"));return ok(service.visits(from,to,storeId));
    }
    @GetMapping("/points") public ResponseEntity<Points> points(@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) UUID storeId,HttpServletRequest request) {
        filters(request,Set.of("from","to","storeId"));return ok(service.points(from,to,storeId));
    }
    @GetMapping("/redemptions") public ResponseEntity<Redemptions> redemptions(@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) UUID storeId,HttpServletRequest request) {
        filters(request,Set.of("from","to","storeId"));return ok(service.redemptions(from,to,storeId));
    }
    @GetMapping("/game") public ResponseEntity<Game> game(@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        filters(request,Set.of("from","to"));return ok(service.game(from,to));
    }
    @GetMapping("/competition") public ResponseEntity<Competition> competition(@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        filters(request,Set.of("from","to"));return ok(service.competition(from,to));
    }
    private static void filters(HttpServletRequest request,Set<String> allowed) {
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().length!=1)) {throw new InvalidRequestException();}
    }
    private static <T> ResponseEntity<T> ok(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
