package com.amiraharcs.loyalty.game.api;

import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.game.service.GameSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/customers/me/game-sessions")
public class GameSessionController {
    private final GameSessionService sessions;
    public GameSessionController(GameSessionService sessions) { this.sessions = sessions; }
    @PostMapping
    public ResponseEntity<GameSessionResponse> start(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        rejectQuery(request);
        if (body != null && (!body.isObject() || !body.isEmpty())) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        var response = sessions.start(key, requestId(request));
        return ResponseEntity.created(URI.create("/api/v1/customers/me/game-sessions/" + response.sessionId()))
                .cacheControl(CacheControl.noStore()).body(response);
    }
    @GetMapping("/{id}")
    public ResponseEntity<GameSessionResponse> recovery(@PathVariable UUID id, HttpServletRequest request) {
        rejectQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.owned(id, requestId(request)));
    }
    private static UUID requestId(HttpServletRequest request) { return UUID.fromString(RequestIdFilter.requestId(request)); }
    private static void rejectQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }
}
