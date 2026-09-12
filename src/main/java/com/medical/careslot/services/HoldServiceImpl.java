package com.medical.careslot.services;

import com.medical.careslot.api.holds.CreateHoldResponse;
import com.medical.careslot.models.AppUser;
import com.medical.careslot.models.Hold;
import com.medical.careslot.models.Slot;
import com.medical.careslot.repositories.AppUserRepository;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import com.medical.careslot.services.exceptions.ConflictException;
import com.medical.careslot.services.exceptions.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class HoldServiceImpl implements HoldService {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(2);

    private final HoldRepository holdRepository;
    private final SlotRepository slotRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    public HoldServiceImpl(HoldRepository holdRepository, SlotRepository slotRepository, AppUserRepository appUserRepository,  Clock clock) {
        this.holdRepository = holdRepository;
        this.slotRepository = slotRepository;
        this.appUserRepository = appUserRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreateHoldResponse createHold(UUID slotId, UUID patientId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        var existing = holdRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingResponse(existing.get(), slotId, patientId);
        }

        Slot slot = slotRepository.findByIdForUpdate(slotId).orElseThrow(() -> new ResourceNotFoundException("Slot %s not found".formatted(slotId)));

//        The lock may have made us wait for another identical request. Re-read after locking
        existing = holdRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            return existingResponse(existing.get(), slotId, patientId);
        }

//        if( slot.getStatus() != Slot.Status.OPEN) {
//            throw new ConflictException("Slot %s is no longer available".formatted(slotId));
//        }
        if (slot.getStatus() != Slot.Status.OPEN) {
            throw new ConflictException(
                    "Slot is not available. requestedSlotId=%s, loadedSlotId=%s, status=%s, version=%s"
                            .formatted(
                                    slotId,
                                    slot.getId(),
                                    slot.getStatus(),
                                    slot.getVersion()
                            )
            );
        }

//        check whether user is a patient or admin
        AppUser patient = appUserRepository.findById(patientId).orElseThrow(() -> new  ResourceNotFoundException("Patient %s not found".formatted(patientId)));

        if (patient.getRole() != AppUser.Role.PATIENT) {
            throw new ConflictException("Only a patient can hold an appointment slot");
        }

        Instant expiresAt = Instant.now(clock).plus(HOLD_DURATION);
        Hold hold = new Hold(); // TODO: check what's the solution here
        hold.setSlot(slot);
        hold.setPatient(patient);
        hold.setIdempotencyKey(idempotencyKey);
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(expiresAt);

        slot.setStatus(Slot.Status.HELD);
        Hold saved = holdRepository.save(hold);

        return new CreateHoldResponse(saved.getId(), slot.getId(), saved.getStatus(), saved.getExpiresAt());
    }

    private CreateHoldResponse existingResponse(Hold hold, UUID slotId, UUID patientId) {
        if (!hold.getSlot().getId().equals(slotId) || !hold.getPatient().getId().equals(patientId)) {
            throw new ConflictException("Idempotency key has already been used for another request");
        }
        return new CreateHoldResponse(hold.getId(), slotId, hold.getStatus(), hold.getExpiresAt());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if(idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("idempotencyKey can't be empty or longer than 100 characters");
        }
    }


}
