package com.amiraharcs.loyalty.customer.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Framework DEBUG descriptions see no customer/staff lookup query. Binding still sees real values. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CustomerLookupPrivacyFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return java.util.stream.Stream.of("/api/v1/staff/customers", "/api/v1/admin/customers", "/api/v1/admin/staff")
                .noneMatch(route -> path.equals(route) || path.startsWith(route + "/"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getQueryString() { return null; }
        }, response);
    }
}
