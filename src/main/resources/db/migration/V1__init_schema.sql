-- CareSlot: initial schema
-- Enforces slot/hold/booking invariants at the DB layer, not just in app code.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS btree_gist; -- needed for exclusion constraint on time ranges

-- ============ CLINICS ============
CREATE TABLE clinic (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(200) NOT NULL,
    address VARCHAR(400),
    timezone VARCHAR(50) NOT NULL DEFAULT 'Australia/Melbourne',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ PRACTITIONERS ============
CREATE TABLE practitioner (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    clinic_id UUID NOT NULL REFERENCES clinic(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    specialty VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_practitioner_clinic ON practitioner(clinic_id);

-- ============ APPOINTMENT TYPES ============
CREATE TABLE appointment_type (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    clinic_id UUID NOT NULL REFERENCES clinic(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    duration_minutes INT NOT NULL CHECK (duration_minutes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_appt_type_clinic ON appointment_type(clinic_id);

-- ============ USERS (patients + clinic admins) ============
CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('PATIENT', 'CLINIC_ADMIN')),
    clinic_id UUID REFERENCES clinic(id), -- non-null only for CLINIC_ADMIN
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ SLOTS ============
CREATE TABLE slot (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    practitioner_id UUID NOT NULL REFERENCES practitioner(id) ON DELETE CASCADE,
    appointment_type_id UUID NOT NULL REFERENCES appointment_type(id),
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'HELD', 'BOOKED', 'CANCELLED')),
    version INT NOT NULL DEFAULT 0, -- optimistic lock for concurrent status flips
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_time > start_time),

    -- No two slots for the same practitioner may overlap in time, ever.
    EXCLUDE USING gist (
        practitioner_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    )
);
CREATE INDEX idx_slot_practitioner_time ON slot(practitioner_id, start_time);
CREATE INDEX idx_slot_status ON slot(status);

-- ============ HOLDS ============
-- A hold reserves a slot for up to 2 minutes while the patient confirms.
CREATE TABLE hold (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    slot_id UUID NOT NULL REFERENCES slot(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (idempotency_key)
);
-- Only one ACTIVE hold may exist per slot at any time.
CREATE UNIQUE INDEX uq_hold_active_per_slot ON hold(slot_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_hold_expiry_sweep ON hold(status, expires_at) WHERE status = 'ACTIVE';

-- ============ BOOKINGS ============
CREATE TABLE booking (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    slot_id UUID NOT NULL REFERENCES slot(id),
    patient_id UUID NOT NULL REFERENCES app_user(id),
    hold_id UUID REFERENCES hold(id),
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ,

    UNIQUE (idempotency_key)
);
-- Only one CONFIRMED booking may exist per slot at any time.
CREATE UNIQUE INDEX uq_booking_confirmed_per_slot ON booking(slot_id) WHERE status = 'CONFIRMED';

-- ============ WAITLIST ============
CREATE TABLE waitlist_entry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    practitioner_id UUID NOT NULL REFERENCES practitioner(id),
    appointment_type_id UUID NOT NULL REFERENCES appointment_type(id),
    patient_id UUID NOT NULL REFERENCES app_user(id),
    desired_window_start TIMESTAMPTZ NOT NULL,
    desired_window_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'PROMOTED', 'EXPIRED', 'CANCELLED')),
    position BIGSERIAL, -- FIFO ordering for promote-exactly-one
    promoted_slot_id UUID REFERENCES slot(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_waitlist_lookup ON waitlist_entry(practitioner_id, appointment_type_id, status, position);

-- ============ OUTBOX (for notifications worker, DLQ-able) ============
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(50) NOT NULL, -- e.g. 'BOOKING', 'WAITLIST_ENTRY'
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,     -- e.g. 'BOOKED', 'CANCELLED', 'PROMOTED', 'HOLD_EXPIRED'
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')),
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event(status, created_at) WHERE status = 'PENDING';
