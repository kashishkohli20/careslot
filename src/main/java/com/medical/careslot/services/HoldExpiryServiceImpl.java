package com.medical.careslot.services;

import com.medical.careslot.models.Hold;
import com.medical.careslot.models.Slot;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import com.medical.careslot.services.exceptions.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class HoldExpiryServiceImpl implements HoldExpiryService {

    private final HoldRepository holdRepository;
    private final SlotRepository slotRepository;
    private final Clock clock;
    private final int batchSize;

    public HoldExpiryServiceImpl(HoldRepository holdRepository,
                                 SlotRepository slotRepository,
                                 Clock clock,
                                 @Value("${careslot.holds.expiry-batch-size:100}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.holdRepository = holdRepository;
        this.slotRepository = slotRepository;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    @Transactional
    public int expireActiveHolds() {
        Instant now = clock.instant();

        List<Hold> holdCandidates = holdRepository.findExpiredActiveHoldsForUpdate(now, batchSize);
        int expiredCount = 0;

        for (Hold hold : holdCandidates) {
            if (hold.getStatus() != Hold.Status.ACTIVE ||
                    hold.getExpiresAt().isAfter(now)) {
    continue;
            }

            Slot slot = slotRepository.findByIdForUpdate(hold.getSlot().getId()).orElseThrow(() -> new ResourceNotFoundException("Slot %s was not found for hold %s".formatted(hold.getSlot().getId(), hold.getId())));

            if (slot.getStatus() != Slot.Status.HELD) {
                throw new IllegalStateException("Invariant violation: active hold %s requires HELD slot %s, but slot status is %s"
                        .formatted(hold.getId(), slot.getId(), slot.getStatus()));
            }

            hold.setStatus(Hold.Status.EXPIRED);
            slot.setStatus(Slot.Status.OPEN);
            expiredCount++;

        }

        return expiredCount;
    }
}