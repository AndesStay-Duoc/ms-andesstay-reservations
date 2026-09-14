package cl.andesstay.reservations.security;

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
     * true si la reserva pertenece al usuario autenticado
     * (guestId guardado al crear la reserva = sub del JWT de Azure AD).
     */
    public boolean isOwner(Long reservationId, Authentication authentication) {
        if (reservationId == null || authentication == null
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        String subject = jwt.getSubject();
        return reservationRepository.findById(reservationId)
                .map(reservation -> subject != null && subject.equals(reservation.getGuestId()))
                .orElse(false);
    }
}
