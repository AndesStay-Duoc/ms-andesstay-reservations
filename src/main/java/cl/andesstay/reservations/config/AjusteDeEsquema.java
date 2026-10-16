package cl.andesstay.reservations.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Cambios de esquema que ddl-auto=update no sabe aplicar sobre una base existente.
 *
 * POR QUÉ EXISTE. GUEST_ID nació NOT NULL. Hoy puede ir vacío: cuando recepción reserva a nombre
 * de alguien, el backend no conoce su cuenta y la reserva se reconoce por el correo. Hibernate,
 * con ddl-auto=update, crea las columnas que faltan pero NUNCA relaja una restricción que ya
 * existe, así que en una base desplegada antes de este cambio cada reserva de recepción seguiría
 * fallando al guardarse. No hay herramienta de migraciones en el proyecto (las tablas las crea
 * Hibernate y mysql/init corre una sola vez), de ahí este runner.
 *
 * ES IDEMPOTENTE: consulta information_schema y solo altera la columna si sigue siendo NOT NULL.
 * En una base nueva Hibernate ya la crea admitiendo NULL y esto no hace nada.
 *
 * NO BLOQUEA EL ARRANQUE: si falla (otra base, sin permiso de ALTER), lo registra y sigue. Las
 * reservas de los Clientes no dependen de esto.
 */
@Slf4j
@Configuration
public class AjusteDeEsquema {

    @Bean
    public ApplicationRunner permitirReservasSinCuenta(JdbcTemplate jdbc) {
        return args -> {
            try {
                // Sin distinguir mayúsculas: @Table dice RESERVATIONS, pero la estrategia de nombres
                // de Spring Boot la crea en minúsculas, y en Linux MySQL sí distingue.
                List<String[]> columnas = jdbc.query("""
                        SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND LOWER(TABLE_NAME) = 'reservations'
                          AND LOWER(COLUMN_NAME) = 'guest_id'
                          AND IS_NULLABLE = 'NO'
                        """,
                        (rs, i) -> new String[]{
                                rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME"), rs.getString("COLUMN_TYPE")});

                for (String[] c : columnas) {
                    // Se conserva el tipo que ya tiene la columna; solo cambia la nulabilidad.
                    jdbc.execute("ALTER TABLE `" + c[0] + "` MODIFY `" + c[1] + "` " + c[2] + " NULL");
                    log.info("[Esquema] {}.{} ahora admite NULL: recepción puede reservar a nombre de un huésped sin cuenta",
                            c[0], c[1]);
                }
            } catch (DataAccessException e) {
                log.warn("[Esquema] No se pudo comprobar o relajar GUEST_ID: {}. Las reservas creadas por "
                        + "recepción fallarán hasta ejecutar a mano: "
                        + "ALTER TABLE reservations MODIFY guest_id VARCHAR(255) NULL", e.getMessage());
            }
        };
    }
}
