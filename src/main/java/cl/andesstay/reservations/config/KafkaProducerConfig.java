package cl.andesstay.reservations.config;

import cl.andesstay.reservations.messaging.events.ReservationEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * El cliente productor de Kafka, declarado en codigo y no solo en el YAML.
 *
 * POR QUE EN CODIGO. La autoconfiguracion de Spring Boot cubre lo basico, pero las propiedades que
 * de verdad deciden el comportamiento —idempotencia, batching, compresion— quedaban dispersas o
 * directamente ausentes. Reunirlas aqui las hace visibles y comentables, que es lo que un
 * productor de eventos de negocio necesita.
 *
 * LAS GARANTIAS. acks=all con enable.idempotence=true es lo que convierte "se publico" en una
 * afirmacion fiable: el lider espera a las replicas en sincronia y el broker descarta los
 * duplicados que generarian los reintentos. Con min.insync.replicas=2 en el broker y tres
 * replicas, el clúster tolera la caida de un broker sin perder la escritura. max.in.flight se deja
 * en 5, que es el maximo que la idempotencia admite conservando el orden dentro de la particion;
 * ese orden es justo lo que permite reconstruir el timeline de una reserva.
 *
 * EL RENDIMIENTO. batch.size y linger.ms siguen la guia de tuning del curso: agrupar hasta 32 KB y
 * esperar 20 ms antes de enviar cambia muchas peticiones pequeñas por pocas grandes. Los 20 ms de
 * latencia añadida no importan aqui porque la publicacion es asincrona y nadie espera por ella;
 * el huesped ya tiene su respuesta HTTP. La compresion snappy equilibra CPU y red.
 *
 * LA RED DE SEGURIDAD. max.block.ms baja de los 60 s por defecto a 2 s. Aunque send() es
 * asincrono, bloquea mientras busca la metadata del clúster: sin este limite, con el broker caido
 * cada cambio de estado de una reserva se quedaria esperando un minuto.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, ReservationEvent> reservationEventProducerFactory() {
        Map<String, Object> propiedades = new HashMap<>();

        propiedades.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        propiedades.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        propiedades.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Entrega: ninguna escritura se da por buena hasta que las replicas en sincronia la tienen.
        propiedades.put(ProducerConfig.ACKS_CONFIG, "all");
        propiedades.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        propiedades.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        propiedades.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        propiedades.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        // Rendimiento.
        propiedades.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);
        propiedades.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        propiedades.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        // Sin broker alcanzable, send() no se queda esperando un minuto.
        propiedades.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);

        // El consumidor declara el tipo que espera, asi que no hace falta mandar la cabecera con
        // el nombre de la clase. Quitarla desacopla los paquetes: cada repositorio tiene su propia
        // copia del record y no necesita que el nombre coincida.
        propiedades.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(propiedades);
    }

    @Bean
    public KafkaTemplate<String, ReservationEvent> reservationEventKafkaTemplate() {
        return new KafkaTemplate<>(reservationEventProducerFactory());
    }
}
