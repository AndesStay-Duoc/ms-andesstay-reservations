package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${andesstay.kafka.topic.reservations-events}")
    private String reservationsTopic;

    /**
     * Publica un evento de cambio de estado de reserva en Kafka.
     * Los consumidores son ms-andesstay-audit y ms-andesstay-report.
     */
    public void publishReservationEvent(Reservation reservation, String eventType,
                                        String actorId, String actorRole) {
        var event = Map.of(
                "eventId",       UUID.randomUUID().toString(),
                "eventType",     eventType,           // CREATED, CONFIRMED, CHECKIN, CHECKOUT, CANCELLED
                "timestamp",     Instant.now().toString(),
                "traceId",       UUID.randomUUID().toString(),
                "reservationId", reservation.getId(),
                "unitId",        reservation.getUnitId(),
                "guestId",       reservation.getGuestId(),
                "status",        reservation.getStatus().name(),
                "actorId",       actorId,
                "actorRole",     actorRole
        );

        log.info("[Kafka] Publicando evento {} para reserva {} → topic={}",
                eventType, reservation.getId(), reservationsTopic);

        kafkaTemplate.send(reservationsTopic, reservation.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Kafka] Error publicando evento reserva {}: {}", reservation.getId(), ex.getMessage());
                    } else {
                        log.debug("[Kafka] Evento publicado offset={}", result.getRecordMetadata().offset());
                    }
                });
    }
}
