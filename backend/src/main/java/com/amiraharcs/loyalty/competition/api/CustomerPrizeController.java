package com.amiraharcs.loyalty.competition.api;

import com.amiraharcs.loyalty.competition.service.CustomerPrizeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/customers/me/prizes")
public class CustomerPrizeController {
    private final CustomerPrizeService service;
    public CustomerPrizeController(CustomerPrizeService service) {this.service=service;}
    @GetMapping
    public ResponseEntity<CustomerPrizePage> history(@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String cursor,HttpServletRequest request) {
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("size","cursor").contains(e.getKey())
                || e.getValue().length!=1 || e.getValue()[0].isBlank())) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(size,cursor));
    }
}
