package com.amiraharcs.loyalty.game.api;

import com.amiraharcs.loyalty.game.service.GameLifeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/customers/me/game-lives")
public class CustomerGameLivesController {
    private final GameLifeService lives;
    public CustomerGameLivesController(GameLifeService lives) { this.lives = lives; }
    @GetMapping
    public ResponseEntity<GameLivesResponse> current(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(lives.current());
    }
}
