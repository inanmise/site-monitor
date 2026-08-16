package com.sitemonitor.repository;

import com.sitemonitor.model.NetworkOutageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NetworkOutageEventRepository extends JpaRepository<NetworkOutageEvent, Long> {

    Optional<NetworkOutageEvent> findFirstByStatusOrderByIdDesc(String status);

    /** İzleme sweep'lerinin bastırma olayı — sertifika sweep'inin olayıyla karışmasın diye kaynağa göre. */
    Optional<NetworkOutageEvent> findFirstBySourceAndStatusOrderByIdDesc(String source, String status);

    /**
     * SERTİFİKA sweep'inin açık olayı. {@code source IS NULL} şart: 2026-08'den önce yazılmış
     * kayıtlarda kaynak alanı yoktur ve hepsi sertifika sweep'ine aittir.
     *
     * <p>Kapsamlama olmadan sertifika sweep'i, izleme sweep'inin (KEYWORD/PORT…) daha yeni açık
     * olayını "kendi kesintisi" sanıp yanlışlıkla RESOLVED'a çekerdi — iki olgu aynı tabloyu
     * paylaştığı için bu sessiz bir veri bozulması olurdu.
     */
    @Query("SELECT e FROM NetworkOutageEvent e WHERE e.status = :status "
         + "AND (e.source IS NULL OR e.source = 'CERT') ORDER BY e.id DESC")
    List<NetworkOutageEvent> findCertByStatus(@Param("status") String status, Pageable pageable);

    @Query("SELECT e FROM NetworkOutageEvent e ORDER BY e.id DESC")
    List<NetworkOutageEvent> findRecent(Pageable pageable);
}
