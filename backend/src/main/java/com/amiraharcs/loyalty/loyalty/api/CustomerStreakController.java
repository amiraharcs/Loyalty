package com.amiraharcs.loyalty.loyalty.api;

import com.amiraharcs.loyalty.loyalty.service.CustomerStreakService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/customers/me/streak")
public class CustomerStreakController {
    private final CustomerStreakService streaks;
    public CustomerStreakController(CustomerStreakService streaks) { this.streaks=streaks; }
    @GetMapping
    public ResponseEntity<StreakResponse> current(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(streaks.current());
    }
}
