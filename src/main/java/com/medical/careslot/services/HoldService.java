package com.medical.careslot.services;

import com.medical.careslot.api.holds.CreateHoldResponse;

import java.util.UUID;

public interface HoldService {
    CreateHoldResponse createHold(UUID slotId, UUID patientId, String key);
}
