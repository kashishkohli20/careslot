package com.medical.careslot.services;

import com.medical.careslot.api.bookings.ConfirmBookingResponse;
import com.medical.careslot.models.Booking;
import com.medical.careslot.models.Hold;
import com.medical.careslot.models.Slot;
import com.medical.careslot.repositories.BookingRepository;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import com.medical.careslot.services.exceptions.ConflictException;
import com.medical.careslot.services.exceptions.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final HoldRepository holdRepository;
    private final SlotRepository slotRepository;
    private final Clock clock;

    public BookingServiceImpl(BookingRepository bookingRepository, HoldRepository holdRepository, SlotRepository slotRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.holdRepository = holdRepository;
        this.slotRepository = slotRepository;
        this.clock = clock;
    }


    @Override
    @Transactional
    public ConfirmBookingResponse confirmHold(UUID holdId, UUID patientId, String idempotencyKey) {
//        validate idempotency key
        validateIdempotencyKey(idempotencyKey);

        var existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingResponse(existing.get(), holdId, patientId);
        }

        Hold hold = holdRepository.findByIdForUpdate(holdId).orElseThrow(() -> new ResourceNotFoundException("Hold %s was not found".formatted(holdId)));

        Slot slot = slotRepository.findByIdForUpdate(hold.getSlot().getId()).orElseThrow(() -> new ResourceNotFoundException("Slot %s was not found".formatted(hold.getSlot().getId())));

//        A duplicate request could have waited on the hold lock. Re-read after acquiring it.
        existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingResponse(existing.get(), holdId, patientId);
        }

        if (!hold.getPatient().getId().equals(patientId)) {
            throw new ConflictException("This hold belongs to another patient");
        }

        if (hold.getStatus() != Hold.Status.ACTIVE) {
            throw new ConflictException("This hold %s is not active".formatted(holdId));
        }

        if (slot.getStatus() != Slot.Status.HELD) {
            throw new ConflictException("This slot %s is not held".formatted(slot.getId()));
        }

        if (!hold.getExpiresAt().isAfter(Instant.now(clock))) {
            throw new ConflictException("This hold %s is expired".formatted(holdId));
        }

        if (slot.getStatus() != Slot.Status.HELD) {
            throw new ConflictException("This slot %s is not held".formatted(slot.getId()));
        }

        Booking booking = new Booking();
        booking.setSlot(slot);
        booking.setPatient(hold.getPatient());
        booking.setHold(hold);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setStatus(Booking.Status.CONFIRMED);

        hold.setStatus(Hold.Status.CONFIRMED);
        slot.setStatus(Slot.Status.BOOKED);
        Booking saved = bookingRepository.save(booking);

        return new ConfirmBookingResponse(saved.getId(), saved.getSlot().getId(), saved.getStatus());
    }

    private ConfirmBookingResponse existingResponse(Booking booking, UUID holdId, UUID patientId) {
        if (!booking.getHold().getId().equals(holdId) || !booking.getPatient().getId().equals(patientId)) {
            throw new ConflictException("Idempotency key has already been used for another request");
        }
        return new ConfirmBookingResponse(booking.getId(), booking.getSlot().getId(), booking.getStatus());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("IdempotencyKey can't be empty or longer than 100 characters");
        }
    }
}
