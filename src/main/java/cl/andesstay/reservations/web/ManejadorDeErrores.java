package cl.andesstay.reservations.web;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce las excepciones del dominio a los cuerpos exactos de
 * infra/docs/contracts/estados.md, sección «Respuestas de error».
 *
 * Antes no existía, así que los 409 y 404 que el contrato documenta salían como 500 con una traza
 * de Spring: el frontend no podía distinguir «no queda cupo» de «el backend se cayó», y por eso no
 * podía decírselo al usuario.
 */
@Slf4j
@RestControllerAdvice
public class ManejadorDeErrores {

    /** 409 · {"error":"TRANSICION_INVALIDA","desde":"CREADA","hacia":"EN_ESTADIA"} */
    @ExceptionHandler(TransicionInvalidaException.class)
    public ResponseEntity<Map<String, Object>> transicionInvalida(TransicionInvalidaException e) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("error", "TRANSICION_INVALIDA");
        cuerpo.put("desde", e.getDesde() == null ? null : e.getDesde().name());
        cuerpo.put("hacia", e.getHacia() == null ? null : e.getHacia().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(cuerpo);
    }

    /** 409 · {"error":"SIN_DISPONIBILIDAD","unitId":"..."} */
    @ExceptionHandler(SinDisponibilidadException.class)
    public ResponseEntity<Map<String, Object>> sinDisponibilidad(SinDisponibilidadException e) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("error", "SIN_DISPONIBILIDAD");
        cuerpo.put("unitId", e.getUnitId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(cuerpo);
    }

    /** 403 · {"error":"ROL_INSUFICIENTE"} */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> rolInsuficiente(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "ROL_INSUFICIENTE"));
    }

    /** 404 · {"error":"RESERVA_NO_ENCONTRADA"} */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> noEncontrada(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "RESERVA_NO_ENCONTRADA", "detalle", e.getMessage()));
    }

    /**
     * 400 · Estado no reconocido en el cuerpo, rango de fechas inválido, más huéspedes que
     * capacidad, unidad inexistente.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> peticionInvalida(IllegalArgumentException e) {
        String mensaje = e.getMessage() == null ? "" : e.getMessage();
        // valueOf de un enum desconocido llega hasta aquí; el contrato le da su propio código.
        String codigo = mensaje.startsWith("No enum constant") ? "ESTADO_INVALIDO" : "PETICION_INVALIDA";
        return ResponseEntity.badRequest().body(Map.of("error", codigo, "detalle", mensaje));
    }

    /** 400 · Falló la validación del cuerpo, campo por campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        Map<String, Object> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> campos.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDACION", "campos", campos));
    }

    /**
     * 409 · Reglas de negocio restantes, como el solapamiento de fechas.
     *
     * No se captura Exception a secas a propósito: un fallo inesperado tiene que seguir siendo un
     * 500 con su traza. Convertirlo todo en 409 escondería defectos reales.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> reglaDeNegocio(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "REGLA_DE_NEGOCIO", "detalle", e.getMessage()));
    }
}
