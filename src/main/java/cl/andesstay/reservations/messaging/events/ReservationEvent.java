package cl.andesstay.reservations.messaging.events;

import cl.andesstay.reservations.messaging.Actor;

import java.time.Instant;

/**
 * El envelope comun de los eventos de reserva, tipado.
 *
 * Es el mismo sobre que ya usan los comandos de RabbitMQ (ver
 * infra/docs/contracts/events/envelope.md): type, eventId, timestamp, traceId, correlationId,
 * source, actor y payload. Hasta ahora Kafka no lo respetaba —publicaba un Map plano con los
 * campos de la reserva al mismo nivel que los del sobre— y el contrato describia una forma que el
 * codigo no producia. Esta clase es la que hace cierto el contrato.
 *
 * Que sea un record tiene una consecuencia practica: JsonSerializer de Spring Kafka lo serializa
 * sin configuracion, y los consumidores pueden deserializarlo a su propia copia del record sin que
 * los paquetes coincidan, siempre que declaren el tipo destino.
 *
 * @param type          nombre del evento en el tema; sale de TipoDeEvento, nunca de un literal
 * @param eventId       identificador unico de ESTE evento; es la clave de idempotencia
 * @param timestamp     momento en que ocurrio, no en que se consumio
 * @param traceId       identifica la peticion HTTP que lo origino; cambia en cada llamada
 * @param correlationId agrupa todos los eventos de la misma reserva a lo largo de su vida
 * @param source        servicio que lo publico
 * @param actor         quien origino la accion
 * @param payload       los datos de la reserva
 */
public record ReservationEvent(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        String source,
        Actor actor,
        ReservationPayload payload) {
}
