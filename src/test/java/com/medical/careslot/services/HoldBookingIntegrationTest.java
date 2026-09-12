package com.medical.careslot.services;

import com.medical.careslot.api.bookings.ConfirmBookingResponse;
import com.medical.careslot.api.holds.CreateHoldResponse;
import com.medical.careslot.models.*;
import com.medical.careslot.repositories.BookingRepository;
import com.medical.careslot.repositories.HoldRepository;
import com.medical.careslot.repositories.SlotRepository;
import com.medical.careslot.services.exceptions.ConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = "careslot.holds.expiry-scheduler.enabled=false"
)
@Testcontainers
@ExtendWith(SpringExtension.class)
class HoldBookingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private HoldService holdService;
    @Autowired private BookingService bookingService;
    @Autowired private HoldRepository holdRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired  private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    private UUID slotId;
    private UUID patientId;

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

    @Autowired
    void configureTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void createsAnActiveHoldAndMovesTheSlotToHeld() {
        CreateHoldResponse response = holdService.createHold(slotId, patientId, UUID.randomUUID().toString());

        assertNotNull(response.holdId());
        assertEquals(slotId, response.slotId());
        assertEquals(Hold.Status.ACTIVE, response.status());
        assertTrue(response.expiresAt().isAfter(Instant.now()));
        assertEquals(Slot.Status.HELD, slotRepository.findById(slotId).orElseThrow().getStatus());
    }

    @Test
    void returnsTheOriginalHoldForAnIdempotentRetry() {
        String key = UUID.randomUUID().toString();

        CreateHoldResponse first = holdService.createHold(slotId, patientId, key);
        CreateHoldResponse retry = holdService.createHold(slotId, patientId, key);

        assertEquals(first.holdId(), retry.holdId());
        assertEquals(1, holdRepository.count());
    }

    @Test
    void allowsExactlyOneConcurrentHoldForTheSameSlot() throws Exception {
        assertEquals(
                Slot.Status.OPEN,
                slotRepository.findById(slotId)
                        .orElseThrow()
                        .getStatus(),
                "Fixture slot must be OPEN before concurrent hold requests"
        );

        assertEquals(
                0,
                holdRepository.count(),
                "Fixture must not contain an existing hold"
        );

        CyclicBarrier startGate = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Attempt>> attempts = List.of(
                    executor.submit(() -> attemptCreateHold(startGate)),
                    executor.submit(() -> attemptCreateHold(startGate))
            );

            List<Attempt> results = List.of(
                    attempts.get(0).get(10, TimeUnit.SECONDS),
                    attempts.get(1).get(10, TimeUnit.SECONDS)
            );

            long successfulRequests = results.stream().filter(Attempt::succeeded).count();
            long conflicts = results.stream().filter(result -> result.error() instanceof ConflictException).count();

//            assertEquals(
//                    1,
//                    successfulRequests,
//                    () -> "Expected one successful hold attempt, but got "
//                            + successfulRequests
//                            + ". Errors: "
//                            + results.stream()
//                            .filter(result -> result.error() != null)
//                            .map(result -> result.error().toString())
//                            .toList()
//            );
//            assertEquals(
//                    1,
//                    conflicts,
//                    () -> "Expected one ConflictException, but got "
//                            + conflicts
//                            + ". Errors: "
//                            + results.stream()
//                            .filter(result -> result.error() != null)
//                            .map(result -> result.error().toString())
//                            .toList()
//            );
            assertEquals(1, successfulRequests);
            assertEquals(1, conflicts);
            assertEquals(1, holdRepository.count());
            assertEquals(Slot.Status.HELD, slotRepository.findById(slotId).orElseThrow().getStatus());
        }
    }

    @Test
    void confirmsAnActiveHoldExactlyOnce() {
        CreateHoldResponse hold = holdService.createHold(slotId, patientId, UUID.randomUUID().toString());
        String bookingKey = UUID.randomUUID().toString();

        ConfirmBookingResponse first = bookingService.confirmHold(hold.holdId(), patientId, bookingKey);
        ConfirmBookingResponse retry = bookingService.confirmHold(hold.holdId(), patientId, bookingKey);

        assertEquals(first.bookingId(), retry.bookingId());
        assertEquals(Booking.Status.CONFIRMED, first.status());
        assertEquals(1, bookingRepository.count());

        Booking savedBooking = bookingRepository.findById(first.bookingId())
                .orElseThrow();

        assertEquals(hold.holdId(), savedBooking.getHold().getId());
        assertEquals(patientId, savedBooking.getPatient().getId());
        assertEquals(slotId, savedBooking.getSlot().getId());

        assertEquals(Hold.Status.CONFIRMED, holdRepository.findById(hold.holdId()).orElseThrow().getStatus());
        assertEquals(Slot.Status.BOOKED, slotRepository.findById(slotId).orElseThrow().getStatus());
    }

    @Test
    void rejectsConfirmationByAnotherPatient() {
        CreateHoldResponse hold = holdService.createHold(slotId, patientId, UUID.randomUUID().toString());
        UUID anotherPatientId = createSecondPatient();

        ConflictException error = assertThrows(ConflictException.class,
                () -> bookingService.confirmHold(hold.holdId(), anotherPatientId, UUID.randomUUID().toString()));

        assertInstanceOf(ConflictException.class, error);
        assertEquals(0, bookingRepository.count());
        assertEquals(Slot.Status.HELD, slotRepository.findById(slotId).orElseThrow().getStatus());
    }

    private Attempt attemptCreateHold(CyclicBarrier startGate) {
        try {
            startGate.await(10, TimeUnit.SECONDS);
            return new Attempt(holdService.createHold(slotId, patientId, UUID.randomUUID().toString()), null);
        } catch (Throwable error) {
            return new Attempt(null, error);
        }
    }

    private UUID createSecondPatient() {
        return transactionTemplate.execute(ignored -> {
            AppUser patient = new AppUser();
            patient.setEmail("other-patient@example.com");
            patient.setPasswordHash("not-a-real-password-hash");
            patient.setRole(AppUser.Role.PATIENT);
            entityManager.persist(patient);
            entityManager.flush();
            return patient.getId();
        });
    }

    private record Attempt(CreateHoldResponse response, Throwable error) {
        boolean succeeded() {
            return response != null && error == null;
        }
    }
}