package com.amiraharcs.loyalty.auth.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Only an opaque identity ID is serialized as the authentication principal. */
public record SessionPrincipal(UUID userId) implements Principal, Serializable {
    private static final long serialVersionUID = 1L;
    @Override public String getName() { return userId.toString(); }
}
