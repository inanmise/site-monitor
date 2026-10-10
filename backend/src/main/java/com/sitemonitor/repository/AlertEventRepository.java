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

    /**
     * Gelen kutusu "son 24 saatte çözülenler" (2026-10-09, performans): eskiden 30 günlük TÜM alarmlar yüklenip Java'da
     * süzülüyordu (dakikada bir, her kullanıcı). Aynı anlam: çözülmüş + çözülme anı ≥ resolvedSince + oluşma ≥ createdSince
     * (idx_ae_resolved_at). ISO sabit genişlik → sözlüksel karşılaştırma.
     */
    @org.springframework.data.jpa.repository.Query("SELECT e FROM AlertEvent e WHERE e.resolved = true AND e.resolvedAt >= :resolvedSince"
            + " AND e.createdAt >= :createdSince ORDER BY e.createdAt DESC")
    List<AlertEvent> findResolvedSinceCreatedSince(@org.springframework.data.repository.query.Param("resolvedSince") String resolvedSince,
                                                   @org.springframework.data.repository.query.Param("createdSince") String createdSince);

    /** "Sizin için — bugün" son 24 saat şeridi (2026-09-23): pencere içinde ÇÖZÜLEN alarmlar. */
    List<AlertEvent> findByResolvedAtGreaterThanEqual(String since);

    /**
     * Açık alarmlar, en ACİL önce. Önem METİN olarak saklanıyor: {@code ORDER BY alertLevel DESC} alfabetik
     * sıralayıp WARNING &gt; HIGH &gt; CRITICAL verdiği için kritikler listenin SONUNA düşüyordu ve ilk 5'i gösteren
     * "Sizin için — bugün / Açık alarm" kartı onları hiç göstermiyordu (QA 2026-09-24, ISSUE-001). Sıra açıkça
     * yazılır; bilinmeyen seviye en sona.
     */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false ORDER BY "
         + "CASE UPPER(e.alertLevel) WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'WARNING' THEN 2 "
         + "WHEN 'LOW' THEN 3 WHEN 'INFO' THEN 4 ELSE 5 END, e.createdAt DESC")
    List<AlertEvent> findAllOpenOrderBySeverity();

    /** Genel Bakış kartı "şu an" şeridi (2026-09-19): alan başına EN SON alarm olayı (açık ya da çözülmüş) — tek sorgu. */
    @Query("SELECT a FROM AlertEvent a WHERE a.id IN (SELECT MAX(b.id) FROM AlertEvent b WHERE b.domain IS NOT NULL GROUP BY b.domain)")
    List<AlertEvent> findLatestPerDomain();

    /**
     * Genel Bakış kartı "şu an" şeridi — YALNIZ aktif envanter alanları için alan başına en son alarm (2026-10-09, performans).
     * {@link #findLatestPerDomain} kapanmış alarmlar dahil TÜM geçmişi alan adına göre grupluyordu (alarm satırları hiç
     * silinmez → tablo büyüdükçe yavaşlar ve Genel Bakış ek verisini geciktirir). Küçük envanter üzerinden LATERAL ile alan
     * başına tek indeks araması ({@code idx_ae_domain}); kart yalnız aktif envanter alanlarını gösterdiği için sonuç aynı.
     * PostgreSQL'e özgü sözdizimi düşerse çağıran eski sorguya döner.
     */
    @Query(value = "SELECT a.* FROM certificate_inventory i CROSS JOIN LATERAL "
         + "(SELECT * FROM alert_events e WHERE e.domain = i.domain ORDER BY e.id DESC LIMIT 1) a "
         + "WHERE i.active = true", nativeQuery = true)
    List<AlertEvent> findLatestForActiveInventoryDomains();

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
    /** Pencere-içi açık DOWN incident'ler (kuruluş geneli; StormService TAKIMA süzer, 2026-09-29) — terfi eşiği sayımı + üye geri-bağlama.
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
    /**
     * Sessiz saat özeti gönderildi (2026-10-01): hâlâ AÇIK alarmlar bu andan itibaren "bildirilmiş" sayılır — günlük
     * yeniden uyarı kadansı özet anından başlar. Tek toplu UPDATE (alarm başına sorgu yok).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.lastReAlertAt = :at WHERE e.id IN :ids AND e.resolved = false")
    int stampNotifiedByQuietDigest(@Param("ids") Collection<Long> ids, @Param("at") String at);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.resolved = true, e.resolvedAt = :at, e.resolvedBy = :by "
            + "WHERE e.id = :id AND e.resolved = false")
    int markResolvedIfOpen(@Param("id") Long id, @Param("at") String at, @Param("by") String by);

    /**
     * Bildirim SONRASI damga (2026-10-09) — YALNIZ gönderimin değiştirdiği alanlar, yalnız hâlâ AÇIK satırda.
     * Eskiden gönderim (SMTP + aralık beklemesi) bitince yüklü entity {@code save} ediliyordu: {@code AlertEvent}'te
     * {@code @Version}/{@code @DynamicUpdate} yok, yani TÜM kolonlar yeniden yazılıyor ve gönderim sürerken kullanıcının
     * yaptığı çözüm/onay geri alınıyordu (çözülen alarm yeniden açık). Değerler çağıranın taze okumasından gelir.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.lastReAlertAt = :at, e.realertCount = :realertCount, e.daysRemaining = :days, "
            + "e.notAfter = :notAfter, e.notifiedContacts = :notified WHERE e.id = :id AND e.resolved = false")
    int stampNotificationSentIfOpen(@Param("id") Long id, @Param("at") String at,
                                    @Param("realertCount") Integer realertCount, @Param("days") Integer days,
                                    @Param("notAfter") String notAfter, @Param("notified") String notified);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = :stormId WHERE e.id = :id AND e.resolved = false AND e.stormId IS NULL")
    int linkToStormIfOpen(@Param("id") Long id, @Param("stormId") Long stormId);

    /**
     * Sistem bakımı telafisi (2026-10-02, kullanıcı kararı): açılış bildirimi bakımda SUSTURULMUŞ, hâlâ açık ve onaylanmamış
     * alarmların "ilk bildirim gitti" damgasını sıfırlar → bir sonraki tur INITIAL'ı normal kurallarla BİR kez gönderir
     * ("yarıda kalmış ilk bildirim" dalı — fırtına "unlinked → bireysel INITIAL" deseni). {@code until} = bakım bitişi:
     * bakımdan SONRA gerçekten gönderilmiş bir bildirimin (ör. bitişten sonra gelen eskalasyon) damgası EZİLMEZ.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.lastReAlertAt = NULL WHERE e.id IN :ids AND e.resolved = false "
            + "AND (e.acknowledged IS NULL OR e.acknowledged = false) "
            + "AND (e.lastReAlertAt IS NULL OR e.lastReAlertAt <= :until)")
    int clearInitialStampForCatchUp(@Param("ids") Collection<Long> ids, @Param("until") String until);

    /**
     * Storm bağını kaldır — yalnız hâlâ AÇIK satırda (çözülmüş üyeyi full-save ile diriltmeden).
     *
     * <p>{@code lastReAlertAt} de sıfırlanır: üye storm'a eklenirken bireysel bildirim GİTMEDEN
     * damgalanmıştı (SUPPRESSED); bağ kopunca izleme yolu "bugün zaten gönderildi" deyip bir
     * re-alert aralığı (24 sa) susuyordu — "sonraki sweep bireysel alarm" sözü tutulmuyordu.
     * null damga = "ilk bildirim yarıda kaldı" dalı → sonraki sweep INITIAL'ı hemen gönderir.
     *
     * <p>YALNIZ verilen fırtınanın üyesinde (2026-10-09): koşulda fırtına kimliği yoktu — kapanış, üye listesini okuduktan
     * sonra BAŞKA bir fırtınaya geçmiş satırı o fırtınadan koparıp damgasını sıfırlayabiliyordu (yarışta çift İLK bildirim,
     * diğer fırtınanın üye sayısı yanlış). Çağıran kapattığı fırtınayı verir; bağ değişmişse 0 döner, satıra dokunulmaz.
     * Kapanışın BİLİNEN üyeleri için; geç katılan adımı {@link #unlinkFromStormIfLinked}'i kullanır.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = null, e.lastReAlertAt = null "
            + "WHERE e.id = :id AND e.resolved = false AND e.stormId = :stormId")
    int unlinkFromStorm(@Param("id") Long id, @Param("stormId") Long stormId);

    /**
     * Fırtına kapanış yarışı (2026-10-09): {@link #unlinkFromStorm} ile aynı ayırma (bağ + ilk bildirim damgası sıfırlanır →
     * sonraki tur bireysel İLK), ama YALNIZ satır hâlâ verilen fırtınaya bağlıysa — başka fırtınaya taşınmış ya da zaten
     * ayrılmış üyeye dokunmaz. Kapanışın geç katılan adımı ve çağıranın bağlanma sonrası denetimi kullanır.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = null, e.lastReAlertAt = null "
            + "WHERE e.id = :id AND e.resolved = false AND e.stormId = :stormId")
    int unlinkFromStormIfLinked(@Param("id") Long id, @Param("stormId") Long stormId);

    /**
     * Eski (kuruluş geneli) fırtına üyesini takımının fırtınasına TAŞI (2026-09-29, O-3) — yalnız hâlâ AÇIK ve hâlâ eski
     * fırtınaya bağlı satırda (koşullu, atomik). {@code lastReAlertAt} korunur: üye fırtına postasıyla zaten bildirildi.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = :toStormId WHERE e.id = :id AND e.resolved = false AND e.stormId = :fromStormId")
    int moveToStormIfOpen(@Param("id") Long id, @Param("fromStormId") Long fromStormId, @Param("toStormId") Long toStormId);

    /**
     * Eski fırtına üyesini fırtınadan çöz ama "BİLDİRİLDİ" say (2026-09-29, O-3): {@link #unlinkFromStorm}'un tersine
     * {@code lastReAlertAt} sıfırlanmaz, fırtınanın son toplu bildirim anına damgalanır → sonraki tur "yarım kalmış ilk
     * bildirim" diye TEK TEK INITIAL göndermez; bireysel günlük yeniden uyarı kadansı o andan sürer. Yalnız hâlâ ESKİ
     * fırtınaya bağlı satırda (D-b7): üst üste binen ikinci emeklilik koşusu, birincinin takım fırtınasına taşıdığı
     * üyeyi fırtınadan KOPARMAZ.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertEvent e SET e.stormId = null, e.lastReAlertAt = :notifiedAt "
            + "WHERE e.id = :id AND e.resolved = false AND e.stormId = :fromStormId")
    int releaseFromStormAsNotified(@Param("id") Long id, @Param("fromStormId") Long fromStormId,
                                   @Param("notifiedAt") String notifiedAt);

    // 2026-10-07: findOpenAlertsOnSoftDeletedDomains KALDIRILDI — envanter silmesi kalıcı (alarmlar silme anında kapanır),
    // eski çöp kutusu tek seferlik temizlikte (DeletedRecordsPurge) alarmlarıyla birlikte kapandı; açılış telafisi ölüydü.

    @Query("SELECT DISTINCT e.domain FROM AlertEvent e WHERE e.resolved = false AND NOT EXISTS (SELECT n FROM NotificationLog n WHERE n.alertEventId = e.id AND n.emailStatus = 'SENT')")
    List<String> findDomainsWithUnnotifiedOpenAlerts();

    /**
     * O-A3-5 (2026-09-29): ilk bildirimi HİÇ gitmemiş ({@code lastReAlertAt} null) açık olaylar, verilen türlerde — bakım
     * penceresinde açılan değişiklik alarmlarının (DNS_CHANGED / DOMAINMON_CHANGED) pencere bitince bildirilmesi için.
     * Yalnız okuma, dar indeksli (resolved + alert_type).
     *
     * <p>ONAYLANMIŞ olay HARİÇ (2026-10-09, sonsuz döngü düzeltmesi): {@code processConfirmedOutage} onaylı olayda hiçbir
     * şey göndermez ve damga da yazmaz ({@code if (acked) return;}). Bu türler elle kapandığından onaylı, hiç bildirilmemiş
     * olay her pod'da DAKİKADA BİR kilit yazıp boş değerlendirmeye giriyordu — kapatılana dek. Onayı düşen olay (seviye
     * terfisi {@code acknowledged=false} yazar) yeniden listeye girer: ertelenmiş ilk bildirim davranışı aynen korunur.
     * {@code acknowledged} NULLABLE — null onaysız sayılır (O6, {@code Boolean.TRUE.equals} ile aynı).
     */
    @Query("SELECT e FROM AlertEvent e WHERE e.alertType IN :alertTypes AND e.resolved = false "
            + "AND e.lastReAlertAt IS NULL AND (e.acknowledged IS NULL OR e.acknowledged = false)")
    List<AlertEvent> findUnacknowledgedOpenAwaitingInitial(@Param("alertTypes") Collection<String> alertTypes);

    /**
     * D-7 / D-c11 (2026-09-29): pencerede GERÇEKTEN kurtarılarak kapanan alarm sayısı, tür başına — sessiz kapanışlar
     * ({@code resolvedSilently = true}: izleme silindi / duraklatıldı / envanter pasif / tür bildirimi kapalı) KURTARMA
     * değildir, haftalık "çözülen" sayısına ve MTTR'a girmez. Takım kapsamı {@code findFiltered} ile aynı yüklem.
     */
    @Query("""
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
            WHERE e.resolved = true
              AND e.resolvedAt >= :resolvedSince AND e.resolvedAt <= :resolvedUntil
              AND (e.resolvedSilently IS NULL OR e.resolvedSilently = false)
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType
            """)
    List<Object[]> countRecoveredByType(@Param("resolvedSince") String resolvedSince,
                                        @Param("resolvedUntil") String resolvedUntil,
                                        @Param("scoped") boolean scoped, @Param("scope") List<Long> scope);

    // ── Alarm Geçmişi listesinin dört sorgusu — gövdeler SABİTTE (2026-10-04) ────────────────────────────────────
    // Metinler önceki @Query dizeleriyle BİREBİR aynı; tek fark: "7/24'e gidenler" süzgecinin (…Noc ikizleri) aynı
    // gövdeyi paylaşabilmesi. İkizler YALNIZ {@link #NOC_SENT_FILTER} ekler — ayrışamazlar
    // (AlertEventNocFilterQueryTest).
    String ALERT_LIST_FIND = """
            SELECT e FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:activeFrom IS NULL OR e.resolved = false OR e.resolvedAt >= :activeFrom)
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
            """;

    String ALERT_LIST_TYPE_COUNT = """
            SELECT e.alertType, COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:activeFrom IS NULL OR e.resolved = false OR e.resolvedAt >= :activeFrom)
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
            """;

    String ALERT_LIST_FACETS = """
            SELECT e.alertLevel, e.acknowledged, COUNT(e) FROM AlertEvent e
            WHERE (:resolved IS NULL OR e.resolved = :resolved)
              AND (:since IS NULL OR e.createdAt >= :since)
              AND (:until IS NULL OR e.createdAt <= :until)
              AND (:resolvedSince IS NULL OR e.resolvedAt >= :resolvedSince)
              AND (:resolvedUntil IS NULL OR e.resolvedAt <= :resolvedUntil)
              AND (:activeFrom IS NULL OR e.resolved = false OR e.resolvedAt >= :activeFrom)
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
            """;

    String ALERT_LIST_STALE = """
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
            """;

    /**
     * "7/24'e gidenler" (2026-10-04): alarmın 7/24 AÇILIŞ teslimi gitmiş sayılan durumda — {@code noc_deliveries} satırı
     * ({@code phase = OPEN}, durum SENT / SENT_VIA_STORM / QUEUED_RETRY…; {@code NocAlertFacts.sent} ile aynı kural).
     * İndeks: {@code idx_noc_delivery_alert}.
     */
    String NOC_SENT_FILTER = """
              AND EXISTS (SELECT 1 FROM NocDelivery nd
                           WHERE nd.alertEventId = e.id AND nd.phase = 'OPEN'
                             AND (nd.status LIKE 'SENT%' OR nd.status LIKE 'QUEUED_RETRY%'))
            """;

    /** {@link #findFiltered} + yalnız 7/24'e gidenler. Parametreler aynı. */
    @Query(ALERT_LIST_FIND + NOC_SENT_FILTER)
    Page<AlertEvent> findFilteredNoc(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
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

    /** {@link #countFilteredByType} + yalnız 7/24'e gidenler. */
    @Query(ALERT_LIST_TYPE_COUNT + NOC_SENT_FILTER + "GROUP BY e.alertType")
    List<Object[]> countFilteredByTypeNoc(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
            @Param("domain") String domain,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("level") String level,
            @Param("acknowledged") Boolean acknowledged,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);

    /** {@link #countFacets} + yalnız 7/24'e gidenler. */
    @Query(ALERT_LIST_FACETS + NOC_SENT_FILTER + "GROUP BY e.alertLevel, e.acknowledged")
    List<Object[]> countFacetsNoc(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
            @Param("domain") String domain,
            @Param("alertType") String alertType,
            @Param("typeScoped") boolean typeScoped,
            @Param("types") java.util.Collection<String> types,
            @Param("q") String q,
            @Param("teamId") Long teamId,
            @Param("scoped") boolean scoped,
            @Param("scope") List<Long> scope);

    /** {@link #countStale} + yalnız 7/24'e gidenler. */
    @Query(ALERT_LIST_STALE + NOC_SENT_FILTER)
    long countStaleNoc(
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
     * 7/24 konsolu (2026-10-04): AÇIK alarmlar + pencerede ({@code since} sonrası) açılanlar, en yeni önce; tavan
     * {@code pageable} ile (konsol bir "canlı" görünümdür — tüm geçmiş değil). İndeks: {@code idx_ae_resolved} + created_at.
     */
    @Query("SELECT e FROM AlertEvent e WHERE e.resolved = false OR e.createdAt >= :since ORDER BY e.createdAt DESC, e.id DESC")
    List<AlertEvent> findOpenOrCreatedSince(@Param("since") String since, Pageable pageable);

    /**
     * Alarm Geçmişi listesi. {@code activeFrom} (2026-09-28, "aralıkta aktif olanlar" kipi): verilirse alarm, o andan
     * SONRA hâlâ açıksa ya da o anda/sonrasında çözüldüyse girer — {@code until} ile birlikte "pencereyle KESİŞEN"
     * (açılış ≤ bitiş VE (açık YA DA çözüm ≥ başlangıç)) koşulunu kurar; önceki haftadan devredenler de listelenir.
     * Çağıran bu kipte {@code since}'ı (açılış alt sınırı) null geçer. Yüklem haftalık kesinti raporunun üç sorgulu
     * birleşimiyle ({@code WeeklyOutageReportService.loadWeekAlarms}) aynı kümeyi verir (açık = {@code resolved=false}).
     * İndeks: {@code idx_ae_resolved} + {@code idx_ae_resolved_at} (BitmapOr). Karşılaştırma yalnız — fonksiyon yok,
     * null parametre CAST gerektirmez (RepositoryNullableParamCastTest).
     */
    @Query(ALERT_LIST_FIND)
    Page<AlertEvent> findFiltered(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
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

    /** Tip filtre pill'lerinin canlı sayıları — findFiltered ile aynı filtreler (aralıkta-aktif kipi dahil),
     *  alertType HARİÇ (sayılar her zaman tüm tipleri gösterir). */
    @Query(ALERT_LIST_TYPE_COUNT + "GROUP BY e.alertType")
    List<Object[]> countFilteredByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
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

    /**
     * {@code since}'ten beri AÇILAN alarm sayısı — tüm türler (sertifika + dokuz izleme türü), kapsamsız (giriş sayfası
     * kullanım istatistikleri, 2026-10-04). Tek COUNT ({@code idx_ae_created_at}); satır taşınmaz.
     */
    @Query("SELECT COUNT(e) FROM AlertEvent e WHERE e.createdAt >= :since")
    long countCreatedSince(@Param("since") String since);

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
    //
    // Takım kapsamı (2026-09-28, org geneli salt okunur Olaylar): `scoped=false` → tümü; `scoped=true, outside=false`
    // → kapsamdaki takım(lar)ın olayları ("Takımımın olayları": damgalı teamId YA DA domain→envanter SY/UG takımı —
    // IncidentsController.incidentTeamInScope ile aynı kural); `scoped=true, outside=true` → TAMAMLAYICI küme ("Diğer
    // ekiplerin olayları"). İki küme ayrık ve birleşimleri tümüdür. Tamamlayıcıda NULL teamId AÇIKÇA ele alınır:
    // `NOT (e.teamId IN :scope)` NULL için BİLİNMEYEN döner ve takımsız olayı iki kümeden de sessizce düşürürdü.
    // Üç sorgu aynı süzgeç gövdesini paylaşır (sayfa, sayfa-dışı toplam, kök neden sayaçları) — ayrışamasınlar.
    String INCIDENTS_FILTER = """
             (:resolved IS NULL OR e.resolved = :resolved)
               AND (:since IS NULL OR e.createdAt >= :since)
               AND (:until IS NULL OR e.createdAt <= :until)
               AND (:q IS NULL OR LOWER(e.domain) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
               AND (:scoped = FALSE
                    OR (:outside = FALSE AND (e.teamId IN :scope OR EXISTS (
                           SELECT 1 FROM CertificateInventory i
                            WHERE i.domain = e.domain
                              AND (i.teamId IN :scope OR i.ugTeamId IN :scope))))
                    OR (:outside = TRUE
                        AND (e.teamId IS NULL OR e.teamId NOT IN :scope)
                        AND NOT EXISTS (
                           SELECT 1 FROM CertificateInventory i2
                            WHERE i2.domain = e.domain
                              AND (i2.teamId IN :scope OR i2.ugTeamId IN :scope))))
            """;

    @Query("SELECT e FROM AlertEvent e WHERE (:alertType IS NULL OR e.alertType = :alertType) AND " + INCIDENTS_FILTER)
    Page<AlertEvent> findIncidents(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("alertType") String alertType,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("outside") boolean outside,
            @Param("scope") List<Long> scope,
            Pageable pageable);

    /** Sayfanın DIŞINDAKİ bir kapsamın toplamı ("Takımımın / Diğer ekiplerin / Tümü" çip sayıları) — findIncidents'la aynı süzgeç. */
    @Query("SELECT COUNT(e) FROM AlertEvent e WHERE (:alertType IS NULL OR e.alertType = :alertType) AND " + INCIDENTS_FILTER)
    long countIncidents(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("alertType") String alertType,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("outside") boolean outside,
            @Param("scope") List<Long> scope);

    /** Root-cause (alertType) pill sayaçları — alertType HARİÇ aynı incident filtreleri. */
    @Query("SELECT e.alertType, COUNT(e) FROM AlertEvent e WHERE " + INCIDENTS_FILTER + " GROUP BY e.alertType")
    List<Object[]> countIncidentsByType(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("q") String q,
            @Param("scoped") boolean scoped,
            @Param("outside") boolean outside,
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
        return findFiltered(resolved, since, until, resolvedSince, resolvedUntil, null, domain, alertType,
                false, NO_TYPE_SCOPE, q, level, acknowledged, teamId, scoped, scope, pageable);
    }

    default List<Object[]> countFilteredByType(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain,
            String q, String level, Boolean acknowledged, Long teamId,
            boolean scoped, List<Long> scope) {
        return countFilteredByType(resolved, since, until, resolvedSince, resolvedUntil, null, domain,
                false, NO_TYPE_SCOPE, q, level, acknowledged, teamId, scoped, scope);
    }

    default List<Object[]> countFacets(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain, String alertType,
            String q, Long teamId, boolean scoped, List<Long> scope) {
        return countFacets(resolved, since, until, resolvedSince, resolvedUntil, null, domain, alertType,
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
        return findFiltered(resolved, since, until, resolvedSince, resolvedUntil, null, domain, alertType,
                false, NO_TYPE_SCOPE, null, null, null, null, scoped, scope, pageable);
    }

    /** {@link #findFiltered} ile aynı gerekçe — eski imza korunur. */
    default List<Object[]> countFilteredByType(Boolean resolved, String since, String until,
            String resolvedSince, String resolvedUntil, String domain,
            boolean scoped, List<Long> scope) {
        return countFilteredByType(resolved, since, until, resolvedSince, resolvedUntil, null, domain,
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
    @Query(ALERT_LIST_FACETS + "GROUP BY e.alertLevel, e.acknowledged")
    List<Object[]> countFacets(
            @Param("resolved") Boolean resolved,
            @Param("since") String since,
            @Param("until") String until,
            @Param("resolvedSince") String resolvedSince,
            @Param("resolvedUntil") String resolvedUntil,
            @Param("activeFrom") String activeFrom,
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
    @Query(ALERT_LIST_STALE)
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

    // ── Performans projeksiyonları (2026-10-01) — tam entity (5 TEXT sütun) yerine yalnız okunan alanlar ──────────

    /**
     * Menü alarm rozetleri ({@code OpenAlertsSummaryService}): AÇIK alarmların (tip, seviye, sahiplenildi) kırılımı — TEK
     * gruplu sorgu, KESİN sayılar (eskiden seviye/sahiplenilmemiş kırılımı en yeni 200 alarmdan örneklenirdi). Takım
     * kapsamı {@link #findFiltered} ile AYNI yüklem (damgalı takım ya da envanterin SY/UG'si).
     * Sütunlar: {@code [alertType, alertLevel, acknowledged, count]}.
     */
    @Query("""
            SELECT e.alertType, e.alertLevel, e.acknowledged, COUNT(e) FROM AlertEvent e
            WHERE e.resolved = false
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            GROUP BY e.alertType, e.alertLevel, e.acknowledged
            """)
    List<Object[]> countOpenByTypeLevelAck(@Param("scoped") boolean scoped, @Param("scope") List<Long> scope);

    /**
     * Menü alarm rozetlerinin özet kartı: verilen tiplerdeki en yeni AÇIK alarmlar, dar projeksiyon + damgalı takımın adı
     * (LEFT JOIN — ayrı takım sorgusu yok). Sayfa yalnız LIMIT için ({@code List} dönüşü → COUNT sorgusu YOK).
     * Kapsam {@link #countOpenByTypeLevelAck} ile aynı.
     * Sütunlar: {@code [id, domain, alertType, alertLevel, createdAt, acknowledged, teamId, stormId, teamName]}.
     */
    @Query("""
            SELECT e.id, e.domain, e.alertType, e.alertLevel, e.createdAt, e.acknowledged, e.teamId, e.stormId, t.name
            FROM AlertEvent e LEFT JOIN Team t ON t.id = e.teamId
            WHERE e.resolved = false
              AND e.alertType IN :types
              AND (:scoped = FALSE OR e.teamId IN :scope OR EXISTS (
                      SELECT 1 FROM CertificateInventory i
                       WHERE i.domain = e.domain
                         AND (i.teamId IN :scope OR i.ugTeamId IN :scope)))
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<Object[]> findOpenSummaryItems(@Param("types") Collection<String> types,
                                        @Param("scoped") boolean scoped, @Param("scope") List<Long> scope,
                                        Pageable pageable);

    /**
     * Alarm gürültü analizi ({@code AlertNoiseService}): pencere içinde AÇILAN alarmların yalnız okunan alanları — 90 güne
     * kadar tam entity yüklemek yerine. ORDER BY YOK (sıralama gerekirse bellekte); kapsam çağıranda.
     * İndeks: {@code alert_events(created_at)}.
     * Sütunlar: {@code [domain, alertType, alertLevel, createdAt, resolvedAt, resolved, resolvedSilently, acknowledged, teamId]}.
     */
    @Query("""
            SELECT e.domain, e.alertType, e.alertLevel, e.createdAt, e.resolvedAt, e.resolved, e.resolvedSilently,
                   e.acknowledged, e.teamId
            FROM AlertEvent e
            WHERE e.createdAt >= :since
            """)
    List<Object[]> findNoiseRowsSince(@Param("since") String since);

    /**
     * Aylık yönetici özeti (2026-10-10) — {@code [from, to)} aralığında AÇILAN alarmların dar izdüşümü (ay başına TEK
     * sorgu; MTTA için sahiplenme anı da). Sınırlar UTC ISO metin (İstanbul ay başının UTC karşılığı).
     * Sütunlar: {@code [domain, alertType, alertLevel, createdAt, resolvedAt, resolved, resolvedSilently, acknowledged,
     * acknowledgedAt, teamId]}.
     */
    @Query("""
            SELECT e.domain, e.alertType, e.alertLevel, e.createdAt, e.resolvedAt, e.resolved, e.resolvedSilently,
                   e.acknowledged, e.acknowledgedAt, e.teamId
            FROM AlertEvent e
            WHERE e.createdAt >= :from AND e.createdAt < :to
            """)
    List<Object[]> findExecutiveRows(@Param("from") String from, @Param("to") String to);

    /** Aylık yönetici özeti: {@code [from, to)} aralığında açılan alarm SAYISI (önceki ay karşılaştırması). */
    @Query("SELECT COUNT(e) FROM AlertEvent e WHERE e.createdAt >= :from AND e.createdAt < :to")
    long countCreatedBetween(@Param("from") String from, @Param("to") String to);

    /**
     * Gürültü ısı haritası HÜCRE ayrıntısı (2026-10-01): pencerenin alarmları, satıra gidiş için kimlikle. Dar izdüşüm —
     * gün × saat (İstanbul) süzgeci Java'da (created_at metin; dilim birden çok haftaya yayılır). Yalnız tıklanınca çalışır.
     * Sütunlar: {@code [id, domain, alertType, alertLevel, createdAt, resolvedAt, resolved, acknowledged, teamId]}.
     */
    @Query("""
            SELECT e.id, e.domain, e.alertType, e.alertLevel, e.createdAt, e.resolvedAt, e.resolved, e.acknowledged, e.teamId
            FROM AlertEvent e
            WHERE e.createdAt >= :since
            """)
    List<Object[]> findNoiseSlotRowsSince(@Param("since") String since);

    /**
     * İzleme Panosu ({@code MonitoringOverviewService}): {@code from}'dan beri GERÇEKTEN kurtarılarak kapanan alarmlar
     * (sessiz kapanış hariç — {@link #countRecoveredByType} ile aynı kural), (tip, damgalı takım) başına sayı. Kapsam
     * (damgalı takım görüş kapsamında) çağıranda, takım sütunu üzerinden uygulanır.
     * Sütunlar: {@code [alertType, teamId, count]}.
     */
    @Query("""
            SELECT e.alertType, e.teamId, COUNT(e) FROM AlertEvent e
            WHERE e.resolved = true
              AND e.resolvedAt >= :from
              AND (e.resolvedSilently IS NULL OR e.resolvedSilently = false)
            GROUP BY e.alertType, e.teamId
            """)
    List<Object[]> countRecoveredSinceByTypeAndTeam(@Param("from") String from);

    /** İmza zaman çizelgesi (en yeniden eskiye, SAYFALI): "bu alarmdan ÖNCEKİ oluşum" için. */
    @Query("SELECT e.domain, e.alertType, e.createdAt FROM AlertEvent e "
         + "WHERE e.domain IN :domains ORDER BY e.createdAt DESC")
    List<Object[]> findSignatureTimeline(@Param("domains") Collection<String> domains, Pageable pageable);

}
