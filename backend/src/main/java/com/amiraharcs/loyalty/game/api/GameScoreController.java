package com.amiraharcs.loyalty.game.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.game.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/customers/me")
public class GameScoreController {
    private final GameScoreService service;
    private final GameScoreQueryService queries;
    public GameScoreController(GameScoreService service,GameScoreQueryService queries) {this.service=service;this.queries=queries;}
    @PostMapping("/game-sessions/{id}/scores")
    public ResponseEntity<ScoreResponse> submit(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScoreRequest body,HttpServletRequest request) {
        query(request);
        var result=service.submit(id,body,key,UUID.fromString(RequestIdFilter.requestId(request)),request.getRemoteAddr());
        return ResponseEntity.status(result.status()).location(URI.create("/api/v1/customers/me/game-sessions/"+id+"/scores"))
                .cacheControl(CacheControl.noStore()).body(result.body());
    }
    @GetMapping("/game-sessions/{id}/scores")
    public ResponseEntity<ScoreResponse> owned(@PathVariable UUID id,HttpServletRequest request) {
        query(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.owned(id));
    }
    @GetMapping("/game-summary")
    public ResponseEntity<GameSummaryResponse> summary(HttpServletRequest request) {
        query(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.summary());
    }
    private static void query(HttpServletRequest request) {
        if(!request.getParameterMap().isEmpty()) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    }
}
