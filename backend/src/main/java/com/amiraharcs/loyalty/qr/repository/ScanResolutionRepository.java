package com.amiraharcs.loyalty.qr.repository;

import com.amiraharcs.loyalty.qr.model.ScanResolution;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ScanResolutionRepository extends Repository<ScanResolution, UUID> {
    Optional<ScanResolution> findByIdAndStaffId(UUID id, UUID staffId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resolution from ScanResolution resolution where resolution.id=:id and resolution.staff.id=:staffId")
    Optional<ScanResolution> lockOwned(@Param("id") UUID id, @Param("staffId") UUID staffId);
}
