package cl.andesstay.reservations.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A que brokers se conecta el servicio cuando nadie le dice nada.
 *
 * POR QUE EXISTE. El valor por defecto era localhost:9092, un solo broker. El bootstrap solo sirve
 * para descubrir el cluster, pero si esa unica direccion esta caida al arrancar, el servicio no
 * descubre nada aunque los otros dos brokers esten en pie. Con los tres, basta con que responda
 * cualquiera.
 *
 * POR QUE UN MockEnvironment. Esta vacio: no trae las variables del sistema ni las del CI. Asi el
 * test mira el application.yml tal como esta escrito y no lo que tenga definido la maquina que lo
 * ejecuta.
 */
class BootstrapPorDefectoTest {

    private static final String PROPIEDAD = "spring.kafka.bootstrap-servers";

    @Test
    @DisplayName("Sin KAFKA_BOOTSTRAP, el valor por defecto lista los tres brokers")
    void porDefectoLosTres() throws IOException {
        assertThat(entorno().getProperty(PROPIEDAD))
                .isEqualTo("localhost:9092,localhost:9093,localhost:9094");
    }

    @Test
    @DisplayName("Con KAFKA_BOOTSTRAP definida, manda la variable")
    void laVariableManda() throws IOException {
        MockEnvironment entorno = entorno();
        entorno.setProperty("KAFKA_BOOTSTRAP", "kafka1:29092,kafka2:29093,kafka3:29094");

        assertThat(entorno.getProperty(PROPIEDAD))
                .isEqualTo("kafka1:29092,kafka2:29093,kafka3:29094");
    }

    private static MockEnvironment entorno() throws IOException {
        MockEnvironment entorno = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(entorno.getPropertySources()::addLast);
        return entorno;
    }
}
