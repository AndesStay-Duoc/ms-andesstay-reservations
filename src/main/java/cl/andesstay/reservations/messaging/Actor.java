package cl.andesstay.reservations.messaging;

import java.util.Map;

/**
 * Quién originó la acción. Es un campo obligatorio del envelope común; ver
 * infra/docs/contracts/events/envelope.md.
 *
 * @param userId sub del token de quien originó la acción
 * @param role   rol con el que actuó: Admin, Operador, Cliente o Auditor
 * @param tenant de qué superficie venía el token: staff o guest
 */
public record Actor(String userId, String role, String tenant) {

    public static final String TENANT_STAFF = "staff";
    public static final String TENANT_GUEST = "guest";

    /**
     * Deriva el tenant del rol.
     *
     * Hoy existe una sola app registration, así que el token no trae nada que distinga las dos
     * superficies por sí mismo. El rol sí: un Cliente es un huésped, y Admin, Operador y Auditor
     * son personal. Cuando los dos scopes estén en circulación, esto pasará a leerse del scope
     * efectivo en lugar de inferirse.
     */
    public static Actor de(String userId, String role) {
        String tenant = "Cliente".equals(role) ? TENANT_GUEST : TENANT_STAFF;
        return new Actor(userId, role, tenant);
    }

    /** Forma serializada dentro del envelope. */
    public Map<String, Object> comoMapa() {
        return Map.of(
                "userId", userId == null ? "" : userId,
                "role", role == null ? "" : role,
                "tenant", tenant);
    }
}
