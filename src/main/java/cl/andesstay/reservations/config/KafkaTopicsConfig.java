package cl.andesstay.reservations.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * Los temas del sistema, declarados desde el codigo.
 *
 * POR QUE AQUI SI YA LOS CREA EL COMPOSE. El contenedor kafka-init de infra/kafka/compose.yml crea
 * los temas al levantar el clúster, pero eso solo sirve en el entorno que usa ese compose. Con
 * KafkaAdmin, cualquier despliegue del servicio contra cualquier clúster deja los temas con la
 * forma correcta, y la forma correcta queda escrita junto al codigo que la necesita en lugar de en
 * un script. Las dos vias son idempotentes y conviven sin estorbarse: Kafka ignora la creacion de
 * un tema que ya existe.
 *
 * TRES PARTICIONES. Es el paralelismo maximo del tema: tres consumidores del mismo grupo pueden
 * repartirse el trabajo, uno por particion. Como la clave es el reservationId, todos los eventos
 * de una reserva caen siempre en la misma particion y se consumen en orden, que es la condicion
 * para poder reconstruir su timeline y medir su tiempo de ciclo.
 *
 * TRES REPLICAS. Con min.insync.replicas=2 en el broker, el clúster sigue aceptando escrituras con
 * un broker caido y solo las rechaza si caen dos. Es el punto en que disponibilidad y consistencia
 * se equilibran para tres brokers.
 *
 * LA CONDICION. Toda la clase cuelga de la misma bandera que gobierna al publicador. Sin ella,
 * KafkaAdmin intentaria crear los temas al arrancar aunque el clúster no exista y llenaria el log
 * de avisos de broker inalcanzable en los entornos que todavia corren sin Kafka.
 *
 * LAS POLITICAS DE RETENCION son distintas porque los dos temas responden preguntas distintas.
 * reservations.events es un registro de hechos que alimenta reportes recientes: siete dias bastan
 * y pasado ese plazo se borra. audit.timeline es el historial de quien hizo que: se compacta, de
 * modo que el ultimo evento de cada reserva se conserva aunque hayan pasado los treinta dias, y lo
 * que caduca es solo lo intermedio.
 */
@Configuration
@ConditionalOnProperty(name = "andesstay.events.kafka.enabled", havingValue = "true")
public class KafkaTopicsConfig {

    private static final int PARTICIONES = 3;
    private static final short REPLICAS = 3;

    @Value("${andesstay.kafka.topics.reservations-events}")
    private String temaDeEventos;

    @Value("${andesstay.kafka.topics.audit-timeline}")
    private String temaDeTimeline;

    @Bean
    public NewTopic temaReservationsEvents() {
        return TopicBuilder.name(temaDeEventos)
                .partitions(PARTICIONES)
                .replicas(REPLICAS)
                .config("cleanup.policy", "delete")
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .config("min.insync.replicas", "2")
                .build();
    }

    @Bean
    public NewTopic temaAuditTimeline() {
        return TopicBuilder.name(temaDeTimeline)
                .partitions(PARTICIONES)
                .replicas(REPLICAS)
                .config("cleanup.policy", "compact,delete")
                .config("retention.ms", String.valueOf(Duration.ofDays(30).toMillis()))
                .config("min.insync.replicas", "2")
                .build();
    }
}
