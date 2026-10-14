package cl.andesstay.reservations.client;

import cl.andesstay.reservations.web.SinDisponibilidadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Cliente HTTP hacia ms-andesstay-catalog.
 *
 * DOS CAMBIOS DE FONDO respecto de la versión anterior.
 *
 * 1. Deja de ser «dispara y olvida». Antes terminaba en {@code .subscribe()} con el error
 *    registrado y tragado, así que si catalog estaba caído o rechazaba por falta de cupo, la
 *    reserva se confirmaba igual y la disponibilidad quedaba inconsistente en silencio. Eso
 *    incumplía la regla 4 del contrato de estados: confirmar sin cupo tiene que fallar y dejar la
 *    reserva en CREADA. Ahora la llamada se espera y un 409 de catalog se convierte en un 409 de
 *    reservas.
 *
 * 2. Propaga el {@code Authorization} del llamante. Los endpoints internos de catalog dejaron de
 *    ser anónimos, así que sin el token devolverían 401.
 */
@Slf4j
@Component
public class CatalogClient {

    /**
     * Tope de espera. Sin él, una llamada colgada retiene la transacción de la reserva y, con ella,
     * la conexión a la base.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public CatalogClient(@Value("${andesstay.catalog.base-url:http://catalog-svc:8082}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** Datos de la unidad que reservas necesita para validar capacidad y calcular el total. */
    public record Unidad(Long id, String name, Integer capacity, BigDecimal pricePerNight,
                         Integer availableSlots, Boolean active) {
    }

    public Unidad obtenerUnidad(Long unitId) {
        try {
            return webClient.get()
                    .uri("/api/v1/catalog/units/{id}", unitId)
                    .headers(this::propagarToken)
                    .retrieve()
                    .bodyToMono(Unidad.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException.NotFound e) {
            throw new IllegalArgumentException("La unidad " + unitId + " no existe.");
        }
    }

    /**
     * Compromete un cupo por cada noche de [desde, hasta).
     *
     * @param holdId identificador idempotente. Se usa el de la reserva, de modo que reintentar la
     *               confirmación no descuenta el cupo dos veces.
     * @throws SinDisponibilidadException si catalog responde 409
     */
    public void reservarCupo(Long unitId, LocalDate desde, LocalDate hasta, String holdId) {
        try {
            webClient.post()
                    .uri(uri -> uri.path("/api/v1/catalog/internal/units/{id}/hold")
                            .queryParam("from", desde)
                            .queryParam("to", hasta)
                            .queryParam("holdId", holdId)
                            .build(unitId))
                    .headers(this::propagarToken)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
            log.info("[CatalogClient] cupo comprometido · unidad={} holdId={} ({} a {})",
                    unitId, holdId, desde, hasta);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new SinDisponibilidadException(unitId);
            }
            throw e;
        }
    }

    /**
     * Devuelve los cupos de un hold.
     *
     * Aquí sí se registra el fallo sin propagarlo: la cancelación o el checkout ya ocurrieron y no
     * tiene sentido impedirlos porque catalog no responda. Un cupo que queda retenido de más es un
     * problema menor y recuperable; negarle el checkout al huésped, no. Queda en el log para poder
     * conciliarlo.
     */
    public void liberarCupo(String holdId) {
        try {
            webClient.post()
                    .uri("/api/v1/catalog/internal/holds/{holdId}/release", holdId)
                    .headers(this::propagarToken)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
            log.info("[CatalogClient] cupo liberado · holdId={}", holdId);
        } catch (Exception e) {
            log.error("[CatalogClient] NO se pudo liberar el cupo del holdId={}: {}. "
                    + "La operación continúa; queda un cupo retenido que hay que conciliar.",
                    holdId, e.getMessage());
        }
    }

    /**
     * Reenvía el bearer del usuario que originó la petición.
     *
     * Se toma del contexto de seguridad en lugar de recibirlo por parámetro para no arrastrar el
     * token por toda la cadena de llamadas del servicio.
     */
    private void propagarToken(HttpHeaders headers) {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken jwt) {
            headers.setBearerAuth(jwt.getToken().getTokenValue());
        }
    }
}
