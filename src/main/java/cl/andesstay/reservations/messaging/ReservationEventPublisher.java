package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.events.ReservationEvent;
import cl.andesstay.reservations.messaging.events.ReservationPayload;
import cl.andesstay.reservations.messaging.events.TipoDeEvento;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Publica en Kafka un evento por cada transicion de la reserva. Los consumen ms-andesstay-audit y
 * ms-andesstay-report.
 *
 * A diferencia de los comandos de RabbitMQ, que piden que algo se haga, estos eventos cuentan lo
 * que ya ocurrio. Por eso no esperan respuesta y por eso se conservan: cualquier consumidor nuevo
 * puede releer el tema desde el principio y reconstruir su propia vista.
 *
 * LA CLAVE ES EL reservationId. Kafka enruta por hash de la clave, asi que todos los eventos de
 * una misma reserva caen en la misma particion y se entregan en orden. Sin eso, un CHECKOUT podria
 * consumirse antes que su CONFIRMADA y tanto el timeline como el tiempo de ciclo saldrian mal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventPublisher {

    private static final String SOURCE = "ms-andesstay-reservations";
    private static final String MONEDA = "CLP";

    private final KafkaTemplate<String, ReservationEvent> kafkaTemplate;

    @Value("${andesstay.kafka.topics.reservations-events}")
    private String reservationsTopic;

    /**
     * Permite desplegar sin broker de Kafka. Aunque kafkaTemplate.send() es asincrono, la llamada
     * bloquea hasta max.block.ms mientras busca la metadata del clúster: sin esta guarda, cada
     * cambio de estado de una reserva quedaria esperando.
     *
     * La bandera es especifica de Kafka y por omision esta en FALSE. Antes era la misma
     * andesstay.events.enabled que gobierna RabbitMQ, y eso ataba los dos brokers: activar las
     * colas revivia este productor contra un Kafka que no existe, añadiendo los 2 s de
     * max.block.ms a cada escritura de reserva y llenando el log de errores. Kafka se activa
     * cuando su clúster exista, de forma independiente.
     */
    @Value("${andesstay.events.kafka.enabled:false}")
    private boolean eventsEnabled;

    /**
     * Publica el evento que corresponde al estado en que acaba de quedar la reserva.
     *
     * @param reservation    la reserva ya persistida con su nuevo estado
     * @param estadoAnterior estado del que venia, o null si es la creacion
     * @param motivo         razon de la cancelacion; se ignora en el resto de transiciones
     * @param actor          quien origino la accion
     * @param traceId        identifica la peticion HTTP que la origino
     */
    public void publishReservationEvent(Reservation reservation, ReservationStatus estadoAnterior,
                                        String motivo, Actor actor, String traceId) {

        TipoDeEvento tipo = TipoDeEvento.de(reservation.getStatus());

        if (!eventsEnabled) {
            log.debug("[Kafka] Publicacion desactivada (andesstay.events.kafka.enabled=false); evento {} de la reserva {} omitido",
                    tipo.nombreEnElTema(), reservation.getId());
            return;
        }

        ReservationEvent evento = new ReservationEvent(
                tipo.nombreEnElTema(),
                UUID.randomUUID().toString(),
                Instant.now(),
                traceId,
                reservation.getCorrelationId(),
                SOURCE,
                actor,
                payloadDe(reservation, estadoAnterior, motivo));

        log.info("[Kafka] Publicando {} de la reserva {} → topic={} clave={}",
                evento.type(), reservation.getId(), reservationsTopic, reservation.getId());

        kafkaTemplate.send(reservationsTopic, String.valueOf(reservation.getId()), evento)
                .whenComplete((resultado, error) -> {
                    if (error != null) {
                        log.error("[Kafka] Error publicando {} de la reserva {}: {}",
                                evento.type(), reservation.getId(), error.getMessage());
                    } else {
                        log.debug("[Kafka] {} publicado en particion {} offset {}",
                                evento.type(),
                                resultado.getRecordMetadata().partition(),
                                resultado.getRecordMetadata().offset());
                    }
                });
    }

    private ReservationPayload payloadDe(Reservation reserva, ReservationStatus estadoAnterior,
                                         String motivo) {
        return new ReservationPayload(
                reserva.getId(),
                reserva.getUnitId(),
                reserva.getGuestId(),
                reserva.getGuestEmail(),
                reserva.getCheckIn(),
                reserva.getCheckOut(),
                reserva.getHuespedes(),
                reserva.getStatus() == null ? null : reserva.getStatus().name(),
                estadoAnterior == null ? null : estadoAnterior.name(),
                reserva.getStatus() == ReservationStatus.CANCELADA ? motivo : null,
                reserva.getTotalPrice(),
                reserva.getTotalPrice() == null ? null : MONEDA,
                duracionEstadiaHoras(reserva));
    }

    /**
     * Horas comprometidas entre la entrada y la salida, solo en el checkout.
     *
     * Son las horas de la ESTADIA, no las del ciclo completo de la reserva: el ciclo va desde que
     * se crea hasta que se cierra y lo calcula ms-andesstay-report restando las marcas de tiempo
     * de reservation.created y reservation.checked_out, que es la medida fiable porque sale de los
     * eventos reales y no de las fechas prometidas. Este campo, en cambio, se deriva de las fechas
     * de la reserva y sirve para comparar lo comprometido con lo ocurrido.
     */
    private Long duracionEstadiaHoras(Reservation reserva) {
        if (reserva.getStatus() != ReservationStatus.CHECKOUT) {
            return null;
        }
        if (reserva.getCheckIn() == null || reserva.getCheckOut() == null) {
            return null;
        }
        return ChronoUnit.HOURS.between(
                reserva.getCheckIn().atStartOfDay(), reserva.getCheckOut().atStartOfDay());
    }
}
