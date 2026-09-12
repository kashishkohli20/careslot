package com.medical.careslot.repositories;

import com.medical.careslot.models.Hold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Hold h WHERE h.id = :holdId")
    Optional<Hold> findByIdForUpdate(@Param("holdId") UUID holdId);

    @Query(
            value = """
                    SELECT * FROM Hold
                    WHERE status = 'ACTIVE'
                    AND expiresAt <= :now
                    ORDER BY expires_at, id
                    LIMIT :batchSize
                    FOR UPDATE skip locked
                    """,
    nativeQuery = true)
    List<Hold> findExpiredActiveHoldsForUpdate(@Param("now") Instant now, @Param("batchSize")int batchSize);
}
