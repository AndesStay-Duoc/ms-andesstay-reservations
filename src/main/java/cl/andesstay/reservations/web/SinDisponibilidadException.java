package cl.andesstay.reservations.web;

import lombok.Getter;

/**
 * Catalog rechazo el hold por falta de cupo.
 *
 * Se traduce en {@code 409 SIN_DISPONIBILIDAD}. Es la regla 4 del contrato de estados y la que
 * resuelve el overbooking del caso: confirmar sin cupo falla y la reserva permanece en CREADA.
 */
@Getter
public class SinDisponibilidadException extends RuntimeException {

    private final Long unitId;

    public SinDisponibilidadException(Long unitId) {
        super("Sin disponibilidad para la unidad " + unitId);
        this.unitId = unitId;
    }
}
