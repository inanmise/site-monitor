package com.sitemonitor.repository;

import com.sitemonitor.model.UptimeCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, Long> {

    // ── Kontrol Geçmişi v2 (domain+port anahtarlı): sayfalı aralık + hata filtresi + histogram ──
    Page<UptimeCheck> findByDomainAndPortAndCheckedAtBetween(String domain, int port, String from, String to, Pageable p);
    Page<UptimeCheck> findByDomainAndPortAndStatusNotAndCheckedAtBetween(String domain, int port, String status, String from, String to, Pageable p);
    long countByDomainAndPortAndCheckedAtBetween(String domain, int port, String from, String to);
    long countByDomainAndPortAndStatusNotAndCheckedAtBetween(String domain, int port, String status, String from, String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — hata = status <> 'up'.
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(u.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN u.status <> 'up' THEN 1 ELSE 0 END AS fail "
         + "    FROM uptime_checks u WHERE u.domain = :domain AND u.port = :port "
         + "     AND u.checked_at >= :from AND u.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("domain") String domain, @Param("port") int port,
                                    @Param("from") String from, @Param("to") String to, @Param("len") int len);
    Optional<UptimeCheck> findTopByDomainAndPortOrderByIdDesc(String domain, int port);

    /** Tek toplu sorgu — son N saatteki tüm HTTP kontrolleri (uptime overview http_ok hesabı için). */
    List<UptimeCheck> findByCheckedAtGreaterThanEqual(String since);

    /** Uptime overview http_ok: 24h TÜM satırları JVM'e yüklemek (500 domain'de ~144k satır) yerine
     *  domain başına [total, upCount] agregasyonu — httpOk = upCount == total. GROUP BY yalnız ≥1
     *  satırlı domain'i döner. idx_uc_domain_port_checked üzerinden. */
    @Query("SELECT u.domain, COUNT(u), SUM(CASE WHEN u.status = 'up' THEN 1 ELSE 0 END) "
         + "FROM UptimeCheck u WHERE u.checkedAt >= :since AND (u.maintenance = false OR u.maintenance IS NULL) GROUP BY u.domain")
    List<Object[]> aggregateHttpOkSince(@Param("since") String since);

    /** Her (domain,port) için en güncel uptime kontrolü — overview'da domain başına
     *  findTopByDomainAndPort... sorgusu yerine tek toplu sorgu (N+1 giderme). */
    // Her izlenen domain için en güncel uptime kontrolü. Eski MAX(id)+GROUP BY (domain,port) TÜM
    // tabloyu tarıyordu; küçük certificate_inventory'ye LATERAL join ile domain başına tek index-seek
    // (overview zaten yalnız inventory domain'lerini gösterir → semantik aynı; ~3.5sn → ~80ms).
    @Query(value = "SELECT c.* FROM certificate_inventory i CROSS JOIN LATERAL "
         + "(SELECT * FROM uptime_checks u WHERE u.domain = i.domain ORDER BY u.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<UptimeCheck> findLatestPerDomainPort();

    @Query("SELECT u FROM UptimeCheck u WHERE u.domain = :domain AND u.port = :port AND u.checkedAt >= :from AND u.checkedAt <= :to ORDER BY u.checkedAt DESC LIMIT :limit")
    List<UptimeCheck> findByDomainAndPortAndDateRange(
        @Param("domain") String domain,
        @Param("port")   int    port,
        @Param("from")   String from,
        @Param("to")     String to,
        @Param("limit")  int    limit
    );

    /** Haftalık erişilebilirlik raporu — pencere içi (UTC ISO) kontroller, kronolojik sıralı
     *  (up→down geçiş/kesinti tespiti için). checkedAt UTC ISO string. */
    List<UptimeCheck> findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(
        String domain, Integer port, String checkedAtStart, String checkedAtEnd);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM UptimeCheck c WHERE c.domain = :domain AND c.port = :port")
    List<Object[]> historyBounds(@Param("domain") String domain, @Param("port") int port);
}
