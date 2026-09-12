package com.medical.careslot.services;

public interface HoldExpiryService {

    /**
     * Expires one bounded batch of ACTIVE holds whose expiry time has passed.
     *
     * @return number of holds that transitioned from ACTIVE to EXPIRED
     */
    int expireActiveHolds();
}