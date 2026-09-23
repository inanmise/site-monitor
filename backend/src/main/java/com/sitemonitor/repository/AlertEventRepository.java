package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    @Query("SELECT e FROM AlertEvent e WHERE e.domain = :domain AND e.alertType = :alertType AND e.resolved = false ORDER BY e.createdAt DESC")
    List<AlertEvent> findOpenAlerts(String domain, String alertType);

    /** En güncel açık alarm. Aynı (domain, alertType) için BİRDEN ÇOK açık alarm bulunursa (legacy veri veya
     *  withLock'ı aşan bir yarış) tekil-Optional sorgusu {@code IncorrectResultSizeDataAccessException} atıp
     *  o domain'in alarmlarını sessizce düşürürdü; bu List tabanlı sürüm en yenisini (createdAt DESC) döner. */
    default Optional<AlertEvent> findOpenAlert(String domain, String alertType) {
        List<AlertEvent> rows = findOpenAlerts(domain, alertType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Batch lookup — sweep'te N domain için N query yerine tek sorgu. */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.domain IN :domains")
    List<AlertEvent> findOpenByDomainIn(@Param("domains") Collection<String> domains);

    List<AlertEvent> findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();

    List<AlertEvent> findAllByOrderByCreatedAtDesc();
    /** Yönetici özeti (2026-09-12, #20): pencere içinde AÇILAN alarmlar (delta hesabı). */
    List<AlertEvent> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(String since);

    /** "Sizin için — bugün" son 24 saat şeridi (2026-09-23): pencere içinde ÇÖZÜLEN alarmlar. */
    List<AlertEvent> findByResolvedAtGreaterThanEqual(String since);

    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false ORDER BY e.alertLevel DESC, e.createdAt DESC")
    List<AlertEvent> findAllOpenOrderBySeverity();

    /** Genel Bakış kartı "şu an" şeridi (2026-09-19): alan başına EN SON alarm olayı (açık ya da çözülmüş) — tek sorgu. */
    @Query("SELECT a FROM AlertEvent a WHERE a.id IN (SELECT MAX(b.id) FROM AlertEvent b WHERE b.domain IS NOT NULL GROUP BY b.domain)")
    List<AlertEvent> findLatestPerDomain();

    List<AlertEvent> findByDomainOrderByCreatedAtDesc(String domain);

    List<AlertEvent> findByDomainAndResolvedFalse(String domain);

    /** Tip-kapsamlı açık alarm sorgusu — cert sweep'i sadece cert tiplerini,
     *  uptime recovery sadece ACCESSIBILITY'yi kapatabilsin diye. */
    List<AlertEvent> findByDomainAndAlertTypeInAndResolvedFalse(String domain, Collection<String> alertTypes);

    /** Kontrol Geçmişi v2: aralıkla KESİŞEN alarmlar (içinde AÇILAN veya içinde ÇÖZÜLEN) —
     *  satır↔alarm çıkarımsal eşlemesi client'ta yapılır (check kaydında alertEventId yok, bilinçli).
     *  createdAt/resolvedAt sabit-genişlik ISO → sözlüksel aralık. */
    @Query("SELECT e FROM AlertEvent e WHERE e.domain = :domainKey AND e.alertType IN :types "
         + "AND ((e.createdAt >= :from AND e.createdAt <= :to) "
         + "  OR (e.resolvedAt IS NOT NULL AND e.resolvedAt >= :from AND e.resolvedAt <= :to)) "
         + "ORDER BY e.createdAt DESC")
    List<AlertEvent> findOverlappingForHistory(@Param("domainKey") String domainKey,
                                               @Param("types") Collection<String> types,
                                               @Param("from") String from, @Param("to") String to);

    // ── Alarm fırtınası (storm) sorguları ──────────────────────────────────────
    /** Pencere-içi açık DOWN incident'ler (account-wide scope) — terfi eşiği sayımı + üye geri-bağlama.
     *  idx_ae_storm_scan(resolved, alert_type, created_at) tarafından beslenir; created_at sabit-genişlik ISO → sözlüksel aralık. */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.alertType IN :types AND e.createdAt >= :since")
    List<AlertEvent> findOpenDownSince(@Param("types") Collection<String> types, @Param("since") String since);

    /** Pencere-içi açık DOWN incident'ler — per-group scope (yalnız verilen grup). */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false AND e.alertType IN :types AND e.createdAt >= :since AND e.groupName = :group")
    List<AlertEvent> findOpenDownSinceInGroup(@Param("types") Collection<String> types, @Param("since") String since, @Param("group") String group);

    /** Storm üyeleri (çözülmüş+açık) — toplu recovery e-postasında "hangi monitörler" listesi için. */
    List<AlertEvent> findByStormId(Long stormId);

    /** Hâlâ down (açık) storm üyeleri — çözülme/histerezis değerlendirmesi + toggle-off geri-bağlama için. */
    List<AlertEvent> findByStormIdAndResolvedFalse(Long stormId);

    /** Storm bağı (KOŞULLU, atomik) — yalnız HÂLÂ AÇIK + bağsız satırı bağlar. linkPeers'ın full-entity save'i
     *  eşzamanlı bir recovery ile çözülmüş bir incident'i diriltebiliyordu; bu koşullu UPDATE onu önler (M6). */
    /** D9: otomatik kapanışın manuel resolve'a karşı serileştirilmesi — yalnız hâlâ AÇIKSA
     *  kapatır; 0 dönerse yarışı başkası kazanmış demektir (ikinci "çözüldü" maili gitmez,
     *  resolvedBy ezilmez). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.resolved = true, e.resolvedAt = :at, e.resolvedBy = :by "
            + "WHERE e.id = :id AND e.resolved = false")
    int markResolvedIfOpen(@Param("id") Long id, @Param("at") String at, @Param("by") String by);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = :stormId WHERE e.id = :id AND e.resolved = false AND e.stormId IS NULL")
    int linkToStormIfOpen(@Param("id") Long id, @Param("stormId") Long stormId);

    /**
     * Storm bağını kaldır — yalnız hâlâ AÇIK satırda (çözülmüş üyeyi full-save ile diriltmeden).
     *
     * <p>{@code lastReAlertAt} de sıfırlanır: üye storm'a eklenirken bireysel bildirim GİTMEDEN
     * damgalanmıştı (SUPPRESSED); bağ kopunca izleme yolu "bugün zaten gönderildi" deyip bir
     * re-alert aralığı (24 sa) susuyordu — "sonraki sweep bireysel alarm" sözü tutulmuyordu.
     * null damga = "ilk bildirim yarıda kaldı" dalı → sonraki sweep INITIAL'ı hemen gönderir.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = null, e.lastReAlertAt = null WHERE e.id = :id AND e.resolved = false")
    int unlinkFromStorm(@Param("id") Long id);

    @Query("""
            SELECT a FROM AlertEvent a
             WHERE a.resolved = false
               AND EXISTS (
                   SELECT 1 FROM CertificateInventory i
                    WHERE i.domain = a.domain
                      AND i.deletedAt IS NOT NULL
               )
            """)
    List<AlertEvent> findOpenAlertsOnSoftDeletedDomains();

    @Query("SELECT DISTINCT e.domain FROM AlertEvent e WHERE e.resolved = false AND NOT EXISTS (SELECT n FROM NotificationLog n WHERE n.alertEventId = e.id AND n.emailStatus = 'SENT')")
    List<String> findDomainsWithUnnotifiedOpenAlerts();

    @Query("""
            SELECT e FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:alertType IS NULL OR e.alertType = :alertType)
              AND (:typeScoped = FALSE OR e.alertType IN :types)
              AND (:q IS NULL OR LOWER(e.domain) LIKE :q ESCAPE '!')
              AND (:level IS NULL OR e.alertLevel = :level)
              AND (:acknowledged IS NULL OR e.acknowledged = :acknowledged)
              AND (:teamId IS NULL OR e.teamId = :teamId OR EXISTS (
                      SELECT 1 FROM CertificateInventory ti
                       WHERE ti.domain = e.domain
                         AND (ti.teamId = :teamId OR ti.ugTeamId = :teamId)))
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    Page<AlertEvent> findFiltered(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("domain") String domain,
            @Param("alertType") String alertType,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("level") String level,
            @Param("acknowledged") Boolean acknowledged,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            Pageable pageable);

    /** Tip filtre pill'lerinin canlı sayıları — findFiltered ile aynı filtreler,
     *  alertType HARİÇ (sayılar her zaman tüm tipleri gösterir). */
    @Query("""
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:typeScoped = FALSE OR e.alertType IN :types)
              AND (:q IS NULL OR LOWER(e.domain) LIKE :q ESCAPE '!')
              AND (:level IS NULL OR e.alertLevel = :level)
              AND (:acknowledged IS NULL OR e.acknowledged = :acknowledged)
              AND (:teamId IS NULL OR e.teamId = :teamId OR EXISTS (
                      SELECT 1 FROM CertificateInventory ti
                       WHERE ti.domain = e.domain
                         AND (ti.teamId = :teamId OR ti.ugTeamId = :teamId)))
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType
            """)
    List<Object[]> countFilteredByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("domain") String domain,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("level") String level,
            @Param("acknowledged") Boolean acknowledged,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);

    // ── Executive haftalık özet (WeeklyReportKpiService) — alertLevel-bazlı sayımlar ──
    //    (findFiltered/countFilteredByType alertType'a göre filtreler, alertLevel'a göre DEĞİL.)
    //    Her sorgu, findFiltered'daki takım-kapsam EXISTS yüklemini taşır (teamId + domain→envanter SY/UG).

    /** Verilen seviyede {@code asOf} anı itibarıyla AÇIK alarm sayısı (createdAt ≤ asOf, o an çözülmemiş) — takım kapsamlı.
     *  As-of semantiği geçmiş hafta için de yeniden hesaplanabilir → skor hafta-üstü delta'sı gerçek olur. */
    @Query("""
            SELECT COUNT(e) FROM AlertEvent e
            WHERE e.alertLevel = :level
              AND e.createdAt <= :asOf
              AND (e.resolvedAt IS NULL OR e.resolvedAt > :asOf)
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    long countOpenByLevelAsOf(@Param("level") String level, @Param("asOf") String asOf,
                              @Param("scoped") boolean scoped, @Param("scope") List<Long> scope);

    /** Verilen seviyede, pencerede AÇILAN alarm sayısı (createdAt ∈ [since, until]) — takım kapsamlı. */
    @Query("""
            SELECT COUNT(e) FROM AlertEvent e
            WHERE e.alertLevel = :level
              AND e.createdAt >= :since AND e.createdAt <= :until
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    long countByLevelOpenedBetween(@Param("level") String level, @Param("since") String since,
                                   @Param("until") String until, @Param("scoped") boolean scoped,
                                   @Param("scope") List<Long> scope);

    /** Verilen seviyede, pencerede ÇÖZÜLEN alarm sayısı (resolved, resolvedAt ∈ [since, until]) — takım kapsamlı. */
    @Query("""
            SELECT COUNT(e) FROM AlertEvent e
            WHERE e.alertLevel = :level AND e.resolved = true
              AND e.resolvedAt >= :since AND e.resolvedAt <= :until
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    long countByLevelResolvedBetween(@Param("level") String level, @Param("since") String since,
                                     @Param("until") String until, @Param("scoped") boolean scoped,
                                     @Param("scope") List<Long> scope);

    /** Domain rename: alarm geçmişini yeni domain'e taşı.
     *  Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE AlertEvent e SET e.domain = :newDomain WHERE e.domain = :oldDomain")
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);

    /** Denormalize grup adı kopyasını bir TAKIM için yeniden adlandır (İzleme Grupları rename ile senkron). @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE AlertEvent e SET e.groupName = :newName WHERE LOWER(e.groupName) = LOWER(:oldName) "
         + "AND ((:teamId IS NULL AND e.teamId IS NULL) OR e.teamId = :teamId) AND e.alertType IN :alertTypes")
    int renameGroupForTeamAndTypes(@Param("teamId") Long teamId, @Param("oldName") String oldName,
                                   @Param("newName") String newName, @Param("alertTypes") java.util.Collection<String> alertTypes);

    // ── Incidents Overview ekranı — findFiltered'dan AYRI: q, domain üzerinde LIKE (monitör adı/host araması) ──
    @Query("""
            SELECT e FROM AlertEvent e
             WHERE (:resolved IS NULL OR e.resolved = :resolved)
               AND (:since IS NULL OR e.createdAt >= :since)
               AND (:until IS NULL OR e.createdAt <= :until)
               AND (:alertType IS NULL OR e.alertType = :alertType)
               AND (:q IS NULL OR LOWER(e.domain) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
               AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                       SELECT 1 FROM CertificateInventory i
                        WHERE i.domain = e.domain
                          AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    Page<AlertEvent> findIncidents(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("alertType") String alertType,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope,
            Pageable pageable);

    /** Root-cause (alertType) pill sayaçları — alertType HARİÇ aynı incident filtreleri. */
    @Query("""
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
             WHERE (:resolved IS NULL OR e.resolved = :resolved)
               AND (:since IS NULL OR e.createdAt >= :since)
               AND (:until IS NULL OR e.createdAt <= :until)
               AND (:q IS NULL OR LOWER(e.domain) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
               AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                       SELECT 1 FROM CertificateInventory i
                        WHERE i.domain = e.domain
                          AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType
            """)
    List<Object[]> countIncidentsByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);


    // ── Tip kapsamı EKLENMEDEN ÖNCEKİ imzalar ────────────────────────────────────────────
    // Alarm sekmesinin tip süzgeci (typeScoped/types) YALNIZ AdminController'ın alarm ucundan
    // besleniyor. Diğer tüm çağıranlar ve testler tipe göre süzmüyor; imzayı orada da
    // değiştirmek 22 çağrı yerini gereksiz yere riske sokardı. Dosyadaki mevcut desen
    // (bkz. aşağıdaki "Eski (filtresiz) imza") aynen izleniyor: default aşırı yükleme
    // typeScoped=false geçer ve sorgu eskisiyle BİREBİR aynı davranır.

    default Page<AlertEvent> findFiltered(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain, String alertType,
            String q, String level, Boolean acknowledged, Long teamId,
            boolean scoped, List<Long> scope, Pageable pageable) {
        return findFiltered(resolved, since, until, resolvedSince, resolvedUntil, domain, alertType,
                false, NO_TYPE_SCOPE, q, level, acknowledged, teamId, scoped, scope, pageable);
    }

    default List<Object[]> countFilteredByType(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain,
            String q, String level, Boolean acknowledged, Long teamId,
            boolean scoped, List<Long> scope) {
        return countFilteredByType(resolved, since, until, resolvedSince, resolvedUntil, domain,
                false, NO_TYPE_SCOPE, q, level, acknowledged, teamId, scoped, scope);
    }

    default List<Object[]> countFacets(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain, String alertType,
            String q, Long teamId, boolean scoped, List<Long> scope) {
        return countFacets(resolved, since, until, resolvedSince, resolvedUntil, domain, alertType,
                false, NO_TYPE_SCOPE, q, teamId, scoped, scope);
    }

    default long countStale(Boolean resolved, String staleBefore, String domain, String alertType,
            String q, Long teamId, boolean scoped, List<Long> scope) {
        return countStale(resolved, staleBefore, domain, alertType,
                false, NO_TYPE_SCOPE, q, teamId, scoped, scope);
    }

    /** Tip kapsamı KAPALIYKEN IN listesine geçilen kukla değer: JPQL'de "IN ()" geçersizdir,
     *  bu yüzden takım kapsamındaki {@code scopeList} deseninin aynısı uygulanır. */
    java.util.List<String> NO_TYPE_SCOPE = java.util.List.of("-");

    /**
     * Eski (filtresiz) imza — haftalık KPI ve izleme istatistikleri servisleri bunu kullanıyor.
     *
     * <p>Alarm Geçmişi ekranı için eklenen q / level / acknowledged / teamId parametreleri bu
     * çağrı yerlerini İLGİLENDİRMİYOR; imzayı orada da değiştirmek altı çağrı yerini gereksiz
     * yere riske sokardı. {@code default} aşırı yükleme ile hepsi olduğu gibi kalıyor.
     */
    default Page<AlertEvent> findFiltered(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain, String alertType,
            boolean scoped, List<Long> scope, Pageable pageable) {
        // typeScoped=false → tip kapsamı UYGULANMAZ (bu çağıranlar tek tip ya da tümünü ister).
        return findFiltered(resolved, since, until, resolvedSince, resolvedUntil, domain, alertType,
                false, NO_TYPE_SCOPE, null, null, null, null, scoped, scope, pageable);
    }

    /** {@link #findFiltered} ile aynı gerekçe — eski imza korunur. */
    default List<Object[]> countFilteredByType(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain,
            boolean scoped, List<Long> scope) {
        return countFilteredByType(resolved, since, until, resolvedSince, resolvedUntil, domain,
                false, NO_TYPE_SCOPE, null, null, null, null, scoped, scope);
    }


    /**
     * İstatistik şeridinin sayaçları: (seviye, sahiplenildi) kırılımı — TEK sorguda.
     *
     * <p>Kartlardaki sayılar SAYFA İÇİNDEN hesaplanamaz: 81 alarmın 20'si ekranda dururken
     * "Kritik: 12" yazmak yanıltıcı olur. Bu yüzden sunucuda, listeyle AYNI filtrelerle sayılır.
     *
     * <p>Kendi boyutları HARİÇ: seviye ve sahiplenilme filtreleri buraya UYGULANMAZ — aksi halde
     * "Kritik" kartına basınca diğer kartlar sıfırlanır ve kullanıcı geri dönemez.
     */
    @Query("""
            SELECT e.alertLevel, e.acknowledged, COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:alertType IS NULL OR e.alertType = :alertType)
              AND (:typeScoped = FALSE OR e.alertType IN :types)
              AND (:q IS NULL OR LOWER(e.domain) LIKE :q ESCAPE '!')
              AND (:teamId IS NULL OR e.teamId = :teamId OR EXISTS (
                      SELECT 1 FROM CertificateInventory ti
                       WHERE ti.domain = e.domain
                         AND (ti.teamId = :teamId OR ti.ugTeamId = :teamId)))
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertLevel, e.acknowledged
            """)
    List<Object[]> countFacets(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("domain") String domain,
            @Param("alertType") String alertType,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);


    /**
     * "Uzun süredir açık" sayacı — {@code staleBefore}'dan ESKİ alarmlar.
     *
     * <p>Neden AYRI sorgu: bu boyutu {@link #countFacets}'in gruplamasına {@code CASE WHEN} ile
     * katmayı denedim ve Hibernate'in ürettiği SQL PostgreSQL'de patladı
     * ({@code column "created_at" must appear in the GROUP BY clause}) — SELECT'teki CASE ile
     * GROUP BY'daki CASE parametreli oldukları için özdeş sayılmıyor. Tek turu kurtarmak uğruna
     * lehçeye bağımlı, kırılgan bir sorgu yazmaktansa ikinci bir COUNT daha ucuz.
     *
     * <p>Diğer filtreler listeyle AYNI uygulanır; yalnız yaş eşiği eklenir.
     */
    @Query("""
            SELECT COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND e.createdAt < :staleBefore
              AND (:domain IS NULL OR e.domain = :domain)
              AND (:alertType IS NULL OR e.alertType = :alertType)
              AND (:typeScoped = FALSE OR e.alertType IN :types)
              AND (:q IS NULL OR LOWER(e.domain) LIKE :q ESCAPE '!')
              AND (:teamId IS NULL OR e.teamId = :teamId OR EXISTS (
                      SELECT 1 FROM CertificateInventory ti
                       WHERE ti.domain = e.domain
                         AND (ti.teamId = :teamId OR ti.ugTeamId = :teamId)))
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            """)
    long countStale(
            @Param("resolved") Boolean resolved,
            @Param("staleBefore") String staleBefore,
            @Param("domain") String domain,
            @Param("alertType") String alertType,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);


    /**
     * (domain, tip) → son {@code since} tarihinden bu yana açılmış alarm sayısı — TEK sorguda.
     *
     * <p>Kart başına ayrı sorgu N+1 olurdu (50 kayıtlık sayfada 50 sorgu); enrichAlerts'teki
     * mevcut toplu-sorgu deseni izlendi. Kapsam kontrolü GEREKMEZ: yalnız zaten görüntülenen
     * alarmların (domain, tip) çiftleri için sayım yapılıyor.
     */
    @Query("""
            SELECT e.domain, e.alertType, COUNT(e) FROM AlertEvent e
            WHERE e.domain IN :domains AND e.createdAt >= :since
            GROUP BY e.domain, e.alertType
            """)
    List<Object[]> countRecentByDomainAndType(@Param("domains") Collection<String> domains,
                                              @Param("since") String since);

    /** İmza (alan adı + tip) geçmiş özeti: toplam, ilk, son oluşum, son kapanış — TÜM zamanlar (2026-09-16). */
    @Query("SELECT e.domain, e.alertType, COUNT(e), MIN(e.createdAt), MAX(e.createdAt), MAX(e.resolvedAt) "
         + "FROM AlertEvent e WHERE e.domain IN :domains GROUP BY e.domain, e.alertType")
    List<Object[]> summarizeHistoryByDomainAndType(@Param("domains") Collection<String> domains);

    /** İmza zaman çizelgesi (en yeniden eskiye, SAYFALI): "bu alarmdan ÖNCEKİ oluşum" için. */
    @Query("SELECT e.domain, e.alertType, e.createdAt FROM AlertEvent e "
         + "WHERE e.domain IN :domains ORDER BY e.createdAt DESC")
    List<Object[]> findSignatureTimeline(@Param("domains") Collection<String> domains, Pageable pageable);

}
