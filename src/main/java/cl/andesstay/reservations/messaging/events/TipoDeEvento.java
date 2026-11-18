package cl.andesstay.reservations.messaging.events;

import cl.andesstay.reservations.domain.ReservationStatus;

/**
 * El vocabulario de los eventos de Kafka, en un solo sitio.
 *
 * POR QUE EXISTE ESTA CLASE. Hasta ahora convivian tres vocabularios para la misma cosa: el
 * publicador mandaba "CREATED" al crear pero el nombre del enum en castellano en cada transicion
 * ("CONFIRMADA", "EN_ESTADIA"...), mientras el contrato de infra/docs/contracts/events/kafka.md
 * hablaba de "reservation.created" y "reservation.confirmed". Los consumidores filtraban por el
 * literal que les tocara, y la consulta de ocupacion activa de ms-andesstay-report llego a tener
 * un parche que aceptaba "EN_ESTADIA" y "EN_ESTADÍA" a la vez. Un enum resuelve el problema de
 * raiz: el nombre que viaja por el tema se deriva del estado y no se escribe a mano en ninguna
 * parte.
 *
 * El nombre que se serializa es el del contrato, en minusculas y con punto. El estado del dominio
 * sigue en castellano, que es como lo nombra el caso; la traduccion vive aqui y solo aqui.
 */
public enum TipoDeEvento {

    CREADA(ReservationStatus.CREADA, "reservation.created"),
    CONFIRMADA(ReservationStatus.CONFIRMADA, "reservation.confirmed"),
    CHECKIN_PENDIENTE(ReservationStatus.CHECKIN_PENDIENTE, "reservation.checkin_pending"),
    EN_ESTADIA(ReservationStatus.EN_ESTADIA, "reservation.in_stay"),
    CHECKOUT(ReservationStatus.CHECKOUT, "reservation.checked_out"),
    CANCELADA(ReservationStatus.CANCELADA, "reservation.cancelled");

    private final ReservationStatus estado;
    private final String nombreEnElTema;

    TipoDeEvento(ReservationStatus estado, String nombreEnElTema) {
        this.estado = estado;
        this.nombreEnElTema = nombreEnElTema;
    }

    /** El valor del campo "type" del envelope. */
    public String nombreEnElTema() {
        return nombreEnElTema;
    }

    public ReservationStatus estado() {
        return estado;
    }

    /**
     * El tipo de evento que corresponde a un estado.
     *
     * No lleva rama por defecto a proposito: si el caso incorpora un estado nuevo, el compilador
     * no avisa pero esta busqueda falla de inmediato en la primera transicion, y eso es preferible
     * a publicar un evento con un "type" vacio que los consumidores ignorarian en silencio.
     */
    public static TipoDeEvento de(ReservationStatus estado) {
        for (TipoDeEvento tipo : values()) {
            if (tipo.estado == estado) {
                return tipo;
            }
        }
        throw new IllegalArgumentException(
                "No hay tipo de evento definido para el estado " + estado
                        + ": agregalo a TipoDeEvento y al contrato de kafka.md.");
    }
}
