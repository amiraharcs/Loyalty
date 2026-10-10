package com.amiraharcs.loyalty.auth.api;

import com.amiraharcs.loyalty.auth.service.RegistrationRateLimiter;
import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.common.api.ApiErrorWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after CSRF validation and before registration validation/hashing; never trusts forwarded headers. */
public class RegistrationRateLimitFilter extends OncePerRequestFilter {
    private final RegistrationRateLimiter limiter;
    private final ApiErrorWriter errors;

    public RegistrationRateLimitFilter(RegistrationRateLimiter limiter, ApiErrorWriter errors) {
        this.limiter = limiter;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/v1/auth/registrations").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long retryAfter = limiter.retryAfter(request.getRemoteAddr());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            errors.write(ApiErrorCode.RATE_LIMITED, request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
