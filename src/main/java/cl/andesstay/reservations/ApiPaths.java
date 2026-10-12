package cl.andesstay.reservations;

/**
 * Prefijos de las rutas HTTP, en un solo sitio.
 *
 * Se usan constantes y no @Value porque @RequestMapping exige una constante en tiempo de
 * compilacion: la concatenacion de dos `static final String` literales lo es, mientras que el valor
 * de un @Value se resuelve en ejecucion y no compilaria.
 *
 * POR QUE EL ORDEN ES /api/v1 Y NO /v1/api. La version califica a la API, no al revés, y es la
 * forma que usan practicamente todas las APIs publicas. Mas concreto: con /api/v1 el prefijo
 * comun de todo lo que es API es /api, asi que una sola regla de nginx o un solo predicado del
 * gateway cubre cualquier version; con /v1/api habria que repetir la regla por version. En develop
 * convivian las dos formas mas los servicios sin versionar, y esta tanda las unifica.
 *
 * POR QUE NO server.servlet.context-path NI spring.mvc.servlet.path. Las dos remapean el
 * DispatcherServlet completo, y Actuator se sirve a traves de el, asi que /actuator/health pasaria
 * a /api/v1/actuator/health y romperia el healthcheck del contenedor y el de docker compose. Con
 * una constante, Actuator queda fuera del prefijo POR CONSTRUCCION, que es el argumento decisivo.
 */
public final class ApiPaths {

    /** Version actual de la API. Una v2 se declara aqui y en ningun otro sitio. */
    public static final String V1 = "/api/v1";

    /** Reservas. Es el recurso central del caso. */
    public static final String RESERVATIONS = V1 + "/reservations";

    private ApiPaths() {
    }
}
