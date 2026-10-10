package com.amiraharcs.loyalty.auth.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Fixed role catalogue seeded by Flyway; no role-management workflow. */
@Entity
@Table(name = "roles", schema = "loyalty")
public class Role {
    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "code", length = 16, nullable = false, updatable = false)
    private RoleCode code;

    protected Role() { }
    public RoleCode getCode() { return code; }
}
