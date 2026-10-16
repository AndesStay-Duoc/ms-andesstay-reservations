package cl.andesstay.reservations.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Correo del usuario según su access token de Azure AD.
 *
 * Se usa en dos sitios con el mismo criterio: al crear la reserva de un Cliente (para el voucher)
 * y al reconocer como suyas las reservas que recepción creó a su correo.
 */
public final class CorreoDelToken {

    private CorreoDelToken() {
    }

    /**
     * Claim opcional "email"; si Azure no lo emite, preferred_username solo cuando es un correo
     * real (el UPN de un invitado tiene la forma usuario_gmail.com#EXT#@tenant y no sirve).
     *
     * @return el correo sin espacios alrededor, o null si el token no trae ninguno utilizable
     */
    public static String de(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String email = jwt.getClaimAsString("email");
        if (StringUtils.hasText(email)) {
            return email.trim();
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (StringUtils.hasText(username) && username.contains("@") && !username.contains("#EXT#")) {
            return username.trim();
        }
        return null;
    }
}
