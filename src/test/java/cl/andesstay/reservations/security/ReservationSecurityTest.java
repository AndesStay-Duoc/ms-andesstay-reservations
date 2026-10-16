package cl.andesstay.reservations.security;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.repository.ReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Quién es dueño de una reserva. Es la regla que decide si un Cliente puede verla y cancelarla,
 * así que un error aquí expondría reservas ajenas.
 */
@ExtendWith(MockitoExtension.class)
class ReservationSecurityTest {

    private static final String SUB_ANA = "sub-ana";
    private static final String CORREO_ANA = "ana@gmail.com";

    @Mock
    private ReservationRepository repository;

    @InjectMocks
    private ReservationSecurity security;

    private static Reservation reserva(String guestId, String guestEmail) {
        return Reservation.builder().id(1L).guestId(guestId).guestEmail(guestEmail).build();
    }

    private static Jwt token(String sub, String email) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject(sub);
        if (email != null) {
            b.claim("email", email);
        }
        return b.build();
    }

    // ── Reserva creada por el propio Cliente (con guestId) ──────────────────

    @Test
    @DisplayName("Con guestId, el dueño es la cuenta con ese sub")
    void conGuestIdCuentaElSub() {
        assertTrue(ReservationSecurity.esDuenio(reserva(SUB_ANA, CORREO_ANA), SUB_ANA, CORREO_ANA));
    }

    @Test
    @DisplayName("Con guestId, otra cuenta con el mismo correo NO es dueña")
    void conGuestIdElCorreoNoBasta() {
        assertFalse(ReservationSecurity.esDuenio(reserva(SUB_ANA, CORREO_ANA), "sub-otra-cuenta", CORREO_ANA));
    }

    // ── Reserva creada por recepción (sin guestId) ──────────────────────────

    @Test
    @DisplayName("Sin guestId, la reconoce quien tiene ese correo, sin distinguir mayúsculas")
    void sinGuestIdCuentaElCorreo() {
        assertTrue(ReservationSecurity.esDuenio(reserva(null, " Ana@Gmail.com "), SUB_ANA, CORREO_ANA));
    }

    @Test
    @DisplayName("Sin guestId, un correo distinto no es dueño")
    void sinGuestIdOtroCorreo() {
        assertFalse(ReservationSecurity.esDuenio(reserva(null, CORREO_ANA), "sub-pedro", "pedro@gmail.com"));
    }

    @Test
    @DisplayName("Sin guestId y token sin correo, nadie es dueño")
    void sinGuestIdTokenSinCorreo() {
        assertFalse(ReservationSecurity.esDuenio(reserva(null, CORREO_ANA), SUB_ANA, null));
    }

    // ── isOwner, de punta a punta con el token ──────────────────────────────

    @Test
    @DisplayName("isOwner lee el correo del token para las reservas de recepción")
    void isOwnerConTokenReal() {
        when(repository.findById(1L)).thenReturn(Optional.of(reserva(null, CORREO_ANA)));
        assertTrue(security.isOwner(1L, new JwtAuthenticationToken(token(SUB_ANA, CORREO_ANA))));
    }

    @Test
    @DisplayName("isOwner devuelve false si la reserva no existe")
    void isOwnerReservaInexistente() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertFalse(security.isOwner(1L, new JwtAuthenticationToken(token(SUB_ANA, CORREO_ANA))));
    }

    // ── CorreoDelToken ──────────────────────────────────────────────────────

    @Test
    @DisplayName("El UPN de un invitado (#EXT#) no se toma como correo")
    void upnDeInvitadoNoEsCorreo() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(SUB_ANA)
                .claim("preferred_username", "ana_gmail.com#EXT#@andesstay.onmicrosoft.com").build();
        assertNull(CorreoDelToken.de(jwt));
    }

    @Test
    @DisplayName("Sin claim email, preferred_username sirve si es un correo real")
    void preferredUsernameComoRespaldo() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(SUB_ANA)
                .claim("preferred_username", "ana@gmail.com").build();
        assertEquals("ana@gmail.com", CorreoDelToken.de(jwt));
    }
}
