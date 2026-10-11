package cl.andesstay.reservations.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Máquina de estados de la reserva. Contrato canónico en
 * infra/docs/contracts/estados.md.
 *
 * <pre>
 * CREADA ──► CONFIRMADA ──► CHECKIN_PENDIENTE ──► EN_ESTADIA ──► CHECKOUT
 *    │            │                 │
 *    └────────────┴─────────────────┴──► CANCELADA
 * </pre>
 *
 * NOTA DE CODIFICACIÓN. El valor es {@code EN_ESTADIA} SIN TILDE, para evitar problemas de
 * encoding en JSON, en parámetros de URL y en la base. Antes el enum llevaba tilde, lo que además
 * de esos problemas obligaba a una migración: con {@code @Enumerated(EnumType.STRING)} las filas
 * guardan el literal, así que renombrar sin actualizar los datos hace que Hibernate falle al LEER
 * las reservas antiguas. La sentencia está en el documento de despliegue:
 *
 * <pre>UPDATE reservations SET status = 'EN_ESTADIA' WHERE status = 'EN_ESTADÍA';</pre>
 *
 * El deserializador acepta igualmente la forma acentuada, por compatibilidad con el enunciado del
 * caso y con cualquier cliente que la tuviera cableada, pero la API siempre responde sin tilde.
 */
public enum ReservationStatus {
    CREADA,
    CONFIRMADA,
    CHECKIN_PENDIENTE,
    EN_ESTADIA,
    CHECKOUT,
    CANCELADA;

    /** Acepta tanto EN_ESTADIA como EN_ESTADÍA en la entrada. */
    @JsonCreator
    public static ReservationStatus desde(String valor) {
        if (valor == null) {
            return null;
        }
        String normalizado = valor.trim().toUpperCase().replace('Í', 'I');
        return ReservationStatus.valueOf(normalizado);
    }

    /**
     * Valida la transición propuesta.
     *
     * {@code CHECKIN_PENDIENTE → CANCELADA} es la transición 7 del contrato. Antes faltaba aquí,
     * aunque el servicio ya tenía la rama que libera el cupo para ese caso: una reserva con el
     * huésped por llegar no se podía anular.
     */
    public boolean canTransitionTo(ReservationStatus next) {
        return switch (this) {
            case CREADA            -> next == CONFIRMADA || next == CANCELADA;
            case CONFIRMADA        -> next == CHECKIN_PENDIENTE || next == CANCELADA;
            case CHECKIN_PENDIENTE -> next == EN_ESTADIA || next == CANCELADA;
            case EN_ESTADIA        -> next == CHECKOUT;
            // CHECKOUT y CANCELADA son terminales: de ellos no sale ninguna transición.
            default                -> false;
        };
    }

    /** El cupo está comprometido en estos estados, y por tanto hay que devolverlo al salir. */
    public boolean comprometeCupo() {
        return this == CONFIRMADA || this == CHECKIN_PENDIENTE || this == EN_ESTADIA;
    }
}
