package com.amiraharcs.loyalty.auth;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import java.util.EnumSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RoleAuthoritiesTests {
    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void eachExplicitGrantProducesExactlyItsOneAuthority(RoleCode role) {
        var authorities = RoleAuthorities.forRoles(EnumSet.of(role));
        assertThat(authorities).extracting(authority -> authority.getAuthority())
                .containsExactly(RoleAuthorities.authority(role));
    }

    @Test
    void multipleGrantsAndNoGrantsArePreservedWithoutInventedPermissions() {
        assertThat(RoleAuthorities.forRoles(EnumSet.of(RoleCode.STAFF, RoleCode.ADMIN)))
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder(RoleAuthorities.STAFF, RoleAuthorities.ADMIN);
        assertThat(RoleAuthorities.forRoles(EnumSet.noneOf(RoleCode.class))).isEmpty();
    }
}
