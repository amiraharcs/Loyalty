package com.amiraharcs.loyalty.auth.security;

import com.amiraharcs.loyalty.auth.service.LoginRateLimiter;
import com.amiraharcs.loyalty.common.api.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final LoginRateLimiter limiter;
    private final ApiErrorWriter errors;
    public LoginRateLimitFilter(LoginRateLimiter limiter, ApiErrorWriter errors) { this.limiter = limiter; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !(request.getContextPath() + "/api/v1/auth/sessions").equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long retryAfter = limiter.reserveAddress(request.getRemoteAddr());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            errors.write(ApiErrorCode.UNAUTHENTICATED, request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
