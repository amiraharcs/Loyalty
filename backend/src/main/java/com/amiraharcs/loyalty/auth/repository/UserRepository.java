package com.amiraharcs.loyalty.auth.repository;

import com.amiraharcs.loyalty.auth.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Identity lookups expect User.canonicalizeEmail; hard-delete is intentionally not exposed. */
public interface UserRepository extends Repository<User, UUID> {
    User save(User user);
    Optional<User> findById(UUID id);
    Optional<User> findByCanonicalEmail(String canonicalEmail);
    boolean existsByCanonicalEmail(String canonicalEmail);
}
