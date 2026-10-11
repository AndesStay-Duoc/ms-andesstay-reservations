package cl.andesstay.reservations.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "RESERVATIONS")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "res_seq")
    @SequenceGenerator(name = "res_seq", sequenceName = "SEQ_RESERVATIONS", allocationSize = 1)
    private Long id;

    @Column(name = "GUEST_ID", nullable = false)
    private String guestId;              // sub del JWT Azure AD

    @Column(name = "GUEST_EMAIL", nullable = false)
    private String guestEmail;

    @Column(name = "UNIT_ID", nullable = false)
    private Long unitId;

    @Column(name = "CHECK_IN", nullable = false)
    private LocalDate checkIn;

    @Column(name = "CHECK_OUT", nullable = false)
    private LocalDate checkOut;

    @Column(name = "HUESPEDES", nullable = false)
    private Integer huespedes;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "TOTAL_PRICE", precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @Column(name = "CREATED_BY", length = 100)
    private String createdBy;            // usuario que creó (sub JWT)

    /**
     * Agrupa todos los eventos y mensajes del ciclo de vida de esta reserva.
     *
     * Es campo obligatorio del envelope común. Se genera al crear y se reutiliza en cada evento
     * posterior, así que basta filtrar por él para armar el timeline completo. No confundir con el
     * traceId, que cambia en cada petición HTTP. Ver
     * infra/docs/contracts/events/envelope.md.
     */
    @Column(name = "CORRELATION_ID", length = 36, updatable = false)
    private String correlationId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = ReservationStatus.CREADA;
        // Las reservas anteriores a este campo no lo traen; una persona es el minimo razonable.
        if (huespedes == null) huespedes = 1;
        // Red de seguridad: el servicio ya lo asigna al crear, pero una reserva sin correlationId
        // dejaría mensajes imposibles de correlacionar, y el campo es obligatorio en el contrato.
        if (correlationId == null) correlationId = java.util.UUID.randomUUID().toString();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
