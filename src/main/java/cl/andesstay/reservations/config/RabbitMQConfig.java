package cl.andesstay.reservations.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de RabbitMQ del lado del publicador.
 *
 * Declara los tres exchanges, igual que ms-andesstay-notify. La duplicación es deliberada: si la
 * topología viviera solo en notify, un arranque de este servicio con notify caído publicaría
 * contra un exchange inexistente, y como convertAndSend no espera confirmación del broker, el
 * llamador HTTP recibiría su 201 y el mensaje se perdería sin excepción en ninguna parte.
 *
 * Declarar un exchange dos veces con el mismo tipo y la misma durabilidad es idempotente. Las
 * COLAS, en cambio, se declaran solo en notify: una cola declarada con argumentos distintos falla
 * con PRECONDITION_FAILED y cierra el canal.
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /** Serializa los mensajes como JSON (en lugar del formato binario por defecto). */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange cmdDirectExchange(
            @Value("${andesstay.rabbitmq.exchange.direct}") String nombre) {
        return new DirectExchange(nombre, true, false);
    }

    @Bean
    public TopicExchange cmdTopicExchange(
            @Value("${andesstay.rabbitmq.exchange.topic}") String nombre) {
        return new TopicExchange(nombre, true, false);
    }

    @Bean
    public DirectExchange cmdDeadLetterExchange(
            @Value("${andesstay.rabbitmq.exchange.dlx}") String nombre) {
        return new DirectExchange(nombre, true, false);
    }

    /**
     * Publisher confirms y returns, que es lo que cierra la cadena de garantías de la guía 2.2.1.
     *
     * Sin esto la publicación es «dispara y olvida» desde el punto de vista del broker: un mensaje
     * con una routing key sin binding se descarta en silencio y este servicio nunca se entera. Con
     * mandatory en true, el broker devuelve el mensaje no enrutable y el ReturnsCallback lo deja en
     * el log con su routing key, que es exactamente el dato que hace falta para diagnosticar un
     * binding mal escrito.
     *
     * Los dos callbacks solo registran: no reintentan ni persisten. Un nack del broker sobre un
     * comando de notificación no justifica bloquear la transacción de la reserva, pero sí tiene que
     * dejar rastro.
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCallbacks() {
        return template -> {
            template.setReturnsCallback(returned -> log.error(
                    "[RabbitMQ] Mensaje NO ENRUTABLE devuelto por el broker · exchange={} routingKey={} código={} motivo={} cuerpo={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    new String(returned.getMessage().getBody())));

            template.setConfirmCallback((correlationData, ack, causa) -> {
                String referencia = correlationData == null ? "(sin correlación)" : correlationData.getId();
                if (ack) {
                    log.debug("[RabbitMQ] Broker confirmó la publicación · {}", referencia);
                } else {
                    log.error("[RabbitMQ] Broker RECHAZÓ la publicación · {} · motivo={}", referencia, causa);
                }
            });
        };
    }
}
