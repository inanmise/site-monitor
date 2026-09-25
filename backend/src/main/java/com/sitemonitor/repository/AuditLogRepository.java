package com.sitemonitor.repository;

import com.sitemonitor.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * APPEND-ONLY denetim deposu. Bilerek {@code JpaRepository} DEĞİL, çıplak {@link Repository} genişletir —
 * böylece {@code delete*} / genel {@code saveAll}/update yüzeyi uygulamaya HİÇ AÇILMAZ (kurcalanamazlık).
 * İzin verilenler: yeni kayıt {@link #save} (insert), okuma sorguları, ve YALNIZ geo/PTR kolonlarını güncelleyen
 * dar {@link #updateGeo} (çekirdek/hash alanlarına dokunamaz → hash zinciri kırılmaz). Retention silmesi
 * uygulama repo'sundan değil, {@code SchedulerService.cleanupOldLogs} JDBC'sinden yapılır.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    // ── Yazma: yalnız insert + dar geo update ────────────────────────────────────
    AuditLog save(AuditLog entry);

    Optional<AuditLog> findById(Long id);

    /** Geo/PTR zenginleştirmesi — hash'e girmeyen kolonlar; async backfill. Çekirdek alanlara dokunmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE AuditLog a SET a.ipCountry = :country, a.ipCity = :city, a.ipOrg = :org, " +
           "a.ipReverseHost = :host WHERE a.id = :id")
    int updateGeo(@Param("id") Long id, @Param("country") String country, @Param("city") String city,
                  @Param("org") String org, @Param("host") String host);

    // ── Hash zinciri ─────────────────────────────────────────────────────────────
    /** Zincirin ucu (en yüksek seq) — persist() bir sonraki seq/prev_hash'i buradan alır. */
    Optional<AuditLog> findTopByOrderBySeqDesc();
    /** Yapılandırma sağlığı kartı (2026-09-12): son SMTP_TEST / LDAP_TEST sonucu. */
    Optional<AuditLog> findTopByEventTypeOrderByEventTimeDesc(String eventType);

    /** Doğrulama için zincirlenmiş (seq'i olan) satırları seq sırasıyla sayfalı okur (legacy null-seq hariç). */
    List<AuditLog> findBySeqNotNullOrderBySeqAsc(Pageable pageable);

    // ── Anomali/analitik okuma (mevcut) ──────────────────────────────────────────
    @Query("SELECT COUNT(a) > 0 FROM AuditLog a WHERE a.actor = :actor AND a.ipAddress = :ip AND a.outcome = 'SUCCESS' AND a.eventType = 'LOGIN'")
    boolean existsSuccessfulLoginFromIp(@Param("actor") String actor, @Param("ip") String ip);

    @Query("SELECT a FROM AuditLog a WHERE a.actor = :actor AND a.eventType = 'LOGIN' AND a.outcome = 'SUCCESS' AND a.eventTime > :since ORDER BY a.eventTime DESC")
    List<AuditLog> findRecentSuccessfulLogins(@Param("actor") String actor, @Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.actor = :actor AND a.eventType = 'LOGIN_FAILED' AND a.eventTime > :since")
    long countRecentFailedLogins(@Param("actor") String actor, @Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventTime > :since")
    long countEventsSince(@Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '' AND a.eventTime > :since")
    long countAnomaliesSince(@Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.eventTime > :since")
    long countFailedLoginsSince(@Param("since") String since);

    // ── Başarısız-login anomali detektörü için toplu (aggregate) sorgular — hepsi salt-okunur ──
    /** Verilen (from, to] penceresindeki toplam başarısız login (taban/karşılaştırma için). */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.eventTime > :from AND a.eventTime <= :to")
    long countFailedLoginsBetween(@Param("from") String from, @Param("to") String to);

    /** Hesap-bazlı: pencere içinde en çok hedeflenen kullanıcılar (actor, count) — desc. */
    @Query("SELECT a.actor, COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.actor IS NOT NULL AND a.eventTime > :since GROUP BY a.actor ORDER BY COUNT(a) DESC")
    List<Object[]> countFailedByActorSince(@Param("since") String since);

    /** IP-bazlı: pencere içinde en aktif kaynak IP'ler (ip, count) — desc (brute force). */
    @Query("SELECT a.ipAddress, COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.ipAddress IS NOT NULL AND a.eventTime > :since GROUP BY a.ipAddress ORDER BY COUNT(a) DESC")
    List<Object[]> countFailedByIpSince(@Param("since") String since);

    /** IP başına FARKLI kullanıcı sayısı (ip, distinctUsers) — desc (credential stuffing / user enumeration). */
    @Query("SELECT a.ipAddress, COUNT(DISTINCT a.actor) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.ipAddress IS NOT NULL AND a.actor IS NOT NULL AND a.eventTime > :since GROUP BY a.ipAddress ORDER BY COUNT(DISTINCT a.actor) DESC")
    List<Object[]> countDistinctUsersPerIpSince(@Param("since") String since);

    /** Hesap başına FARKLI IP sayısı (actor, distinctIps) — desc (dağıtık saldırı). */
    @Query("SELECT a.actor, COUNT(DISTINCT a.ipAddress) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.actor IS NOT NULL AND a.ipAddress IS NOT NULL AND a.eventTime > :since GROUP BY a.actor ORDER BY COUNT(DISTINCT a.ipAddress) DESC")
    List<Object[]> countDistinctIpsPerActorSince(@Param("since") String since);

    /** Failure-reason önekine (makine kodu) göre sayım — satır çekmeden dağılım (BAD_PASSWORD:%, UNKNOWN_USER:% …). */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.failureReason LIKE :like AND a.eventTime > :from AND a.eventTime <= :to")
    long countFailedByReasonLikeBetween(@Param("like") String like, @Param("from") String from, @Param("to") String to);

    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :since ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsSince(@Param("types") List<String> types, @Param("since") String since);

    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :from AND a.eventTime <= :to ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsBetween(@Param("types") List<String> types,
                                          @Param("from") String from, @Param("to") String to);

    Optional<AuditLog> findTopByActorAndSessionIdOrderByEventTimeDesc(String actor, String sessionId);

    /** Oturumun LOGIN satırı — oturum süresi bunun zamanından hesaplanır (aynı oturumdaki sonraki denetim
     *  satırları, ör. anomali onayı, "login zamanını" ileri kaydırmasın — 2026-09-13). */
    Optional<AuditLog> findTopByActorAndSessionIdAndEventTypeOrderByEventTimeDesc(String actor, String sessionId, String eventType);

    Optional<AuditLog> findTopByActorAndEventTypeAndOutcomeOrderByEventTimeDesc(
            String actor, String eventType, String outcome);

    // ── Filtreli okuma (admin) ───────────────────────────────────────────────────
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE :actor) AND " +
           "(:eventType IS NULL OR a.eventType = :eventType) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findFiltered(
        @Param("actor") String actor,
        @Param("eventType") String eventType,
        @Param("outcome") String outcome,
        @Param("since") String since,
        @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly,
        Pageable pageable);

    // ── Gelişmiş filtre + kaynak/aktör geçmişi (Batch C) ─────────────────────────

    /**
     * Zengin filtre: aktör(LIKE)/actorId(exact)/çoklu-eventType/kaynak-tür+id/outcome/ip/tarih/anomali/serbest-metin.
     *
     * <p><b>Ekip kapsamı (2026-09-25, kullanıcı kararı "tüm olaylar, tam ayrıntı").</b> {@code scopeAll} true ise
     * (global admin / AUDIT) kısıt yok. Aksi hâlde satır yalnız AKTÖRÜ ekip arkadaşıysa döner: olay anındaki takımı
     * ({@code actorTeamId}) kapsamda, ya da kimliği / küçük harf kullanıcı adı kapsamdaki takımların üyeleri
     * arasında (takımı sonradan değişen üye ve kimliksiz giriş olayları için). Boş listeler kukla değerle gelir
     * ({@code TeamActorScope}).
     */
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:scopeAll = TRUE OR a.actorTeamId IN :scopeTeamIds OR a.actorId IN :scopeActorIds " +
           " OR LOWER(a.actor) IN :scopeActorNames) AND " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE :actor) AND " +
           "(:actorId IS NULL OR a.actorId = :actorId) AND " +
           "(:typeFilter = FALSE OR a.eventType IN :types) AND " +
           "(:resourceType IS NULL OR a.resourceType = :resourceType) AND " +
           "(:resourceId IS NULL OR a.resourceId = :resourceId) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:ip IS NULL OR a.ipAddress = :ip) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) AND " +
           "(:q IS NULL OR LOWER(a.actor) LIKE :q OR LOWER(a.resourceId) LIKE :q OR LOWER(a.detail) LIKE :q OR LOWER(a.changes) LIKE :q) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findAdvanced(
        @Param("actor") String actor, @Param("actorId") Long actorId,
        @Param("typeFilter") boolean typeFilter, @Param("types") List<String> types,
        @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
        @Param("outcome") String outcome, @Param("ip") String ip,
        @Param("since") String since, @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly, @Param("q") String q,
        @Param("scopeAll") boolean scopeAll, @Param("scopeTeamIds") Collection<Long> scopeTeamIds,
        @Param("scopeActorIds") Collection<Long> scopeActorIds, @Param("scopeActorNames") Collection<String> scopeActorNames,
        Pageable pageable);

    /** Bir kaynağın tüm geçmişi ("bu izlemeye kim ne yaptı"). */
    List<AuditLog> findByResourceTypeAndResourceIdOrderByEventTimeDesc(String resourceType, String resourceId, Pageable pageable);

    /** Bir kaynak TÜRÜNÜN tüm geçmişi (ör. tüm RETENTION_POLICY değişiklikleri). */
    List<AuditLog> findByResourceTypeOrderByEventTimeDesc(String resourceType, Pageable pageable);

    /** Yönetim Paneli değişiklik geçmişi (2026-09-20): kaynak + olay türü beyaz listesi, sayfalı (toplam sayı gerçek). */
    org.springframework.data.domain.Page<AuditLog> findByResourceTypeAndEventTypeIn(String resourceType, java.util.Collection<String> eventTypes, Pageable pageable);
    org.springframework.data.domain.Page<AuditLog> findByResourceTypeAndResourceIdAndEventTypeIn(String resourceType, String resourceId, java.util.Collection<String> eventTypes, Pageable pageable);

    /** Bir kullanıcının tüm eylemleri. */
    List<AuditLog> findByActorIdOrderByEventTimeDesc(Long actorId, Pageable pageable);

    /**
     * İzleme geçmişi geri doldurması (tek seferlik) — verilen olay türlerinin TAMAMI, EN ESKİDEN
     * yeniye. Sıra önemli: kaydı ilk oluşturan aktör en eski CREATE satırından okunur.
     */
    List<AuditLog> findByEventTypeInOrderByEventTimeAsc(java.util.Collection<String> eventTypes);

    /** Aynı istekten doğan ilişkili olaylar. */
    List<AuditLog> findByCorrelationIdOrderBySeqAsc(String correlationId);

    /** Özet: pencere içi olay-türü dağılımı. */
    @Query("SELECT a.eventType, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY a.eventType ORDER BY COUNT(a) DESC")
    List<Object[]> countByEventTypeSince(@Param("since") String since);

    /** Özet: pencere içi sonuç (SUCCESS/FAILURE/BLOCKED) dağılımı. */
    @Query("SELECT a.outcome, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY a.outcome")
    List<Object[]> countByOutcomeSince(@Param("since") String since);

    /** Özet: en aktif aktörler. */
    @Query("SELECT a.actor, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since AND a.actor IS NOT NULL GROUP BY a.actor ORDER BY COUNT(a) DESC")
    List<Object[]> topActorsSince(@Param("since") String since, Pageable pageable);

    /** Özet: günlük olay yoğunluğu (zaman-yoğunluğu grafiği). eventTime ISO string → ilk 10 karakter = gün (yyyy-MM-dd). */
    @Query("SELECT SUBSTRING(a.eventTime, 1, 10), COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY SUBSTRING(a.eventTime, 1, 10) ORDER BY SUBSTRING(a.eventTime, 1, 10)")
    List<Object[]> countByDaySince(@Param("since") String since);

    /**
     * findFiltered ile aynı ama actor tam (case-insensitive) eşleşme — bir kullanıcı adı başkasının
     * alt-dizesi olsa bile karışmasın. /api/me/audit'i besler.
     */
    // ── Cihaz Geçmişi ekranı (self-scope) ────────────────────────────────────────
    //
    // findOwnFiltered ile AYNI güvenlik deseni: actor TAM ve case-insensitive eşleşir
    // (LOWER) — bir kullanıcı adı başkasının alt-dizesi olsa bile karışmaz. Yeni TABLO
    // AÇILMADI: giriş geçmişinin kaynağı audit_log'dur, dolayısıyla ufku
    // `site.monitor.audit.retention-days` ile sınırlıdır (arayüzde not edilir).

    /** Giriş zaman çizelgesi. {@code failed=false} → yalnız LOGIN, {@code true} → yalnız LOGIN_FAILED. */
    @Query("SELECT a FROM AuditLog a WHERE LOWER(a.actor) = :actor "
         + "AND a.eventType = :eventType ORDER BY a.eventTime DESC")
    Page<AuditLog> findOwnLogins(@Param("actor") String actor,
                                 @Param("eventType") String eventType,
                                 Pageable pageable);

    /** Kullanıcının EN SON başarılı girişi — "Bu cihaz" kartının IP/konum/zaman kaynağı. */
    @Query("SELECT a FROM AuditLog a WHERE LOWER(a.actor) = :actor "
         + "AND a.eventType = 'LOGIN' AND a.outcome = 'SUCCESS' ORDER BY a.eventTime DESC LIMIT 1")
    Optional<AuditLog> findLatestOwnLogin(@Param("actor") String actor);

    /** Tek satır self-scope doğrulaması — "bu girişi ben yapmadım" akışında IDOR kapısı. */
    @Query("SELECT a FROM AuditLog a WHERE a.id = :id AND LOWER(a.actor) = :actor")
    Optional<AuditLog> findOwnById(@Param("id") Long id, @Param("actor") String actor);

    /**
     * E1: kullanıcının GEÇMİŞTE giriş yaptığı FARKLI User-Agent dizeleri.
     *
     * <p>"Yeni cihaz" tespiti neden HAM UA eşitliğiyle YAPILMAZ: tarayıcı her güncellendiğinde
     * ham dize değişir ({@code Chrome/120} → {@code Chrome/121}), yani ham karşılaştırma her
     * tarayıcı güncellemesinde "yeni cihaz" der ve kullanıcıyı yanlış alarma boğar. Çağıran bu
     * listeyi {@link com.sitemonitor.service.UserAgentSummary} özetine indirger ("Windows ·
     * Chrome") ve karşılaştırmayı ONUN üzerinden yapar — sürümden bağımsız, kararlı.
     *
     * <p>DISTINCT olduğu için küme küçüktür (kullanıcı başına birkaç tarayıcı).
     */
    @Query("SELECT DISTINCT a.userAgent FROM AuditLog a WHERE LOWER(a.actor) = :actor "
         + "AND a.eventType = 'LOGIN' AND a.outcome = 'SUCCESS' "
         + "AND a.userAgent IS NOT NULL AND a.id <> :excludeId")
    List<String> findDistinctLoginUserAgents(@Param("actor") String actor,
                                             @Param("excludeId") Long excludeId);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "LOWER(a.actor) = :actor AND " +
           "(:eventType IS NULL OR a.eventType = :eventType) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findOwnFiltered(
        @Param("actor") String actor,
        @Param("eventType") String eventType,
        @Param("outcome") String outcome,
        @Param("since") String since,
        @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly,
        Pageable pageable);
}
