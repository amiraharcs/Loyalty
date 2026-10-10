package com.amiraharcs.loyalty.auth.repository;

import com.amiraharcs.loyalty.auth.model.Role;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Flyway owns the fixed role catalogue; callers may only resolve a known code. */
public interface RoleRepository extends Repository<Role, RoleCode> {
    Optional<Role> findById(RoleCode code);
}
