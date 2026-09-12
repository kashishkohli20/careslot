package com.medical.careslot.services;

import com.medical.careslot.api.bookings.ConfirmBookingResponse;
import com.medical.careslot.api.holds.CreateHoldResponse;
import com.medical.careslot.models.*;
import com.medical.careslot.repositories.BookingRepository;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import com.medical.careslot.services.exceptions.ConflictException;
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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = "carslot.holds.expiry-scheduler.enabled=false"
)
@Testcontainers
public class HoldExpiryConcurrencyIntegrationTest {

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
    @Autowired private BookingRepository bookingRepository;
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
    void lateConfirmationAndExpirySweepCannotCreateBooking() throws Exception {
        CreateHoldResponse hold = holdService.createHold(slotId, patientId, UUID.randomUUID().toString());
        setHoldExpiry(hold.holdId(), Instant.now().minus(1, ChronoUnit.MINUTES));

        CyclicBarrier startGate = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ConfirmationAttempt> confirmationFuture = executor.submit(
                    () -> attemptConfirmation(startGate, hold.holdId())
            );
            Future<ExpiryAttempt> expiryFuture = executor.submit(
                    () -> attemptExpiry(startGate)
            );

            ConfirmationAttempt confirmation = confirmationFuture.get(10, TimeUnit.SECONDS);
            ExpiryAttempt expiry = expiryFuture.get(10, TimeUnit.SECONDS);

            assertNull(expiry.error());
            assertEquals(1, expiry.expiredCount());
            assertInstanceOf(ConflictException.class, confirmation.error());

            Hold expiredHold = holdRepository.findById(hold.holdId()).orElseThrow();
            Slot reopenedSlot = slotRepository.findById(slotId).orElseThrow();

            assertEquals(Hold.Status.EXPIRED, expiredHold.getStatus());
            assertEquals(Slot.Status.OPEN, reopenedSlot.getStatus());
            assertEquals(0, bookingRepository.count());
        }
    }

    private ConfirmationAttempt attemptConfirmation(CyclicBarrier startGate, UUID holdId) {
        try {
            startGate.await(10, TimeUnit.SECONDS);
            ConfirmBookingResponse response = bookingService.confirmHold(
                    holdId,
                    patientId,
                    UUID.randomUUID().toString()
            );
            return new ConfirmationAttempt(response, null);
        } catch (Throwable error) {
            return new ConfirmationAttempt(null, error);
        }
    }

    private ExpiryAttempt attemptExpiry(CyclicBarrier startGate) {
        try {
            startGate.await(10, TimeUnit.SECONDS);
            return new ExpiryAttempt(holdExpiryService.expireActiveHolds(), null);
        } catch (Throwable error) {
            return new ExpiryAttempt(0, error);
        }
    }

    private void setHoldExpiry(UUID holdId, Instant expiresAt) {
        transactionTemplate.executeWithoutResult(ignored -> {
            Hold hold = holdRepository.findById(holdId).orElseThrow();
            hold.setExpiresAt(expiresAt);
        });
    }

    private record ConfirmationAttempt(ConfirmBookingResponse response, Throwable error) {
    }

    private record ExpiryAttempt(int expiredCount, Throwable error) {
    }


}
