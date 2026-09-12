package com.medical.careslot.services;

import com.medical.careslot.api.bookings.ConfirmBookingResponse;

import java.util.UUID;

public interface BookingService {
    ConfirmBookingResponse confirmHold(UUID uuid, UUID patientId, String bookingKey);
}
