package com.medical.careslot.api.bookings;

import com.medical.careslot.models.Booking;

import java.util.UUID;

public record ConfirmBookingResponse(
        UUID bookingId,
        UUID slotId,
        Booking.Status status
) {

}
