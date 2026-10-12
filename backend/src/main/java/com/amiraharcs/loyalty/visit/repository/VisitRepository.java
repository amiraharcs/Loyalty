package com.amiraharcs.loyalty.visit.repository;

import com.amiraharcs.loyalty.visit.model.Visit;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface VisitRepository extends Repository<Visit, UUID>, VisitAppend {
    Optional<Visit> findById(UUID id);
    boolean existsByCustomerIdAndBusinessDate(UUID customerId, LocalDate businessDate);
    Optional<Visit> findByCustomerIdAndBusinessDate(UUID customerId, LocalDate businessDate);
    Page<Visit> findByCustomerIdOrderByConfirmedAtDescIdDesc(UUID customerId, Pageable pageable);
}
