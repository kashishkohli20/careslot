// CareSlot domain entities — package: com.careslot.domain
// Matches V1__init_schema.sql. One file for review; split into separate
// files per class before committing to the real project structure.

package com.medical.careslot.models;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// ==================== WaitlistEntry ====================
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {
    public enum Status { WAITING, PROMOTED, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practitioner_id")
    private Practitioner practitioner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_type_id")
    private AppointmentType appointmentType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private AppUser patient;

    @Column(name = "desired_window_start", nullable = false)
    private Instant desiredWindowStart;

    @Column(name = "desired_window_end", nullable = false)
    private Instant desiredWindowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.WAITING;

    // BIGSERIAL in DB -> FIFO tie-breaker for "promote exactly one"
    @Column(insertable = false, updatable = false)
    private long position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_slot_id")
    private Slot promotedSlot;
}

