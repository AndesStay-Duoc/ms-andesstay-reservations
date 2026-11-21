package cl.andesstay.reservations.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los temas que declara este servicio tienen que ser una copia exacta de la fuente de verdad,
 * infra/kafka/scripts/crear-topicos.sh.
 *
 * Los valores esperados se escriben aqui a mano y no se leen del script porque el script vive en
 * otro repositorio y este test tiene que poder correr solo. Si alguien cambia la retencion en un
 * lado y no en el otro, este test es el que lo avisa: cambiar el valor aqui obliga a mirar el
 * script, que es lo que se quiere.
 */
class KafkaTopicsConfigTest {

    private KafkaTopicsConfig config;

    @BeforeEach
    void preparar() {
        config = new KafkaTopicsConfig();
        ReflectionTestUtils.setField(config, "temaDeEventos", "reservations.events");
        ReflectionTestUtils.setField(config, "temaDeTimeline", "audit.timeline");
    }

    @Test
    @DisplayName("reservations.events: 3 particiones, 3 replicas, delete, 7 dias, min ISR 2")
    void eventos() {
        NewTopic tema = config.temaReservationsEvents();

        assertThat(tema.name()).isEqualTo("reservations.events");
        assertThat(tema.numPartitions()).isEqualTo(3);
        assertThat(tema.replicationFactor()).isEqualTo((short) 3);
        assertThat(tema.configs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "cleanup.policy", "delete",
                "retention.ms", "604800000",
                "min.insync.replicas", "2"));
    }

    @Test
    @DisplayName("audit.timeline: 3 particiones, 3 replicas, compact,delete, 30 dias, min ISR 2")
    void timeline() {
        NewTopic tema = config.temaAuditTimeline();

        assertThat(tema.name()).isEqualTo("audit.timeline");
        assertThat(tema.numPartitions()).isEqualTo(3);
        assertThat(tema.replicationFactor()).isEqualTo((short) 3);
        assertThat(tema.configs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "cleanup.policy", "compact,delete",
                "retention.ms", "2592000000",
                "min.insync.replicas", "2"));
    }
}
