package cl.andesstay.reservations.messaging.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Los datos de la reserva que viajan dentro del envelope.
 *
 * Es un record y no un Map porque el consumidor tiene que poder confiar en los nombres y en los
 * tipos: con un Map, un campo mal escrito en el productor se convierte en un null en el consumidor
 * y nadie se entera hasta que un KPI sale en cero. Ademas permite que Jackson serialice y
 * deserialice sin configuracion adicional.
 *
 * Los campos opcionales van en null cuando no aplican: estadoAnterior no existe en la creacion,
 * motivo solo llega en la cancelacion y duracionEstadiaHoras solo en el checkout. El contrato los
 * describe asi en infra/docs/contracts/events/kafka.md.
 *
 * @param reservationId        identificador de la reserva; es tambien la clave de particion
 * @param unitId               unidad reservada
 * @param guestId              sub del token del huesped, o null si reservo recepcion a su nombre
 * @param guestEmail           correo del huesped, que es lo que identifica la reserva sin cuenta
 * @param fechaEntrada         fecha de entrada comprometida
 * @param fechaSalida          fecha de salida comprometida
 * @param huespedes            numero de personas
 * @param estado               estado al que acaba de pasar la reserva
 * @param estadoAnterior       estado del que venia, o null en la creacion
 * @param motivo               razon de la cancelacion; null en el resto de transiciones
 * @param tarifaTotal          total de la reserva, o null si la unidad no tenia tarifa
 * @param moneda               moneda de la tarifa
 * @param duracionEstadiaHoras horas comprometidas entre entrada y salida; solo en el checkout
 */
public record ReservationPayload(
        Long reservationId,
        Long unitId,
        String guestId,
        String guestEmail,
        LocalDate fechaEntrada,
        LocalDate fechaSalida,
        Integer huespedes,
        String estado,
        String estadoAnterior,
        String motivo,
        BigDecimal tarifaTotal,
        String moneda,
        Long duracionEstadiaHoras) {
}
