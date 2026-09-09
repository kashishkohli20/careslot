// CareSlot domain entities — package: com.careslot.domain
// Matches V1__init_schema.sql. One file for review; split into separate
// files per class before committing to the real project structure.

package com.careslot.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// ==================== Clinic ====================
@Entity
@Table(name = "clinic")
public class Clinic {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 400)
    private String address;

    @Column(nullable = false, length = 50)
    private String timezone = "Australia/Melbourne";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // getters/setters omitted for brevity
}

// ==================== Practitioner ====================
@Entity
@Table(name = "practitioner")
public class Practitioner {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 120)
    private String specialty;
}

// ==================== AppointmentType ====================
@Entity
@Table(name = "appointment_type")
public class AppointmentType {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
}

// ==================== AppUser ====================
@Entity
@Table(name = "app_user")
public class AppUser {
    public enum Role { PATIENT, CLINIC_ADMIN }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic; // only set for CLINIC_ADMIN
}

// ==================== Slot ====================
@Entity
@Table(name = "slot")
public class Slot {
    public enum Status { OPEN, HELD, BOOKED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practitioner_id")
    private Practitioner practitioner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_type_id")
    private AppointmentType appointmentType;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    // Optimistic lock: guards concurrent OPEN->HELD or HELD->BOOKED races
    // in addition to the DB partial-unique-index guarantees.
    @Version
    @Column(nullable = false)
    private int version;
}

// ==================== Hold ====================
@Entity
@Table(name = "hold")
public class Hold {
    public enum Status { ACTIVE, CONFIRMED, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id")
    private Slot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private AppUser patient;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt; // set to now() + 2 minutes on creation
}

// ==================== Booking ====================
@Entity
@Table(name = "booking")
public class Booking {
    public enum Status { CONFIRMED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id")
    private Slot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private AppUser patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CONFIRMED;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}

// ==================== WaitlistEntry ====================
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

// ==================== OutboxEvent ====================
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    public enum Status { PENDING, PUBLISHED, FAILED, DEAD_LETTER }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType; // "BOOKING" | "WAITLIST_ENTRY"

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType; // "BOOKED" | "CANCELLED" | "PROMOTED" | "HOLD_EXPIRED"

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;
}
