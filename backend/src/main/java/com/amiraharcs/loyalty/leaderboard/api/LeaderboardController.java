package com.amiraharcs.loyalty.leaderboard.api;

import com.amiraharcs.loyalty.leaderboard.service.LeaderboardService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/leaderboards")
public class LeaderboardController {
    private final LeaderboardService service;
    public LeaderboardController(LeaderboardService service) { this.service=service; }
    @GetMapping("/weekly")
    public ResponseEntity<LeaderboardPage> weekly(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        parameters(request,Set.of("periodStart","size","cursor"));
        return response(service.weekly(periodStart,size,cursor));
    }
    @GetMapping("/all-time")
    public ResponseEntity<LeaderboardPage> allTime(@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String cursor,HttpServletRequest request) {
        parameters(request,Set.of("size","cursor"));return response(service.allTime(size,cursor));
    }
    private static ResponseEntity<LeaderboardPage> response(LeaderboardPage body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    private static void parameters(HttpServletRequest request,Set<String> allowed) {
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().length!=1 || e.getValue()[0].isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
