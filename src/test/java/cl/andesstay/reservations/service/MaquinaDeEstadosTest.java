package cl.andesstay.reservations.service;

import cl.andesstay.reservations.client.CatalogClient;
import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.messaging.NotificationPublisher;
import cl.andesstay.reservations.messaging.ReservationEventPublisher;
import cl.andesstay.reservations.repository.ReservationRepository;
import cl.andesstay.reservations.web.SinDisponibilidadException;
import cl.andesstay.reservations.web.TransicionInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ítem 5.4 del checklist: los nueve casos que
 * infra/docs/contracts/estados.md declara obligatorios.
 *
 * Son reglas de negocio, no de transporte, así que se prueban contra el servicio con los
 * colaboradores simulados. La capa HTTP ya está cubierta por la batería de Postman; lo que aquí se
 * fija es que la máquina de estados no se pueda saltar aunque alguien llame al servicio desde otro
 * sitio.
 *
 * Las excepciones que se esperan son las que el @ControllerAdvice traduce:
 * TransicionInvalida y SinDisponibilidad a 409, AccessDenied a 403.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaquinaDeEstadosTest {

    private static final Long ID = 42L;
    private static final String DUENO = "sub-del-huesped";
    private static final LocalDate ENTRADA = LocalDate.of(2026, 11, 10);
    private static final LocalDate SALIDA = LocalDate.of(2026, 11, 13);

    @Mock
    private ReservationRepository repository;
    @Mock
    private NotificationPublisher notificationPublisher;
    @Mock
    private ReservationEventPublisher eventPublisher;
    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private ReservationService service;

    @BeforeEach
    void guardarDevuelveLoQueRecibe() {
        when(repository.save(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(catalogClient.obtenerUnidad(anyLong())).thenReturn(
                new CatalogClient.Unidad(1L, "Cabana Coigue", 4, new BigDecimal("45000"), 2, true));
    }

    /** Reserva en el estado pedido, ya persistida. */
    private Reservation reservaEn(ReservationStatus estado) {
        Reservation r = Reservation.builder()
                .id(ID)
                .guestId(DUENO)
                .guestEmail("huesped@example.cl")
                .unitId(1L)
                .checkIn(ENTRADA)
                .checkOut(SALIDA)
                .huespedes(2)
                .status(estado)
                .correlationId("corr-0001")
                .build();
        when(repository.findById(ID)).thenReturn(Optional.of(r));
        return r;
    }

    // ── 1 y 2 · Confirmar, con cupo y sin él ─────────────────────────────────

    @Test
    @DisplayName("1 · CREADA a CONFIRMADA con cupo")
    void confirmarConCupo() {
        reservaEn(ReservationStatus.CREADA);

        Reservation r = service.changeStatus(ID, ReservationStatus.CONFIRMADA, "op-1", "Operador");

        assertEquals(ReservationStatus.CONFIRMADA, r.getStatus());
        verify(catalogClient).reservarCupo(eq(1L), eq(ENTRADA), eq(SALIDA), anyString());
        verify(notificationPublisher).sendEmail(any(), eq("RESERVA_CONFIRMADA"), any(), anyString());
        verify(notificationPublisher).sendVoucherRequest(any(), eq("VOUCHER"), any(), anyString());
    }

    @Test
    @DisplayName("2 · CREADA a CONFIRMADA sin cupo: falla y la reserva SIGUE en CREADA")
    void confirmarSinCupo() {
        Reservation r = reservaEn(ReservationStatus.CREADA);
        doThrow(new SinDisponibilidadException(1L))
                .when(catalogClient).reservarCupo(anyLong(), any(), any(), anyString());

        assertThrows(SinDisponibilidadException.class,
                () -> service.changeStatus(ID, ReservationStatus.CONFIRMADA, "op-1", "Operador"));

        // Es la mitad que importa de este caso, y la que resuelve el overbooking del enunciado:
        // el cupo se compromete ANTES de persistir el estado, así que un rechazo de catalog deja
        // la reserva intacta.
        assertEquals(ReservationStatus.CREADA, r.getStatus());
        verify(repository, never()).save(any());
        verify(notificationPublisher, never()).sendEmail(any(), anyString(), any(), anyString());
    }

    // ── 3 y 4 · No se puede saltar ningún paso ───────────────────────────────

    @Test
    @DisplayName("3 · CREADA a CHECKIN_PENDIENTE: no hay check-in sin confirmar")
    void noHayCheckinSinConfirmar() {
        reservaEn(ReservationStatus.CREADA);

        TransicionInvalidaException e = assertThrows(TransicionInvalidaException.class,
                () -> service.changeStatus(ID, ReservationStatus.CHECKIN_PENDIENTE, "op-1", "Operador"));

        assertEquals(ReservationStatus.CREADA, e.getDesde());
        assertEquals(ReservationStatus.CHECKIN_PENDIENTE, e.getHacia());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("4 · CREADA a EN_ESTADIA: tampoco saltándose dos pasos")
    void noSePuedeSaltarDosPasos() {
        reservaEn(ReservationStatus.CREADA);

        assertThrows(TransicionInvalidaException.class,
                () -> service.changeStatus(ID, ReservationStatus.EN_ESTADIA, "op-1", "Operador"));
    }

    // ── 5 · La ruta feliz completa ───────────────────────────────────────────

    @Test
    @DisplayName("5 · ruta feliz completa hasta CHECKOUT")
    void rutaFeliz() {
        Reservation r = reservaEn(ReservationStatus.CREADA);

        assertEquals(ReservationStatus.CONFIRMADA,
                service.changeStatus(ID, ReservationStatus.CONFIRMADA, "op", "Operador").getStatus());
        assertEquals(ReservationStatus.CHECKIN_PENDIENTE,
                service.changeStatus(ID, ReservationStatus.CHECKIN_PENDIENTE, "op", "Operador").getStatus());
        assertEquals(ReservationStatus.EN_ESTADIA,
                service.changeStatus(ID, ReservationStatus.EN_ESTADIA, "op", "Operador").getStatus());
        assertEquals(ReservationStatus.CHECKOUT,
                service.changeStatus(ID, ReservationStatus.CHECKOUT, "op", "Operador").getStatus());

        assertEquals(ReservationStatus.CHECKOUT, r.getStatus());
        // El cupo se toma al confirmar y se devuelve al salir, una vez cada cosa.
        verify(catalogClient).reservarCupo(anyLong(), any(), any(), anyString());
        verify(catalogClient).liberarCupo(anyString());
        verify(notificationPublisher).sendHousekeepingTicket(any(), eq("PREPARACION"), any(), anyString());
        verify(notificationPublisher).sendHousekeepingTicket(any(), eq("LIMPIEZA"), any(), anyString());
    }

    // ── 6 y 7 · Los dos estados terminales ───────────────────────────────────

    @Nested
    @DisplayName("6 y 7 · desde un estado terminal no sale ninguna transición")
    class EstadosTerminales {

        @ParameterizedTest
        @EnumSource(ReservationStatus.class)
        @DisplayName("CHECKOUT a cualquier estado")
        void desdeCheckout(ReservationStatus destino) {
            reservaEn(ReservationStatus.CHECKOUT);

            assertThrows(TransicionInvalidaException.class,
                    () -> service.changeStatus(ID, destino, "op", "Operador"),
                    "CHECKOUT es terminal, pero aceptó pasar a " + destino);
        }

        @ParameterizedTest
        @EnumSource(ReservationStatus.class)
        @DisplayName("CANCELADA a cualquier estado")
        void desdeCancelada(ReservationStatus destino) {
            reservaEn(ReservationStatus.CANCELADA);

            assertThrows(TransicionInvalidaException.class,
                    () -> service.changeStatus(ID, destino, "op", "Operador"),
                    "CANCELADA es terminal, pero aceptó pasar a " + destino);
        }
    }

    // ── 8 y 9 · Lo que un huésped NO puede hacer ─────────────────────────────

    @Test
    @DisplayName("8 · un huésped intentando confirmar recibe 403, no 409")
    void huespedNoConfirma() {
        reservaEn(ReservationStatus.CREADA);

        // AccessDenied y no TransicionInvalida: la transición es válida en sí misma, lo que falla
        // es quién la pide. Confirmar es de recepción.
        assertThrows(AccessDeniedException.class,
                () -> service.changeStatus(ID, ReservationStatus.CONFIRMADA, DUENO, "Cliente"));

        verify(catalogClient, never()).reservarCupo(anyLong(), any(), any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("9 · un huésped solo cancela mientras la reserva siga en CREADA")
    void huespedCancelaSoloEnCreada() {
        reservaEn(ReservationStatus.CREADA);

        Reservation r = service.changeStatus(ID, ReservationStatus.CANCELADA, DUENO, "Cliente");

        assertEquals(ReservationStatus.CANCELADA, r.getStatus());
        verify(notificationPublisher).sendEmail(any(), eq("RESERVA_CANCELADA"), any(), anyString());
    }

    @Test
    @DisplayName("9 bis · ya confirmada, el huésped no puede anularla por su cuenta")
    void huespedNoCancelaUnaConfirmada() {
        reservaEn(ReservationStatus.CONFIRMADA);

        // Hay cupo comprometido y, en un negocio real, condiciones de cancelación: pasa por
        // recepción. La propiedad de la reserva la comprueba @PreAuthorize en el controlador;
        // aquí se comprueba QUÉ puede hacer con ella.
        assertThrows(AccessDeniedException.class,
                () -> service.changeStatus(ID, ReservationStatus.CANCELADA, DUENO, "Cliente"));
    }

    @Test
    @DisplayName("cancelar una reserva confirmada devuelve el cupo")
    void cancelarConfirmadaDevuelveElCupo() {
        // Este caso detectó un defecto real: el estado anterior se consultaba sobre la entidad ya
        // mutada, así que la comparación era siempre falsa y el cupo nunca se restituía.
        reservaEn(ReservationStatus.CONFIRMADA);

        service.changeStatus(ID, ReservationStatus.CANCELADA, "op", "Operador");

        verify(catalogClient).liberarCupo(anyString());
    }

}
