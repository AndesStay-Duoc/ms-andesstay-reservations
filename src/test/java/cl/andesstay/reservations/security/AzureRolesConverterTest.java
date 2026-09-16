package cl.andesstay.reservations.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el servicio derive el rol Cliente con la misma regla que el BFF.
 */
class AzureRolesConverterTest {

    private final AzureRolesConverter converter = new AzureRolesConverter();

    private static Jwt token(Consumer<Map<String, Object>> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(claims)
                .build();
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void invitadoSinRolesConScope_recibeRolCliente() {
        Jwt jwt = token(c -> { c.put("acct", 1L); c.put("scp", "AndesStay.Access"); });
        assertThat(authorities(jwt)).containsExactly("ROLE_Cliente");
    }

    @Test
    void invitadoSinScopeDeLaApi_quedaSinAuthorities() {
        Jwt jwt = token(c -> { c.put("acct", 1L); c.put("scp", "User.Read"); });
        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void miembroSinRoles_quedaSinAuthorities() {
        Jwt jwt = token(c -> { c.put("acct", 0L); c.put("scp", "AndesStay.Access"); });
        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void invitadoConRolAsignado_conservaSuRolYNoRecibeCliente() {
        Jwt jwt = token(c -> {
            c.put("acct", 1L);
            c.put("scp", "AndesStay.Access");
            c.put("roles", List.of("Operador"));
        });
        assertThat(authorities(jwt)).containsExactly("ROLE_Operador");
    }
}
