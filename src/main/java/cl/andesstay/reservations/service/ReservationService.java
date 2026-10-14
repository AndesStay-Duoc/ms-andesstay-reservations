package cl.andesstay.reservations.service;

import cl.andesstay.reservations.client.CatalogClient;
import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.Actor;
import cl.andesstay.reservations.messaging.NotificationPublisher;
import cl.andesstay.reservations.messaging.ReservationEventPublisher;
import cl.andesstay.reservations.repository.ReservationRepository;
import cl.andesstay.reservations.web.TransicionInvalidaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        validarFechas(reservation);

        // La unidad se consulta ANTES de guardar: de ella salen la capacidad con la que se valida
        // el número de huéspedes y la tarifa con la que se calcula el total.
        CatalogClient.Unidad unidad = catalogClient.obtenerUnidad(reservation.getUnitId());
        validarCapacidad(reservation, unidad);

        long conflicts = repository.countOverlapping(
                reservation.getUnitId(), reservation.getCheckIn(), reservation.getCheckOut());
        if (conflicts > 0) {
            throw new IllegalStateException("La unidad no está disponible para las fechas solicitadas.");
        }

        reservation.setCreatedBy(actorId);
        reservation.setStatus(ReservationStatus.CREADA);
        reservation.setTotalPrice(calcularTotal(reservation, unidad));
        // El correlationId nace aquí y acompaña a la reserva el resto de su vida: es lo que permite
        // armar su timeline filtrando por un solo campo.
        reservation.setCorrelationId(UUID.randomUUID().toString());
        Reservation saved = repository.save(reservation);

        String traceId = UUID.randomUUID().toString();
        Actor actor = Actor.de(actorId, actorRole);

        // Acuse de recibo al huésped. La tabla de rabbit.md empieza en la confirmación, pero el
        // servicio ya lo enviaba y quitarlo dejaría al huésped sin señal de que su solicitud llegó.
        notificationPublisher.sendEmail(saved, "RESERVA_RECIBIDA", actor, traceId);

        eventPublisher.publishReservationEvent(saved, "CREATED", actorId, actorRole);

        log.info("Reserva #{} creada por actor={} huespedes={} total={} correlationId={}",
                saved.getId(), actorId, saved.getHuespedes(), saved.getTotalPrice(),
                saved.getCorrelationId());
        return saved;
    }

    public Reservation findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Reserva " + id + " no encontrada"));
    }

    public List<Reservation> search(ReservationStatus status, LocalDate from, LocalDate to) {
        return repository.search(status, from, to);
    }

    /**
     * Reservas de un huésped concreto.
     *
     * Existe porque un Cliente no puede usar la búsqueda general: vería las reservas de todos. Sin
     * esto, la pantalla «Reservas» del frontend se le abría para terminar en un 403.
     *
     * Incluye las que recepción creó a su correo sin cuenta asociada: ver findPropias.
     *
     * @param guestId sub del token del Cliente
     * @param email   correo del token, o null si no trae ninguno (entonces solo cuenta el sub)
     */
    public List<Reservation> searchPropias(String guestId, String email) {
        return repository.findPropias(guestId, email);
    }

    /** Cambia el estado según la máquina de estados. */
    @Transactional
    public Reservation changeStatus(Long id, ReservationStatus newStatus, String actorId, String actorRole) {
        Reservation reservation = findById(id);

        // El estado anterior se captura ANTES de mutar la entidad.
        //
        // Antes no se hacía, y la comparación de la rama CANCELADA consultaba
        // reservation.getStatus() sobre la misma instancia ya modificada: era siempre falsa, así que
        // al cancelar una reserva confirmada el cupo nunca se restituía. Es un defecto silencioso,
        // del tipo que solo aparece contando plazas al final del mes.
        ReservationStatus estadoAnterior = reservation.getStatus();

        validarQuePuedeHacerlo(actorRole, estadoAnterior, newStatus);

        if (!estadoAnterior.canTransitionTo(newStatus)) {
            throw new TransicionInvalidaException(estadoAnterior, newStatus);
        }

        // Al confirmar se compromete el cupo ANTES de persistir el nuevo estado. Si catalog
        // responde 409, la excepción deja la transacción sin efecto y la reserva permanece en
        // CREADA, que es la regla 4 del contrato y lo que resuelve el overbooking.
        if (newStatus == ReservationStatus.CONFIRMADA) {
            catalogClient.reservarCupo(reservation.getUnitId(),
                    reservation.getCheckIn(), reservation.getCheckOut(), holdId(reservation));
        }

        reservation.setStatus(newStatus);
        Reservation updated = repository.save(reservation);

        String traceId = UUID.randomUUID().toString();
        Actor actor = Actor.de(actorId, actorRole);

        switch (newStatus) {
            case CONFIRMADA -> {
                notificationPublisher.sendEmail(updated, "RESERVA_CONFIRMADA", actor, traceId);
                notificationPublisher.sendVoucherRequest(updated, "VOUCHER", actor, traceId);
            }
            case CHECKIN_PENDIENTE -> {
                notificationPublisher.sendHousekeepingTicket(updated, "PREPARACION", actor, traceId);
                notificationPublisher.sendEmail(updated, "RECORDATORIO_CHECKIN", actor, traceId);
            }
            case CHECKOUT -> {
                catalogClient.liberarCupo(holdId(updated));
                notificationPublisher.sendHousekeepingTicket(updated, "LIMPIEZA", actor, traceId);
                notificationPublisher.sendEmail(updated, "CHECKOUT_REALIZADO", actor, traceId);
            }
            case CANCELADA -> {
                // Solo se devuelve el cupo si de verdad estaba comprometido. Liberar un hold que
                // nunca existió es inofensivo —catalog lo ignora— pero la comprobación deja claro
                // en el código cuándo hay algo que devolver.
                if (estadoAnterior.comprometeCupo()) {
                    catalogClient.liberarCupo(holdId(updated));
                }
                // Este correo faltaba, aunque el contrato lo exige para toda cancelación.
                notificationPublisher.sendEmail(updated, "RESERVA_CANCELADA", actor, traceId);
            }
            default -> {
            }
        }

        eventPublisher.publishReservationEvent(updated, newStatus.name(), actorId, actorRole);

        log.info("Reserva #{} pasó de {} a {} por actor={} correlationId={}",
                updated.getId(), estadoAnterior, newStatus, actorId, updated.getCorrelationId());
        return updated;
    }

    // ── Interno ──────────────────────────────────────────────────────────────

    /**
     * Identificador idempotente del cupo comprometido por esta reserva.
     *
     * Se deriva del id de la reserva en lugar de generarse al azar: así reintentar una
     * confirmación no descuenta el cupo dos veces, y una cancelación posterior sabe qué liberar sin
     * tener que guardar nada más.
     */
    private String holdId(Reservation reserva) {
        return "reserva-" + reserva.getId();
    }

    /**
     * Limita lo que un huesped puede hacer con su propia reserva.
     *
     * El contrato le permite UNA transicion: cancelar, y solo mientras la reserva siga en CREADA.
     * Una vez confirmada hay cupo comprometido y, en un negocio real, condiciones de cancelacion,
     * asi que a partir de ahi pasa por recepcion.
     *
     * La propiedad de la reserva ya la comprobo @PreAuthorize en el controlador; aqui solo se
     * decide QUE puede hacer, no sobre que.
     */
    private void validarQuePuedeHacerlo(String actorRole, ReservationStatus desde,
                                        ReservationStatus hacia) {
        if (!"Cliente".equals(actorRole)) {
            return;
        }
        if (hacia != ReservationStatus.CANCELADA) {
            throw new AccessDeniedException(
                    "Un huesped solo puede cancelar su reserva; confirmar y los demas cambios son de recepcion.");
        }
        if (desde != ReservationStatus.CREADA) {
            throw new AccessDeniedException(
                    "La reserva ya esta " + desde + ": para anularla hay que hablar con recepcion.");
        }
    }

    private void validarFechas(Reservation reserva) {
        if (reserva.getCheckIn() == null || reserva.getCheckOut() == null) {
            throw new IllegalArgumentException("La reserva necesita fecha de entrada y de salida.");
        }
        if (!reserva.getCheckIn().isBefore(reserva.getCheckOut())) {
            throw new IllegalArgumentException("La salida tiene que ser posterior a la entrada.");
        }
    }

    private void validarCapacidad(Reservation reserva, CatalogClient.Unidad unidad) {
        if (reserva.getHuespedes() == null || reserva.getHuespedes() < 1) {
            reserva.setHuespedes(1);
        }
        if (unidad.capacity() != null && reserva.getHuespedes() > unidad.capacity()) {
            throw new IllegalArgumentException(
                    "La unidad admite %d huésped(es) y se pidieron %d."
                            .formatted(unidad.capacity(), reserva.getHuespedes()));
        }
    }

    /**
     * Total = noches × tarifa por noche.
     *
     * El campo existía desde el principio pero nunca se calculaba, así que toda reserva se
     * guardaba con el total en nulo y el voucher salía sin importe.
     */
    private BigDecimal calcularTotal(Reservation reserva, CatalogClient.Unidad unidad) {
        if (unidad.pricePerNight() == null) {
            return null;
        }
        long noches = ChronoUnit.DAYS.between(reserva.getCheckIn(), reserva.getCheckOut());
        return unidad.pricePerNight().multiply(BigDecimal.valueOf(noches));
    }
}
