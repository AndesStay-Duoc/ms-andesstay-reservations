package cl.andesstay.reservations.controller;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import cl.andesstay.reservations.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService service;

    /** POST /api/reservations — Huésped o Recepcionista crean una reserva */
    @PostMapping
    @PreAuthorize("hasAnyRole('Cliente', 'Operador', 'Admin')")
    public ResponseEntity<Reservation> create(@Valid @RequestBody Reservation reservation,
                                              @AuthenticationPrincipal Jwt jwt) {
        String actorId   = jwt.getSubject();
        String actorRole = extractRole(jwt);
        // Si es huésped, fuerza que guestId sea el propio sub
        if (actorRole.equals("Cliente")) {
            reservation.setGuestId(actorId);
            reservation.setGuestEmail(jwt.getClaimAsString("email"));
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
                                                    @AuthenticationPrincipal Jwt jwt) {
        ReservationStatus newStatus = ReservationStatus.valueOf(body.get("status"));
        return ResponseEntity.ok(
                service.changeStatus(id, newStatus, jwt.getSubject(), extractRole(jwt))
        );
    }

    private String extractRole(Jwt jwt) {
        List<String> roles = jwt.getClaimAsList("roles");
        if (roles != null && !roles.isEmpty()) return roles.get(0);
        return "Cliente";
    }
}
