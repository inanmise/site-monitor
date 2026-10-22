package com.sitemonitor.repository;

import com.sitemonitor.model.LoginIssueReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LoginIssueReportRepository extends JpaRepository<LoginIssueReport, Long> {

    /** Filtreli + sayfalı. status/tarih aralığı + serbest metin (q) opsiyonel (:p IS NULL OR ...). En yeni önce.
     *  impact (2026-09-28): çağıran "%,KOD,%" olarak sarar — etki CSV'sinin iki ucuna virgül eklenip aranır.
     *  q, çağıran tarafından "%küçükharf%" olarak sarılıp geçirilir (message + errorText + username içinde arar). */
    @Query("""
            SELECT r FROM LoginIssueReport r
             WHERE (:status IS NULL OR r.status = :status)
               AND (:source   IS NULL OR r.source = :source)
               AND (:category IS NULL OR r.category = :category)
               AND (:impact IS NULL OR CONCAT(',', r.impacts, ',') LIKE :impact)
               AND (:since  IS NULL OR r.reportedAt >= :since)
               AND (:until  IS NULL OR r.reportedAt <= :until)
               AND (:q IS NULL OR LOWER(r.message) LIKE :q OR LOWER(r.errorText) LIKE :q OR LOWER(r.username) LIKE :q)
             ORDER BY r.reportedAt DESC
            """)
    Page<LoginIssueReport> findFiltered(@Param("status") String status,
                                        @Param("source") String source,
                                        @Param("category") String category,
                                        @Param("impact") String impact,
                                        @Param("q") String q,
                                        @Param("since") String since,
                                        @Param("until") String until,
                                        Pageable pageable);

    /** Günlük özet (digest) — verilen andan beri gelen kaynak bazlı bildirimler, en yeni önce. */
    List<LoginIssueReport> findBySourceAndReportedAtGreaterThanEqualOrderByReportedAtDesc(String source, String since);

    /**
     * "Bildirimlerim" (2026-09-26): kullanıcı adı büyük/küçük harf duyarsız eşleşen, OTURUM İÇİNDE oluşan kayıtlar —
     * USER_REPORT + CLIENT_ERROR (kullanıcı adı sunucudaki oturumdan yazılır). Giriş sayfasından (LOGIN, oturumsuz)
     * yazılan kayıtlar LİSTELENMEZ (2026-10-22, hata avı AUTH-13; varsayılan karar): orada kullanıcı adı ziyaretçinin
     * YAZDIĞI addır — herkes başkasının listesine kendi metni ve e-postasıyla kayıt bırakabiliyordu. Durum süzgeci opsiyonel.
     * Kullanıcı adı OTURUMDAN gelir (controller); istek parametresinden asla. id eşitlik bozucu: aynı saniyedeki kayıtlar
     * sayfa sınırında yinelenmez.
     */
    @Query("""
            SELECT r FROM LoginIssueReport r
             WHERE LOWER(r.username) = :username
               AND r.source IN ('USER_REPORT', 'CLIENT_ERROR')
               AND (:status IS NULL OR r.status = :status)
             ORDER BY r.reportedAt DESC, r.id DESC
            """)
    Page<LoginIssueReport> findMine(@Param("username") String usernameLower,
                                    @Param("status") String status,
                                    Pageable pageable);

    /** "Bildirimlerim" durum sayaçları — {@link #findMine} ile AYNI kaynak süzgeci. */
    @Query("""
            SELECT r.status, COUNT(r) FROM LoginIssueReport r
             WHERE LOWER(r.username) = :username
               AND r.source IN ('USER_REPORT', 'CLIENT_ERROR')
             GROUP BY r.status
            """)
    List<Object[]> countMineByStatus(@Param("username") String usernameLower);

    /** Durum kırılımı (opsiyonel tarih aralığı) — sayaç kartları için. */
    @Query("""
            SELECT r.status, COUNT(r) FROM LoginIssueReport r
             WHERE (:since IS NULL OR r.reportedAt >= :since)
               AND (:until IS NULL OR r.reportedAt <= :until)
             GROUP BY r.status
            """)
    List<Object[]> countByStatus(@Param("since") String since, @Param("until") String until);
}
