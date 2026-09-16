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
     * Permite desplegar sin broker de Kafka. En la instancia EC2 no se levantan
     * Kafka ni RabbitMQ, y aunque kafkaTemplate.send() es asíncrono, la llamada
     * bloquea hasta max.block.ms mientras busca la metadata del clúster: sin
     * esta guarda, cada cambio de estado de una reserva quedaría esperando.
     *
     * Con el valor en true (por defecto) el comportamiento no cambia.
     */
    @Value("${andesstay.events.enabled:true}")
    private boolean eventsEnabled;

    /**
     * Publica un evento de cambio de estado de reserva en Kafka.
     * Los consumidores son ms-andesstay-audit y ms-andesstay-report.
     */
    public void publishReservationEvent(Reservation reservation, String eventType,
                                        String actorId, String actorRole) {
        if (!eventsEnabled) {
            log.debug("[Kafka] Publicación desactivada (andesstay.events.enabled=false); evento {} de la reserva {} omitido",
                    eventType, reservation.getId());
            return;
        }

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
