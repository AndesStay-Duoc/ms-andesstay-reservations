package cl.andesstay.reservations.controller;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    /** Roles que operan reservas, en orden de privilegio: el primero presente es el del actor. */
    private static final List<String> ACTOR_ROLES = List.of("Admin", "Operador", "Cliente");

    private final ReservationService service;

    /** POST /api/reservations — Huésped o Recepcionista crean una reserva */
    @PostMapping
    @PreAuthorize("hasAnyRole('Cliente', 'Operador', 'Admin')")
    public ResponseEntity<Reservation> create(@Valid @RequestBody Reservation reservation,
                                              JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        String actorId   = jwt.getSubject();
        String actorRole = actorRole(authentication);
        // Si es huésped, fuerza que la reserva quede a su nombre
        if (actorRole.equals("Cliente")) {
            reservation.setGuestId(actorId);
            reservation.setGuestEmail(guestEmail(jwt));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(reservation, actorId, actorRole));
    }

    /** GET /api/reservations/{id} */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('Admin', 'Operador', 'Auditor') or " +
                  "(hasRole('Cliente') and @reservationSecurity.isOwner(#id, authentication))")
    public ResponseEntity<Reservation> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** GET /api/reservations?status=&from=&to= */
    @GetMapping
    @PreAuthorize("hasAnyRole('Admin', 'Operador')")
    public ResponseEntity<List<Reservation>> search(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.search(status, from, to));
    }

    /** PUT /api/reservations/{id}/status */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('Admin', 'Operador')")
    public ResponseEntity<Reservation> changeStatus(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body,
                                                    JwtAuthenticationToken authentication) {
        ReservationStatus newStatus = ReservationStatus.valueOf(body.get("status"));
        return ResponseEntity.ok(
                service.changeStatus(id, newStatus, authentication.getToken().getSubject(),
                        actorRole(authentication))
        );
    }

    /**
     * Rol con el que actúa el usuario, decidido por las authorities ya convertidas
     * (incluye el Cliente derivado para huéspedes autoregistrados) y no por el orden
     * del claim "roles". Un token sin rol de reservas no se trata como Cliente.
     */
    private static String actorRole(JwtAuthenticationToken authentication) {
        Set<String> authorities = AuthorityUtils.authorityListToSet(authentication.getAuthorities());
        return ACTOR_ROLES.stream()
                .filter(role -> authorities.contains("ROLE_" + role))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("El token no trae un rol que opere reservas."));
    }

    /**
     * Correo del huésped para el voucher. Se toma del claim opcional "email"; si Azure
     * no lo emite, se usa preferred_username solo cuando es un correo real (el UPN de
     * un invitado tiene la forma usuario_gmail.com#EXT#@tenant y no sirve).
     */
    private static String guestEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (StringUtils.hasText(email)) {
            return email;
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (StringUtils.hasText(username) && username.contains("@") && !username.contains("#EXT#")) {
            return username;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "El token no trae el correo del huésped: falta el claim opcional email en el access token.");
    }
}
