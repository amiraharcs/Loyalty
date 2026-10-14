package com.amiraharcs.loyalty.qr.api;

import com.amiraharcs.loyalty.qr.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/customers/me/qr-tokens")
public class QrTokenController {
    private final QrTokenService tokens;
    private final QrIssuanceRateLimiter limiter;
    public QrTokenController(QrTokenService tokens, QrIssuanceRateLimiter limiter) { this.tokens = tokens; this.limiter = limiter; }
    @PostMapping
    public ResponseEntity<QrTokenResponse> issue(HttpServletRequest request) throws java.io.IOException {
        // No caller-controlled fields, including an empty JSON object, are accepted.
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1 || !request.getParameterMap().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        long retryAfter = limiter.retryAfterAddress(request.getRemoteAddr());
        if (retryAfter > 0) { throw new com.amiraharcs.loyalty.common.api.RateLimitException(retryAfter); }
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(tokens.issue());
    }
}
