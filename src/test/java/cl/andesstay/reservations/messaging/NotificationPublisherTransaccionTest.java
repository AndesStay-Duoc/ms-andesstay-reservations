package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Los comandos de RabbitMQ salen despues del commit de la reserva, no antes.
 *
 * POR QUE EXISTE. Antes se publicaban en mitad de la transaccion. Si algo fallaba despues —en la
 * prueba de punta a punta fue el productor de Kafka—, la reserva se revertia pero el comando ya
 * estaba en el broker, y notify le mandaba al huesped "Reserva recibida" de una reserva que no
 * existe. Ahora el envio se registra para afterCommit: si la transaccion se revierte, no sale nada.
 *
 * La transaccion se simula con TransactionSynchronizationManager, que es el mismo mecanismo que usa
 * @Transactional por debajo, para no necesitar una base de datos.
 */
@SpringBootTest(classes = NotificationPublisher.class)
class NotificationPublisherTransaccionTest {

    private static final Actor ACTOR = Actor.de("sub-admin", "Admin");

    @Autowired
    private NotificationPublisher publisher;
    @MockBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void abrirTransaccion() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cerrarTransaccion() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("dentro de una transacción no publica nada hasta el commit")
    void esperaAlCommit() {
        publisher.sendEmail(reserva(), "RESERVA_RECIBIDA", ACTOR, "traza-1");
        publisher.sendVoucherRequest(reserva(), "VOUCHER", ACTOR, "traza-1");

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(CorrelationData.class));

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(rabbitTemplate).convertAndSend(eq("cmd.direct"), eq("email.send"), any(Object.class),
                any(CorrelationData.class));
        verify(rabbitTemplate).convertAndSend(eq("cmd.direct"), eq("voucher.gen"), any(Object.class),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("si la transacción se revierte, el comando no sale")
    void rollbackNoPublica() {
        publisher.sendEmail(reserva(), "RESERVA_RECIBIDA", ACTOR, "traza-2");

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("un broker caído después del commit no tumba la petición: la reserva ya existe")
    void falloTrasElCommitNoSePropaga() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(CorrelationData.class));

        assertThatCode(() -> publisher.sendEmail(reserva(), "RESERVA_RECIBIDA", ACTOR, "traza-3"))
                .doesNotThrowAnyException();
        assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit)
                .doesNotThrowAnyException();

        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class),
                any(CorrelationData.class));
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
