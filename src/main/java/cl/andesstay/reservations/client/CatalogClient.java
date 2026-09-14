package cl.andesstay.reservations.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Cliente HTTP interno para notificar al ms-andesstay-catalog
 * cuando debe ajustar la disponibilidad de una unidad.
 *
 * delta = -1 → CONFIRMAR reserva (ocupa un cupo)
 * delta = +1 → CANCELAR o CHECKOUT (libera el cupo)
 *
 * Se usa WebClient (no bloqueante). Los errores se logean pero no
 * abortan la transacción principal para mantener disponibilidad eventual.
 */
@Slf4j
@Component
public class CatalogClient {

    private final WebClient webClient;

    public CatalogClient(@Value("${andesstay.catalog.base-url:http://catalog-svc:8082}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Ajusta los cupos de la unidad en catalog.
     * @param unitId ID de la unidad
     * @param delta  -1 para disminuir, +1 para aumentar
     */
    public void adjustSlots(Long unitId, int delta) {
        webClient.patch()
                .uri("/api/catalog/internal/units/{id}/slots?delta={delta}", unitId, delta)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.info("[CatalogClient] Cupos ajustados unitId={} delta={}", unitId, delta))
                .doOnError(e -> log.error("[CatalogClient] Error ajustando cupos unitId={} delta={}: {}", unitId, delta, e.getMessage()))
                .subscribe(); // disparo y olvido (fire-and-forget); disponibilidad eventual
    }
}
