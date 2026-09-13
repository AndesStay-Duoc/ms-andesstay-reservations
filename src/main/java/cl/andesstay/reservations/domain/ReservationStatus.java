package cl.andesstay.reservations.domain;

/**
 * Máquina de estados de la reserva.
 *
 * CREADA → CONFIRMADA → CHECKIN_PENDIENTE → EN_ESTADÍA → CHECKOUT
 *        ↘ CANCELADA (desde CREADA o CONFIRMADA)
 */
public enum ReservationStatus {
    CREADA,
    CONFIRMADA,
    CHECKIN_PENDIENTE,
    EN_ESTADÍA,
    CHECKOUT,
    CANCELADA;

    /** Valida que la transición propuesta sea válida según las reglas de negocio. */
    public boolean canTransitionTo(ReservationStatus next) {
        return switch (this) {
            case CREADA           -> next == CONFIRMADA || next == CANCELADA;
            case CONFIRMADA       -> next == CHECKIN_PENDIENTE || next == CANCELADA;
            case CHECKIN_PENDIENTE -> next == EN_ESTADÍA;
            case EN_ESTADÍA       -> next == CHECKOUT;
            default               -> false;  // CHECKOUT y CANCELADA son terminales
        };
    }
}
