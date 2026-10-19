package com.amiraharcs.loyalty.competition.api;
import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.competition.service.PrizeAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/prizes")
public class AdminPrizeController {
    private final PrizeAdminService service;
    public AdminPrizeController(PrizeAdminService service) {this.service=service;}
    @GetMapping public ResponseEntity<AdminPrizePage<AdminPrizeResponse>> list(@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,HttpServletRequest r) {
        parameters(r,Set.of("size","cursor"));return response(service.list(size,cursor));
    }
    @GetMapping("/{id}") public ResponseEntity<AdminPrizeResponse> get(@PathVariable UUID id,HttpServletRequest r) {parameters(r,Set.of());return response(service.get(id));}
    @PostMapping public ResponseEntity<AdminPrizeResponse> create(@Valid @RequestBody PrizeRequest body,HttpServletRequest r) {
        parameters(r,Set.of());var result=service.create(body,UUID.fromString(RequestIdFilter.requestId(r)));
        return ResponseEntity.created(URI.create("/api/v1/admin/prizes/"+result.id())).cacheControl(CacheControl.noStore()).body(result);
    }
    @PatchMapping("/{id}") public ResponseEntity<AdminPrizeResponse> update(@PathVariable UUID id,@RequestBody PrizePatchRequest body,HttpServletRequest r) {
        parameters(r,Set.of());return response(service.update(id,body,UUID.fromString(RequestIdFilter.requestId(r))));
    }
    static <T> ResponseEntity<T> response(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    static void parameters(HttpServletRequest r,Set<String> allowed) {
        if(r.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().length!=1 || e.getValue()[0].isBlank())) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    }
}
