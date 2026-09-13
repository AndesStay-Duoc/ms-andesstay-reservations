package cl.andesstay.reservations.repository;

import cl.andesstay.reservations.domain.Reservation;
import cl.andesstay.reservations.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatus(ReservationStatus status);

    List<Reservation> findByGuestId(String guestId);

    @Query("""
        SELECT r FROM Reservation r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:from   IS NULL OR r.checkIn  >= :from)
          AND (:to     IS NULL OR r.checkOut <= :to)
        ORDER BY r.createdAt DESC
    """)
    List<Reservation> search(
            @Param("status") ReservationStatus status,
            @Param("from")   LocalDate from,
            @Param("to")     LocalDate to
    );

    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.unitId = :unitId AND r.status NOT IN ('CHECKOUT','CANCELADA') AND r.checkIn < :checkOut AND r.checkOut > :checkIn")
    long countOverlapping(@Param("unitId") Long unitId,
                          @Param("checkIn") LocalDate checkIn,
                          @Param("checkOut") LocalDate checkOut);
}
