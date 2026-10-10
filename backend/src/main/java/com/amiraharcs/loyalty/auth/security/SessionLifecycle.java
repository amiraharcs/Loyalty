package com.amiraharcs.loyalty.auth.security;

import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;

@Component
public class SessionLifecycle {
    public static final String AUTHENTICATED_AT = "loyalty.authenticatedAt";
    public static final String PASSWORD_VERSION = "loyalty.passwordVersion";
    private final SecurityContextLogoutHandler security;
    private final CsrfLogoutHandler csrf;
    private final CookieSerializer cookies;

    public SessionLifecycle(SecurityContextRepository contexts, CookieCsrfTokenRepository tokens, CookieSerializer cookies) {
        this.security = new SecurityContextLogoutHandler();
        security.setSecurityContextRepository(contexts);
        this.csrf = new CsrfLogoutHandler(tokens);
        this.cookies = cookies;
    }
    public void invalidate(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        security.logout(request, response, authentication);
        csrf.logout(request, response, authentication);
        cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, ""));
    }
}
