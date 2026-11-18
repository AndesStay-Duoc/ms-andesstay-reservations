package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.events.ReservationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lo que ocurre con la bandera apagada, que es el valor por omision.
 *
 * Va en una clase aparte porque la bandera se lee al construir el contexto, y dos contextos con
 * valores distintos no caben en la misma. El caso importa: durante meses el sistema se desplego
 * sin clúster de Kafka, y la garantia de que entonces el publicador no toca el broker es lo que
 * evita que cada cambio de estado de una reserva se quede esperando los 2 s de max.block.ms.
 */
@SpringBootTest(classes = ReservationEventPublisher.class)
@TestPropertySource(properties = "andesstay.events.kafka.enabled=false")
class ReservationEventPublisherApagadoTest {

    @Autowired
    private ReservationEventPublisher publisher;

    @MockBean
    private KafkaTemplate<String, ReservationEvent> kafkaTemplate;

    @Test
    @DisplayName("Con la bandera en false no se toca el broker")
    void noPublica() {
        Reservation reserva = Reservation.builder()
                .id(42L)
                .guestEmail("huesped@andesstay.cl")
                .unitId(7L)
                .checkIn(LocalDate.of(2026, 3, 10))
                .checkOut(LocalDate.of(2026, 3, 13))
                .huespedes(2)
                .status(ReservationStatus.CONFIRMADA)
                .correlationId("corr-1")
                .build();

        publisher.publishReservationEvent(
                reserva, ReservationStatus.CREADA, null, Actor.de("sub-operador", "Operador"), "traza");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}
