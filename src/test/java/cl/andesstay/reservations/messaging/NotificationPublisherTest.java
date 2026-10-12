package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * El exchange y las routing keys con que se publica salen de application.yml.
 *
 * Solo se carga NotificationPublisher, sin autoconfiguración (ni base de datos ni broker), pero con
 * el application.yml real: si alguien renombra una propiedad o cambia un valor, falla aquí y no en
 * producción con mensajes devueltos por el broker como no enrutables.
 */
@SpringBootTest(classes = NotificationPublisher.class)
class NotificationPublisherTest {

    @Autowired
    private NotificationPublisher publisher;
    @MockBean
    private RabbitTemplate rabbitTemplate;

    private static final Actor ACTOR = Actor.de("sub-admin", "Admin");

    @Test
    void elCorreoSalePorCmdDirectConLaRoutingKeyConfigurada() {
        publisher.sendEmail(reserva(), "RESERVA_CONFIRMADA", ACTOR, "traza-1");

        assertThat(publicado("email.send")).containsEntry("type", "email.send");
    }

    @Test
    void elTicketDeHousekeepingSalePorSuRoutingKey() {
        publisher.sendHousekeepingTicket(reserva(), "PREPARACION", ACTOR, "traza-2");

        assertThat(publicado("housekeeping.ticket")).containsEntry("type", "housekeeping.ticket");
    }

    @Test
    void elVoucherSalePorSuRoutingKey() {
        publisher.sendVoucherRequest(reserva(), "VOUCHER", ACTOR, "traza-3");

        assertThat(publicado("voucher.gen")).containsEntry("type", "voucher.gen");
    }

    /** Verifica exchange y routing key, y devuelve el envelope publicado. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> publicado(String routingKey) {
        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("cmd.direct"), eq(routingKey), envelope.capture(),
                any(CorrelationData.class));
        return (Map<String, Object>) envelope.getValue();
    }

    private static Reservation reserva() {
        return Reservation.builder()
                .id(7L)
                .guestId("huesped")
                .guestEmail("huesped@andesstay.test")
                .unitId(3L)
                .checkIn(LocalDate.of(2026, 11, 2))
                .checkOut(LocalDate.of(2026, 11, 4))
                .huespedes(2)
                .totalPrice(new BigDecimal("90000"))
                .correlationId("correlacion-7")
                .build();
    }
}
