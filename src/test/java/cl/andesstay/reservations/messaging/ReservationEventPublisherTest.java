package cl.andesstay.reservations.messaging;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.events.ReservationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El contrato del evento que sale hacia Kafka: el tema, la clave de particion y el envelope.
 *
 * Se carga solo el publicador, sin base de datos ni broker, pero con el application.yml real: si
 * alguien renombra andesstay.kafka.topics.reservations-events, falla aqui y no en ejecucion con un
 * tema creado al vuelo que nadie consume.
 *
 * La bandera se enciende por propiedad de test porque en application.yml esta en false: lo que se
 * prueba es lo que ocurre CUANDO publica, y hay un caso aparte para cuando no debe hacerlo.
 */
@SpringBootTest(classes = ReservationEventPublisher.class)
@TestPropertySource(properties = "andesstay.events.kafka.enabled=true")
class ReservationEventPublisherTest {

    private static final Actor ACTOR = Actor.de("sub-operador", "Operador");

    @Autowired
    private ReservationEventPublisher publisher;

    @MockBean
    private KafkaTemplate<String, ReservationEvent> kafkaTemplate;

    @Nested
    @DisplayName("El envelope lleva lo que el contrato promete")
    class Envelope {

        @Test
        @DisplayName("La creacion publica reservation.created sin estado anterior")
        void creacion() {
            publicar(reserva(ReservationStatus.CREADA), null, null);

            ReservationEvent evento = capturado();
            assertThat(evento.type()).isEqualTo("reservation.created");
            assertThat(evento.source()).isEqualTo("ms-andesstay-reservations");
            assertThat(evento.eventId()).isNotBlank();
            assertThat(evento.timestamp()).isNotNull();
            assertThat(evento.correlationId()).isEqualTo("corr-1");
            assertThat(evento.actor().role()).isEqualTo("Operador");
            assertThat(evento.payload().estadoAnterior()).isNull();
            assertThat(evento.payload().reservationId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Una transicion lleva el estado del que venia")
        void transicion() {
            publicar(reserva(ReservationStatus.CONFIRMADA), ReservationStatus.CREADA, null);

            assertThat(capturado().payload().estadoAnterior()).isEqualTo("CREADA");
        }

        @Test
        @DisplayName("El motivo solo viaja en la cancelacion")
        void motivoSoloEnCancelacion() {
            publicar(reserva(ReservationStatus.CANCELADA), ReservationStatus.CREADA, "El huesped desistio");
            assertThat(capturado().payload().motivo()).isEqualTo("El huesped desistio");

            publicar(reserva(ReservationStatus.CONFIRMADA), ReservationStatus.CREADA, "ruido");
            assertThat(capturado().payload().motivo()).isNull();
        }

        @Test
        @DisplayName("La duracion de la estadia solo viaja en el checkout")
        void duracionSoloEnCheckout() {
            publicar(reserva(ReservationStatus.CHECKOUT), ReservationStatus.EN_ESTADIA, null);
            // Del 10 al 13 de marzo son tres noches.
            assertThat(capturado().payload().duracionEstadiaHoras()).isEqualTo(72L);

            publicar(reserva(ReservationStatus.EN_ESTADIA), ReservationStatus.CHECKIN_PENDIENTE, null);
            assertThat(capturado().payload().duracionEstadiaHoras()).isNull();
        }
    }

    @Nested
    @DisplayName("El vocabulario sale de TipoDeEvento y no de literales")
    class Vocabulario {

        @ParameterizedTest(name = "{0} publica {1}")
        @CsvSource({
                "CREADA,            reservation.created",
                "CONFIRMADA,        reservation.confirmed",
                "CHECKIN_PENDIENTE, reservation.checkin_pending",
                "EN_ESTADIA,        reservation.in_stay",
                "CHECKOUT,          reservation.checked_out",
                "CANCELADA,         reservation.cancelled"
        })
        void cadaEstadoTieneSuNombreEnElTema(ReservationStatus estado, String esperado) {
            publicar(reserva(estado), null, null);

            assertThat(capturado().type()).isEqualTo(esperado);
        }
    }

    @Nested
    @DisplayName("La particion se elige por reserva")
    class Clave {

        @Test
        @DisplayName("La clave es el id de la reserva, para que sus eventos lleguen en orden")
        void claveEsElId() {
            publicar(reserva(ReservationStatus.CONFIRMADA), ReservationStatus.CREADA, null);

            ArgumentCaptor<String> clave = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(anyString(), clave.capture(), any(ReservationEvent.class));
            assertThat(clave.getValue()).isEqualTo("42");
        }

        @Test
        @DisplayName("El tema es el que declara application.yml")
        void temaConfigurado() {
            publicar(reserva(ReservationStatus.CONFIRMADA), ReservationStatus.CREADA, null);

            ArgumentCaptor<String> tema = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(tema.capture(), anyString(), any(ReservationEvent.class));
            assertThat(tema.getValue()).isEqualTo("reservations.events");
        }
    }

    // ── Apoyo ────────────────────────────────────────────────────────────────

    private void publicar(Reservation reserva, ReservationStatus anterior, String motivo) {
        when(kafkaTemplate.send(anyString(), anyString(), any(ReservationEvent.class)))
                .thenReturn(new CompletableFuture<SendResult<String, ReservationEvent>>());

        publisher.publishReservationEvent(reserva, anterior, motivo, ACTOR, "traza-1");
    }

    private ReservationEvent capturado() {
        ArgumentCaptor<ReservationEvent> captor = ArgumentCaptor.forClass(ReservationEvent.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce())
                .send(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static Reservation reserva(ReservationStatus estado) {
        return Reservation.builder()
                .id(42L)
                .guestId("sub-huesped")
                .guestEmail("huesped@andesstay.cl")
                .unitId(7L)
                .checkIn(LocalDate.of(2026, 3, 10))
                .checkOut(LocalDate.of(2026, 3, 13))
                .huespedes(2)
                .status(estado)
                .totalPrice(new BigDecimal("135000.00"))
                .correlationId("corr-1")
                .build();
    }
}
