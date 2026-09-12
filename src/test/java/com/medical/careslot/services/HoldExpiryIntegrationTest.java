package com.medical.careslot.services;

import com.medical.careslot.api.bookings.ConfirmBookingResponse;
import com.medical.careslot.api.holds.CreateHoldResponse;
import com.medical.careslot.models.AppUser;
import com.medical.careslot.models.AppointmentType;
import com.medical.careslot.models.Booking;
import com.medical.careslot.models.Clinic;
import com.medical.careslot.models.Hold;
import com.medical.careslot.models.Practitioner;
import com.medical.careslot.models.Slot;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class HoldExpiryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private HoldService holdService;
    @Autowired private BookingService bookingService;
    @Autowired private HoldExpiryService holdExpiryService;
    @Autowired private HoldRepository holdRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private UUID slotId;
    private UUID patientId;

    @Autowired
    void configureTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.execute("TRUNCATE TABLE outbox_event, waitlist_entry, booking, hold, slot, appointment_type, practitioner, app_user, clinic RESTART IDENTITY CASCADE");

            Clinic clinic = new Clinic();
            clinic.setName("Fitzroy Family Clinic");
            clinic.setTimezone("Australia/Melbourne");
            clinic.setCreatedAt(Instant.now());
            entityManager.persist(clinic);

            Practitioner practitioner = new Practitioner();
            practitioner.setClinic(clinic);
            practitioner.setName("Dr Priya Shah");
            practitioner.setSpecialty("General Practice");
            entityManager.persist(practitioner);

            AppointmentType appointmentType = new AppointmentType();
            appointmentType.setClinic(clinic);
            appointmentType.setName("Standard consultation");
            appointmentType.setDurationMinutes(30);
            entityManager.persist(appointmentType);

            AppUser patient = new AppUser();
            patient.setEmail("patient@example.com");
            patient.setPasswordHash("not-a-real-password-hash");
            patient.setRole(AppUser.Role.PATIENT);
            entityManager.persist(patient);

            Slot slot = new Slot();
            slot.setPractitioner(practitioner);
            slot.setAppointmentType(appointmentType);
            slot.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
            slot.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES));
            slot.setStatus(Slot.Status.OPEN);
            entityManager.persist(slot);
            entityManager.flush();

            slotId = slot.getId();
            patientId = patient.getId();
        });
    }

    @Test
    void expiresActiveExpiredHoldAndReopensSlot() {
        CreateHoldResponse createdHold = createHoldAndMakeExpired();

        int expiredCount = holdExpiryService.expireActiveHolds();

        Hold expiredHold = holdRepository.findById(createdHold.holdId()).orElseThrow();
        Slot reopenedSlot = slotRepository.findById(slotId).orElseThrow();

        assertEquals(1, expiredCount);
        assertEquals(Hold.Status.EXPIRED, expiredHold.getStatus());
        assertEquals(Slot.Status.OPEN, reopenedSlot.getStatus());
    }

    @Test
    void doesNotExpireFutureActiveHold() {
        CreateHoldResponse createdHold = holdService.createHold(
                slotId,
                patientId,
                UUID.randomUUID().toString()
        );

        int expiredCount = holdExpiryService.expireActiveHolds();

        Hold activeHold = holdRepository.findById(createdHold.holdId()).orElseThrow();
        Slot heldSlot = slotRepository.findById(slotId).orElseThrow();

        assertEquals(0, expiredCount);
        assertEquals(Hold.Status.ACTIVE, activeHold.getStatus());
        assertTrue(activeHold.getExpiresAt().isAfter(Instant.now()));
        assertEquals(Slot.Status.HELD, heldSlot.getStatus());
    }

    @Test
    void doesNotExpireConfirmedHold() {
        CreateHoldResponse createdHold = holdService.createHold(
                slotId,
                patientId,
                UUID.randomUUID().toString()
        );

        ConfirmBookingResponse booking = bookingService.confirmHold(
                createdHold.holdId(),
                patientId,
                UUID.randomUUID().toString()
        );
        assertNotNull(booking.bookingId());
        assertEquals(Booking.Status.CONFIRMED, booking.status());

        setHoldExpiry(createdHold.holdId(), Instant.now().minus(1, ChronoUnit.MINUTES));

        int expiredCount = holdExpiryService.expireActiveHolds();

        Hold confirmedHold = holdRepository.findById(createdHold.holdId()).orElseThrow();
        Slot bookedSlot = slotRepository.findById(slotId).orElseThrow();

        assertEquals(0, expiredCount);
        assertEquals(Hold.Status.CONFIRMED, confirmedHold.getStatus());
        assertEquals(Slot.Status.BOOKED, bookedSlot.getStatus());
    }

    @Test
    void isIdempotentAcrossRepeatedExpirySweeps() {
        CreateHoldResponse createdHold = createHoldAndMakeExpired();

        int firstSweepCount = holdExpiryService.expireActiveHolds();
        int secondSweepCount = holdExpiryService.expireActiveHolds();

        Hold expiredHold = holdRepository.findById(createdHold.holdId()).orElseThrow();
        Slot reopenedSlot = slotRepository.findById(slotId).orElseThrow();

        assertEquals(1, firstSweepCount);
        assertEquals(0, secondSweepCount);
        assertEquals(Hold.Status.EXPIRED, expiredHold.getStatus());
        assertEquals(Slot.Status.OPEN, reopenedSlot.getStatus());
    }

    private CreateHoldResponse createHoldAndMakeExpired() {
        CreateHoldResponse createdHold = holdService.createHold(
                slotId,
                patientId,
                UUID.randomUUID().toString()
        );
        setHoldExpiry(createdHold.holdId(), Instant.now().minus(1, ChronoUnit.MINUTES));
        return createdHold;
    }

    private void setHoldExpiry(UUID holdId, Instant expiresAt) {
        transactionTemplate.executeWithoutResult(ignored -> {
            Hold hold = holdRepository.findById(holdId).orElseThrow();
            hold.setExpiresAt(expiresAt);
        });
    }
}