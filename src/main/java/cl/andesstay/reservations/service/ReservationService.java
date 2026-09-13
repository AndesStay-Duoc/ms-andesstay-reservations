package cl.andesstay.reservations.service;

import cl.andesstay.reservations.client.CatalogClient;
import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.NotificationPublisher;
import cl.andesstay.reservations.messaging.ReservationEventPublisher;
import cl.andesstay.reservations.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository repository;
    private final NotificationPublisher notificationPublisher;
    private final ReservationEventPublisher eventPublisher;
    private final CatalogClient catalogClient;

    /** Crea una nueva reserva. Solo el propio huésped o un recepcionista pueden crear. */
    @Transactional
    public Reservation create(Reservation reservation, String actorId, String actorRole) {
        // Verificar disponibilidad (sin overbooking)
        long conflicts = repository.countOverlapping(
                reservation.getUnitId(), reservation.getCheckIn(), reservation.getCheckOut());
        if (conflicts > 0) {
            throw new IllegalStateException("La unidad no está disponible para las fechas solicitadas.");
        }

        reservation.setCreatedBy(actorId);
        reservation.setStatus(ReservationStatus.CREADA);
        Reservation saved = repository.save(reservation);

        String traceId = UUID.randomUUID().toString();

        // Notificación asíncrona al huésped
        notificationPublisher.sendEmailNotification(
                saved.getGuestEmail(),
                "Reserva recibida - AndesStay #" + saved.getId(),
                "Tu reserva fue recibida. Pronto recibirás la confirmación.",
                traceId
        );

        // Evento Kafka (auditoría y reportería)
        eventPublisher.publishReservationEvent(saved, "CREATED", actorId, actorRole);

        log.info("Reserva #{} creada por actor={}", saved.getId(), actorId);
        return saved;
    }

    public Reservation findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Reserva " + id + " no encontrada"));
    }

    public List<Reservation> search(ReservationStatus status, LocalDate from, LocalDate to) {
        return repository.search(status, from, to);
    }

    /** Cambia el estado según la máquina de estados. */
    @Transactional
    public Reservation changeStatus(Long id, ReservationStatus newStatus, String actorId, String actorRole) {
        Reservation reservation = findById(id);

        if (!reservation.getStatus().canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "Transición inválida: " + reservation.getStatus() + " → " + newStatus);
        }

        // Regla: no se puede hacer check-in sin confirmar
        if (newStatus == ReservationStatus.CHECKIN_PENDIENTE
                && reservation.getStatus() != ReservationStatus.CONFIRMADA) {
            throw new IllegalStateException("Debe CONFIRMAR la reserva antes de iniciar el check-in.");
        }

        reservation.setStatus(newStatus);
        Reservation updated = repository.save(reservation);

        String traceId = UUID.randomUUID().toString();

        // Notificaciones por estado + ajuste de disponibilidad en catalog
        switch (newStatus) {
            case CONFIRMADA -> {
                // Regla del caso: "La disponibilidad disminuye al confirmar la reserva"
                catalogClient.adjustSlots(updated.getUnitId(), -1);
                notificationPublisher.sendEmailNotification(
                        updated.getGuestEmail(),
                        "Reserva CONFIRMADA - AndesStay #" + updated.getId(),
                        "Tu reserva ha sido confirmada. ¡Te esperamos!",
                        traceId
                );
                notificationPublisher.sendVoucherRequest(updated.getId(), updated.getGuestEmail(), traceId);
            }
            case CHECKIN_PENDIENTE -> notificationPublisher.sendHousekeepingTicket(
                    updated.getUnitId(), updated.getCheckIn().toString(), "PREPARE", traceId);
            case CHECKOUT -> {
                // Al hacer checkout se libera el cupo para futuras reservas
                catalogClient.adjustSlots(updated.getUnitId(), +1);
                notificationPublisher.sendHousekeepingTicket(
                        updated.getUnitId(), updated.getCheckOut().toString(), "CLEAN", traceId);
            }
            case CANCELADA -> {
                // Si estaba CONFIRMADA, restituir el cupo
                if (reservation.getStatus() == ReservationStatus.CONFIRMADA
                        || reservation.getStatus() == ReservationStatus.CHECKIN_PENDIENTE) {
                    catalogClient.adjustSlots(updated.getUnitId(), +1);
                }
            }
            default -> {}
        }

        // Evento Kafka
        eventPublisher.publishReservationEvent(updated, newStatus.name(), actorId, actorRole);

        return updated;
    }
}
