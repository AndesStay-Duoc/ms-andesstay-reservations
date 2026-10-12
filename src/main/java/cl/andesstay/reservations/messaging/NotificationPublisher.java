package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica los tres comandos de RabbitMQ del caso. Los consume ms-andesstay-notify.
 *
 * A diferencia de los eventos de Kafka, que cuentan lo que ya ocurrió, estos son comandos en
 * imperativo: piden que algo se haga. El contrato hace que el nombre de cada comando (el "type"
 * del envelope) sea también su routing key. Ver infra/docs/contracts/events/rabbit.md.
 *
 * El exchange y las tres routing keys salen de andesstay.rabbitmq en application.yml; aquí no hay
 * ningún nombre de RabbitMQ escrito a mano.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private static final String SOURCE = "ms-andesstay-reservations";
    private static final String MONEDA = "CLP";

    private final RabbitTemplate rabbitTemplate;

    @Value("${andesstay.rabbitmq.exchange.direct}")
    private String directExchange;

    @Value("${andesstay.rabbitmq.routing.email-send}")
    private String tipoEmail;

    @Value("${andesstay.rabbitmq.routing.housekeeping-ticket}")
    private String tipoHousekeeping;

    @Value("${andesstay.rabbitmq.routing.voucher-gen}")
    private String tipoVoucher;

    /**
     * Permite desplegar sin broker de RabbitMQ. A diferencia de Kafka, aquí convertAndSend() es
     * SÍNCRONO: sin broker lanza AmqpConnectException y la petición HTTP que lo originó termina en
     * 500. Ese es el motivo por el que la guarda es imprescindible y no una optimización.
     *
     * La bandera es específica de RabbitMQ. Antes había una sola, andesstay.events.enabled,
     * compartida con el publicador de Kafka: activarla para tener colas revivía el productor de
     * Kafka, que con max.block.ms en 2 s añadía esa demora a cada escritura de reserva y llenaba el
     * log de errores de broker inalcanzable.
     */
    @Value("${andesstay.events.rabbit.enabled:true}")
    private boolean rabbitEnabled;

    /** Registra la omisión una sola vez por tipo de mensaje, para no llenar el log. */
    private boolean skip(String tipo) {
        if (rabbitEnabled) {
            return false;
        }
        log.debug("[RabbitMQ] Publicación desactivada (andesstay.events.rabbit.enabled=false); {} omitido", tipo);
        return true;
    }

    /**
     * Correo o push al huésped.
     *
     * @param plantilla RESERVA_CONFIRMADA, RECORDATORIO_CHECKIN, CHECKOUT_REALIZADO o
     *                  RESERVA_CANCELADA
     */
    public void sendEmail(Reservation reserva, String plantilla, Actor actor, String traceId) {
        if (skip("email")) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plantilla", plantilla);
        payload.put("destinatario", reserva.getGuestEmail());
        payload.put("reservationId", reserva.getId());
        payload.put("datos", datosDeLaReserva(reserva));

        publicar(tipoEmail, reserva, actor, traceId, payload);
    }

    /**
     * Ticket de preparación o de limpieza de la unidad.
     *
     * @param tipoTarea PREPARACION al pasar a CHECKIN_PENDIENTE, LIMPIEZA al pasar a CHECKOUT
     */
    public void sendHousekeepingTicket(Reservation reserva, String tipoTarea, Actor actor, String traceId) {
        if (skip("ticket de housekeeping")) {
            return;
        }

        // La fecha requerida es la entrada cuando hay que preparar y la salida cuando hay que
        // limpiar: el equipo necesita saber para cuándo, no cuándo se emitió el ticket.
        LocalDate fechaRequerida = "PREPARACION".equals(tipoTarea)
                ? reserva.getCheckIn()
                : reserva.getCheckOut();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reserva.getId());
        payload.put("unitId", reserva.getUnitId());
        payload.put("tipoTarea", tipoTarea);
        // La preparación tiene que estar lista antes de que llegue el huésped; la limpieza
        // posterior al checkout no bloquea a nadie de inmediato.
        payload.put("prioridad", "PREPARACION".equals(tipoTarea) ? "ALTA" : "NORMAL");
        payload.put("fechaRequerida", fechaRequerida == null ? null : fechaRequerida.toString());
        payload.put("notas", notas(reserva));

        publicar(tipoHousekeeping, reserva, actor, traceId, payload);
    }

    /**
     * Generación del comprobante.
     *
     * @param tipoDocumento VOUCHER o BOLETA
     */
    public void sendVoucherRequest(Reservation reserva, String tipoDocumento, Actor actor, String traceId) {
        if (skip("voucher")) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reserva.getId());
        payload.put("tipoDocumento", tipoDocumento);
        payload.put("destinatario", reserva.getGuestEmail());
        payload.put("datos", datosDeLaReserva(reserva));

        publicar(tipoVoucher, reserva, actor, traceId, payload);
    }

    // ── Interno ──────────────────────────────────────────────────────────────

    private void publicar(String type, Reservation reserva, Actor actor, String traceId,
                          Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = envelope(type, eventId, reserva, actor, traceId, payload);

        log.info("[RabbitMQ] Publicando {} → reserva={} eventId={} traceId={}",
                type, reserva.getId(), eventId, traceId);

        // La routing key coincide con el type: el contrato las hizo iguales a propósito, porque el
        // binding directo de cada cola es exactamente el nombre del comando.
        //
        // El CorrelationData lleva el eventId para que, si el broker rechaza la publicación, el
        // ConfirmCallback pueda decir QUÉ mensaje rechazó y no solo que hubo un nack.
        rabbitTemplate.convertAndSend(directExchange, type, envelope,
                new CorrelationData(type + " eventId=" + eventId));
    }

    /**
     * Sobre común. Antes faltaban los campos obligatorios source y actor, y el correlationId se
     * generaba aleatorio en cada mensaje, lo que impedía armar el timeline de una reserva
     * filtrando por él. Ahora el correlationId viene de la propia reserva y se mantiene durante
     * todo su ciclo de vida.
     */
    private Map<String, Object> envelope(String type, String eventId, Reservation reserva,
                                         Actor actor, String traceId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("eventId", eventId);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("traceId", traceId);
        envelope.put("correlationId", reserva.getCorrelationId());
        envelope.put("source", SOURCE);
        envelope.put("actor", actor.comoMapa());
        envelope.put("payload", payload);
        return envelope;
    }

    /**
     * Datos de la reserva para el correo y el comprobante.
     *
     * TODO: el contrato incluye además el nombre de la unidad y la tarifa por noche, que viven en
     * ms-andesstay-catalog. No se incluyen todavía porque CatalogClient es hoy de un solo sentido
     * y sin lectura; se agregan cuando ese cliente pase a ser sincrónico junto con hold y release.
     * Las plantillas de notify ya toleran su ausencia.
     */
    private Map<String, Object> datosDeLaReserva(Reservation reserva) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("unitId", reserva.getUnitId());
        datos.put("fechaEntrada", reserva.getCheckIn() == null ? null : reserva.getCheckIn().toString());
        datos.put("fechaSalida", reserva.getCheckOut() == null ? null : reserva.getCheckOut().toString());
        datos.put("noches", noches(reserva));
        datos.put("moneda", MONEDA);
        if (reserva.getTotalPrice() != null) {
            datos.put("tarifaTotal", reserva.getTotalPrice());
        }
        return datos;
    }

    private Long noches(Reservation reserva) {
        if (reserva.getCheckIn() == null || reserva.getCheckOut() == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(reserva.getCheckIn(), reserva.getCheckOut());
    }

    private String notas(Reservation reserva) {
        Long noches = noches(reserva);
        return noches == null ? "" : noches + " noche(s)";
    }
}
