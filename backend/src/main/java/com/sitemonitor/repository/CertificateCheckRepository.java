package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CertificateCheckRepository extends JpaRepository<CertificateCheck, Long> {

    // ── Kontrol Geçmişi v2 (domain anahtarlı, SSL): sayfalı aralık + hata filtresi + histogram ──
    Page<CertificateCheck> findByDomainAndCheckedAtBetween(String domain, String from, String to, Pageable p);
    Page<CertificateCheck> findByDomainAndStatusAndCheckedAtBetween(String domain, String status, String from, String to, Pageable p);
    long countByDomainAndCheckedAtBetween(String domain, String from, String to);
    long countByDomainAndStatusAndCheckedAtBetween(String domain, String status, String from, String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — hata = status = 'error'.
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.status = 'error' THEN 1 ELSE 0 END AS fail "
         + "    FROM certificate_checks c WHERE c.domain = :domain "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("domain") String domain, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);

    @Query("SELECT c FROM CertificateCheck c WHERE c.domain = :domain ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findTopByDomainOrderByCheckedAtDesc(@Param("domain") String domain, @Param("limit") int limit);

    /** All checks after the given ISO cutoff string, newest first — used for activity log. */
    @Query("SELECT c FROM CertificateCheck c WHERE c.checkedAt >= :cutoff ORDER BY c.checkedAt DESC")
    List<CertificateCheck> findByCheckedAtAfter(@Param("cutoff") String cutoff);

    // (findByCheckedAtAfterLimited + aktivite projeksiyonu kaldırıldı — getActivityRlog ölü koddu, silindi.)

    /** Domain başına özet: [domain, toplam, hata_sayısı] — cutoff'tan beri.
     *  uptime/overview için tablo-başı-döngü (domain × tüm-tablo) yerine tek gruplu sorgu. */
    @Query("SELECT c.domain, COUNT(c), SUM(CASE WHEN c.status = 'error' THEN 1L ELSE 0L END) "
         + "FROM CertificateCheck c WHERE c.checkedAt >= :cutoff AND (c.maintenance = false OR c.maintenance IS NULL) GROUP BY c.domain")
    List<Object[]> aggregateStatusCountsSince(@Param("cutoff") String cutoff);

    /** Haftalık izleme özeti: [domain, toplam, BAŞARILI] — domains ∩ [from,to]; başarı = status<>'error' (bakım hariç). */
    @Query("SELECT c.domain, COUNT(c), SUM(CASE WHEN c.status <> 'error' THEN 1L ELSE 0L END) "
         + "FROM CertificateCheck c WHERE c.domain IN :domains AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "AND (c.maintenance = false OR c.maintenance IS NULL) GROUP BY c.domain")
    List<Object[]> weeklyStatsByDomain(@Param("domains") java.util.Collection<String> domains,
                                       @Param("from") String from, @Param("to") String to);

    @Query("SELECT c FROM CertificateCheck c WHERE c.domain = :domain AND c.checkedAt >= :from AND c.checkedAt <= :to ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findByDomainAndDateRange(
        @Param("domain") String domain,
        @Param("from")   String from,
        @Param("to")     String to,
        @Param("limit")  int    limit
    );

    /** Domain rename: tüm geçmiş kontrol kayıtlarını yeni domain'e taşı.
     *  Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE CertificateCheck c SET c.domain = :newDomain WHERE c.domain = :oldDomain")
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);

    /** Kalıcı silme (purge): bir domain'in tüm geçmiş kontrol kayıtlarını sil.
     *  Caller'da @Transactional zorunlu. Döndürülen değer silinen satır sayısı. */
    @Modifying
    @Query("DELETE FROM CertificateCheck c WHERE c.domain = :domain")
    int deleteByDomain(@Param("domain") String domain);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM CertificateCheck c WHERE c.domain = :domain")
    List<Object[]> historyBounds(@Param("domain") String domain);

    /** Yanit suresi grafigi icin ham seri — diger yedi turle AYNI 4 kolonlu sekil:
     *  [checkedAt, responseMs, up, daysRemaining]. Sertifikada dogal bir "up" bool'u yok, durumdan
     *  turetilir (DnsRecordRepository'deki ayni cozum). 4. kolon yardimci seri: kalan gun trendi —
     *  responseMs kolonu yeni oldugu icin gecmis ms'siz, ama kalan gun 180 gunluk gecmisten dolu gelir. */
    @Query("SELECT c.checkedAt, c.responseMs, CASE WHEN c.status = 'error' THEN false ELSE true END, c.daysRemaining "
         + "FROM CertificateCheck c WHERE c.domain = :domain AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "ORDER BY c.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("domain") String domain, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);
}
