package cl.andesstay.reservations.security;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reglas de autorización a nivel de dato para reservas.
 * Se usa desde @PreAuthorize como {@code @reservationSecurity.isOwner(#id, authentication)}.
 */
@Component("reservationSecurity")
@RequiredArgsConstructor
public class ReservationSecurity {

    private final ReservationRepository reservationRepository;

    /**
     * true si la reserva pertenece al usuario autenticado. Ver {@link #esDuenio}.
     */
    public boolean isOwner(Long reservationId, Authentication authentication) {
        if (reservationId == null || authentication == null
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        String subject = jwt.getSubject();
        String correo = CorreoDelToken.de(jwt);
        return reservationRepository.findById(reservationId)
                .map(reservation -> esDuenio(reservation, subject, correo))
                .orElse(false);
    }

    /**
     * Dueño de una reserva:
     *
     * <ul>
     *   <li>Con guestId (la creó el propio Cliente): solo la cuenta con ese sub. El correo no
     *       cuenta, para que nadie con el mismo correo en otra cuenta pueda tomarla.</li>
     *   <li>Sin guestId (la creó recepción a nombre de alguien sin cuenta conocida): quien tenga
     *       ese correo en su token, sin distinguir mayúsculas. Entra ID verifica el correo con
     *       un código al registrar la cuenta del huésped.</li>
     * </ul>
     *
     * Es el mismo criterio que ReservationRepository.findPropias usa para listar.
     */
    static boolean esDuenio(Reservation reservation, String subject, String correo) {
        if (reservation.getGuestId() != null) {
            return subject != null && subject.equals(reservation.getGuestId());
        }
        return correo != null
                && reservation.getGuestEmail() != null
                && correo.equalsIgnoreCase(reservation.getGuestEmail().trim());
    }
}
