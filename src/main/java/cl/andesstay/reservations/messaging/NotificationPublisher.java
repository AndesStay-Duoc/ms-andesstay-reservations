package cl.andesstay.reservations.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${andesstay.rabbitmq.exchange-direct}")
    private String directExchange;

    /**
     * Envía un mensaje de email al huésped.
     */
    public void sendEmailNotification(String guestEmail, String subject, String body, String traceId) {
        var envelope = buildEnvelope("EMAIL_NOTIFICATION", traceId, Map.of(
                "to", guestEmail,
                "subject", subject,
                "body", body
        ));
        log.info("[RabbitMQ] Publicando email notification → traceId={}", traceId);
        rabbitTemplate.convertAndSend(directExchange, "email.send", envelope);
    }

    /**
     * Envía ticket de preparación a housekeeping.
     */
    public void sendHousekeepingTicket(Long unitId, String checkInDate, String action, String traceId) {
        var envelope = buildEnvelope("HOUSEKEEPING_TICKET", traceId, Map.of(
                "unitId", unitId,
                "date", checkInDate,
                "action", action   // PREPARE | CLEAN
        ));
        log.info("[RabbitMQ] Publicando housekeeping ticket → unitId={} traceId={}", unitId, traceId);
        rabbitTemplate.convertAndSend(directExchange, "housekeeping.ticket", envelope);
    }

    /**
     * Solicita generación de voucher PDF.
     */
    public void sendVoucherRequest(Long reservationId, String guestEmail, String traceId) {
        var envelope = buildEnvelope("VOUCHER_REQUEST", traceId, Map.of(
                "reservationId", reservationId,
                "guestEmail", guestEmail
        ));
        log.info("[RabbitMQ] Publicando voucher request → reservationId={} traceId={}", reservationId, traceId);
        rabbitTemplate.convertAndSend(directExchange, "voucher.gen", envelope);
    }

    private Map<String, Object> buildEnvelope(String type, String traceId, Map<String, Object> payload) {
        return Map.of(
                "type", type,
                "eventId", UUID.randomUUID().toString(),
                "timestamp", Instant.now().toString(),
                "traceId", traceId,
                "correlationId", UUID.randomUUID().toString(),
                "payload", payload
        );
    }
}
