package cl.andesstay.reservations.config;

import cl.andesstay.reservations.messaging.Actor;
import cl.andesstay.reservations.messaging.events.ReservationEvent;
import cl.andesstay.reservations.messaging.events.ReservationPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El JSON que de verdad sale hacia reservations.events.
 *
 * POR QUE EXISTE. El contrato (infra/docs/contracts/events/envelope.md y kafka.md) fija timestamp
 * en ISO 8601 y las fechas de la estadia como "2026-12-01". El JsonSerializer con su ObjectMapper
 * por defecto escribia 1795255200.000000000 y [2026,12,1]. Los consumidores lo leian igual, asi que
 * nada fallaba, pero lo publicado no era lo que el contrato promete a cualquier otro lector.
 *
 * POR QUE SE SERIALIZA DE VERDAD. Se toma el serializador tal como lo arma KafkaProducerConfig y se
 * mira el texto producido, en vez de comprobar como esta configurado: lo que importa es lo que viaja.
 */
class KafkaProducerConfigFormatoTest {

    private static final ObjectMapper LECTOR = new ObjectMapper();

    private Serializer<ReservationEvent> serializador;

    @BeforeEach
    void preparar() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        serializador = serializadorDeValor(
                (DefaultKafkaProducerFactory<String, ReservationEvent>) config.reservationEventProducerFactory());
    }

    @Test
    @DisplayName("timestamp sale en ISO 8601, no como segundos desde 1970")
    void timestampIso() throws Exception {
        JsonNode json = publicar();

        assertThat(json.path("timestamp").isTextual()).isTrue();
        assertThat(json.path("timestamp").asText()).isEqualTo("2026-11-21T10:00:00Z");
    }

    @Test
    @DisplayName("Las fechas de la estadia salen como 2026-12-01, no como [2026,12,1]")
    void fechasIso() throws Exception {
        JsonNode payload = publicar().path("payload");

        assertThat(payload.path("fechaEntrada").asText()).isEqualTo("2026-12-01");
        assertThat(payload.path("fechaSalida").asText()).isEqualTo("2026-12-04");
    }

    @Test
    @DisplayName("Sigue sin mandar la cabecera con el nombre de la clase")
    void sinCabeceraDeTipo() {
        RecordHeaders cabeceras = new RecordHeaders();
        serializador.serialize("reservations.events", cabeceras, evento());

        assertThat(cabeceras.toArray()).isEmpty();
    }

    // ── Apoyo ────────────────────────────────────────────────────────────────────────────────────

    private JsonNode publicar() throws Exception {
        return LECTOR.readTree(serializador.serialize("reservations.events", new RecordHeaders(), evento()));
    }

    private static ReservationEvent evento() {
        return new ReservationEvent("reservation.created", "evt-1",
                Instant.parse("2026-11-21T10:00:00Z"), "traza-1", "corr-1", "ms-andesstay-reservations",
                new Actor("u-1", "Guest", "andesstay"),
                new ReservationPayload(42L, 7L, "g-1", "huesped@example.com",
                        LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-04"), 2,
                        "CREADA", null, null, new BigDecimal("150000"), "CLP", 72L));
    }

    /**
     * El serializador de valor tal como lo usara el productor: la instancia que recibio la fabrica
     * o, si solo se declaro la clase en las propiedades, esa clase configurada con ellas, que es
     * lo mismo que hace KafkaProducer al arrancar.
     */
    @SuppressWarnings("unchecked")
    private static Serializer<ReservationEvent> serializadorDeValor(
            DefaultKafkaProducerFactory<String, ReservationEvent> fabrica) {
        Serializer<ReservationEvent> instancia = fabrica.getValueSerializer();
        if (instancia != null) {
            return instancia;
        }
        var propiedades = fabrica.getConfigurationProperties();
        Class<?> clase = (Class<?>) propiedades.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        Serializer<ReservationEvent> deClase = (Serializer<ReservationEvent>) BeanUtils.instantiateClass(clase);
        deClase.configure(propiedades, false);
        return deClase;
    }
}
