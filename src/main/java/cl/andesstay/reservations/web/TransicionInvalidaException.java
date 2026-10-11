package cl.andesstay.reservations.web;

import cl.andesstay.reservations.domain.ReservationStatus;
import lombok.Getter;

/**
 * La transicion pedida no esta en la tabla del contrato.
 *
 * Se traduce en {@code 409 TRANSICION_INVALIDA} con el desde y el hacia, que es el cuerpo exacto
 * que fija infra/docs/contracts/estados.md.
 */
@Getter
public class TransicionInvalidaException extends RuntimeException {

    private final ReservationStatus desde;
    private final ReservationStatus hacia;

    public TransicionInvalidaException(ReservationStatus desde, ReservationStatus hacia) {
        super("Transicion invalida: " + desde + " -> " + hacia);
        this.desde = desde;
        this.hacia = hacia;
    }
}
