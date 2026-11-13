package com.sitemonitor.repository;

import com.sitemonitor.model.SqlQueryHistory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SqlQueryHistoryRepository extends JpaRepository<SqlQueryHistory, Long> {

    List<SqlQueryHistory> findTop50ByOrderByExecutedAtDesc();

    List<SqlQueryHistory> findTop50ByExecutedByOrderByExecutedAtDesc(String executedBy);

    /**
     * DB analitiği — pencere içindeki sorgu geçmişi (Java'da kullanıcı/SQL/zaman kümelemesi için).
     *
     * <p>TAVANLI ve EN YENİ-ÖNCE (2026-08-20 bellek denetimi). Eskiden LIMIT'siz ve ASC idi:
     * 30 günlük pencere tek bir List'e yükleniyordu, oysa sql-history retention varsayılanı
     * 365 GÜN — yani pencereyi retention sınırlamıyordu. Yoğun bir kurulumda tüm satırlar
     * (+ 9 ayrı toplayıcının ürettiği türev yapılar + @Cacheable payload'ı) aynı anda canlı olurdu.
     * Tavana takılırsa EN YENİ kayıtlar korunur; çağıran listeyi ASC'ye çevirir.
     */
    List<SqlQueryHistory> findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(String since, Limit limit);

    /**
     * SQL Playground geçmişi — sayfalı mod (2026-11-13, A4). Boş süzgeçler nötr değerle gelir (null bağlama yok — PostgreSQL
     * türsüz null parametreyi çözemez): {@code q} = {@code %}, gün sınırları {@code 0000} / {@code 9999}; {@code st}
     * 0 hepsi · 1 başarılı · 2 hatalı · 3 yavaş ({@code durationMs >= slow}). Sıralama çağıranın {@code Pageable}'ı
     * ({@code executedAt DESC, id DESC}).
     */
    @Query(value = "SELECT h FROM SqlQueryHistory h WHERE (:all = true OR h.executedBy = :user) "
            + "AND LOWER(h.sqlText) LIKE :q ESCAPE '!' AND h.executedAt >= :fromIso AND h.executedAt < :toIso "
            + "AND (:st = 0 OR (:st = 1 AND h.success = true) OR (:st = 2 AND h.success = false) "
            + "OR (:st = 3 AND h.durationMs >= :slow))",
           countQuery = "SELECT COUNT(h) FROM SqlQueryHistory h WHERE (:all = true OR h.executedBy = :user) "
            + "AND LOWER(h.sqlText) LIKE :q ESCAPE '!' AND h.executedAt >= :fromIso AND h.executedAt < :toIso "
            + "AND (:st = 0 OR (:st = 1 AND h.success = true) OR (:st = 2 AND h.success = false) "
            + "OR (:st = 3 AND h.durationMs >= :slow))")
    Page<SqlQueryHistory> search(@Param("all") boolean all, @Param("user") String user, @Param("q") String q,
                                 @Param("fromIso") String fromIso, @Param("toIso") String toIso, @Param("st") int st,
                                 @Param("slow") long slow, Pageable pageable);
}
