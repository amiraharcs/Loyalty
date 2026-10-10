package com.amiraharcs.loyalty.auth.security;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** One authority per explicit database grant; there is deliberately no role hierarchy. */
public final class RoleAuthorities {
    public static final String CUSTOMER = "ROLE_CUSTOMER";
    public static final String STAFF = "ROLE_STAFF";
    public static final String ADMIN = "ROLE_ADMIN";

    // Compile-time constants can be used directly in @PreAuthorize annotations.
    public static final String REQUIRES_CUSTOMER = "hasAuthority('" + CUSTOMER + "')";
    public static final String REQUIRES_STAFF = "hasAuthority('" + STAFF + "')";
    public static final String REQUIRES_ADMIN = "hasAuthority('" + ADMIN + "')";
    public static final String REQUIRES_AUTHENTICATION = "isAuthenticated()";

    private RoleAuthorities() { }

    public static String authority(RoleCode role) {
        return switch (role) {
            case CUSTOMER -> CUSTOMER;
            case STAFF -> STAFF;
            case ADMIN -> ADMIN;
        };
    }

    public static List<SimpleGrantedAuthority> forRoles(Collection<RoleCode> roles) {
        return roles.stream().map(role -> new SimpleGrantedAuthority(authority(role))).toList();
    }
}
