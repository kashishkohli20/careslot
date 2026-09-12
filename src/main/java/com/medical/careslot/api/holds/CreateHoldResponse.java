package com.medical.careslot.api.holds;

import com.medical.careslot.models.Hold;

import java.time.Instant;
import java.util.UUID;

public record CreateHoldResponse(
        UUID holdId,
        UUID slotId,
        Hold.Status status,
        Instant expiresAt
) {
}
