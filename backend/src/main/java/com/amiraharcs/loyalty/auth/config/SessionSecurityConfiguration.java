package com.amiraharcs.loyalty.auth.config;

import com.amiraharcs.loyalty.auth.security.DatabaseAuthenticationProvider;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.authentication.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.session.web.http.*;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

@Configuration(proxyBeanMethods = false)
public class SessionSecurityConfiguration {
    @Bean
    AuthenticationManager authenticationManager(DatabaseAuthenticationProvider provider) { return new ProviderManager(provider); }
    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        var tokens = new CookieCsrfTokenRepository();
        tokens.setHeaderName("X-CSRF-TOKEN");
        tokens.setCookieName(production ? "__Host-loyalty-csrf" : "loyalty-csrf");
        tokens.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(production).sameSite("Lax").path("/"));
        return tokens;
    }
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }
    @Bean
    CookieSerializer cookieSerializer(Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName(production ? "__Host-loyalty-session" : "loyalty-session");
        cookie.setUseSecureCookie(production);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setCookiePath("/");
        cookie.setUseBase64Encoding(false);
        return cookie;
    }
    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgresSessionQueries() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }
}
