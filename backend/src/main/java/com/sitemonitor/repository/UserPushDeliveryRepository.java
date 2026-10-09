package com.sitemonitor.repository;

import com.sitemonitor.model.UserPushDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserPushDeliveryRepository extends JpaRepository<UserPushDelivery, Long> {

    /**
     * Outbox: worker'ın alacağı ZAMANI GELMİŞ satırlar (yaş sırasıyla — açlık olmasın). {@code nextAttemptAt}
     * boş = yeni satır, hemen; dolu = backoff bitene dek alınmaz. Eskiden süzgeçsiz PENDING okunuyordu: süpürme
     * ve her yeni kuyruk satırı bekleyen retry'ı hemen yeniden gönderiyor, 2. ve 3. deneme milisaniyeler içinde
     * tükeniyordu (2026-09-28). Damgalar {@code createdAt} ile aynı biçimde → metin karşılaştırması sıralıdır.
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE d.status = 'PENDING' AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
           ORDER BY d.id ASC
           """)
    List<UserPushDelivery> findDuePending(@Param("now") String now, Pageable pageable);

    /**
     * Outbox SAHİPLENME (2026-10-01, onaylı öneri 3): zamanı gelmiş PENDING satırları bu tur için KİRALAR —
     * {@code nextAttemptAt}'a tur başına tekil bir kira damgası yazılır; {@link #findDuePending} kira bitene dek satırı
     * vermez. İki pod aynı satırı aynı anda okuyup ikisi de göndermesin (release ortamı iki replika). Koşul aynı:
     * yalnız hâlâ PENDING ve zamanı gelmiş satır kiralanır; başka pod'un kiraladığı satır atlanır. Gönderim sonucu
     * (SENT / backoff / FAILED) damgayı her zamanki gibi ezer; pod gönderirken ölürse kira bitince satır yeniden alınır.
     */
    @Modifying
    @Transactional
    @Query("""
           UPDATE UserPushDelivery d SET d.nextAttemptAt = :lease
           WHERE d.id IN :ids AND d.status = 'PENDING' AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
           """)
    int claimDue(@Param("ids") java.util.Collection<Long> ids, @Param("now") String now, @Param("lease") String lease);

    /** Bu turun kiraladığı satırlar — kira damgası tur başına tekil olduğundan yalnız bu pod'un aldıkları döner. */
    List<UserPushDelivery> findByIdInAndNextAttemptAtOrderByIdAsc(java.util.Collection<Long> ids, String nextAttemptAt);

    /**
     * Gönderim sonucunun DAR yazımı — tam satır kaydı düştüğünde yedek (2026-09-28): durum ve deneme sayacı yine
     * ilerler. Yazılmazsa satır PENDING + eski sayaçla kalır ve her süpürmede yeniden gönderilir (zehirli satır).
     */
    @Modifying
    @Transactional
    @Query("""
           UPDATE UserPushDelivery d SET d.status = :status, d.attempts = :attempts, d.httpStatus = :httpStatus,
                  d.error = :error, d.sentAt = :sentAt, d.nextAttemptAt = :nextAttemptAt
           WHERE d.id = :id
           """)
    int updateOutcome(@Param("id") Long id, @Param("status") String status, @Param("attempts") Integer attempts,
                      @Param("httpStatus") Integer httpStatus, @Param("error") String error,
                      @Param("sentAt") String sentAt, @Param("nextAttemptAt") String nextAttemptAt);

    /**
     * Çözüm anında hâlâ GÖNDERİLMEMİŞ (PENDING — ağ geçidi yeniden denemesi / devre kesici bekletmesi) açılış, eskalasyon,
     * tekrar satırlarını iptal eder (2026-10-09). Yoksa alarm düzeldikten SONRA "DÜŞTÜ" push'u gidiyor, açılışta SENT satır
     * olmadığı için "DÜZELDİ" hiç gitmiyordu — telefon sonsuza kadar "düştü" gösteriyordu. Çözüm satırına dokunmaz.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
           UPDATE UserPushDelivery d SET d.status = :status, d.nextAttemptAt = NULL
           WHERE d.alertEventId = :eventId AND d.status = 'PENDING' AND d.trigger <> 'RESOLVE'
           """)
    int cancelPendingForResolved(@Param("eventId") Long eventId, @Param("status") String status);

    /** Dedupe ön-kontrolü (yarışta son söz UNIQUE kısıtın — bu yalnız gürültüsüz erken çıkış). */
    boolean existsByAlertEventIdAndDedupeKeyAndUsername(Long alertEventId, String dedupeKey, String username);
    /** Olaysız takım bildirimi (Zayıf Algoritma Raporu) dedupe'u — alertEventId yok. */
    boolean existsByDedupeKeyAndUsername(String dedupeKey, String username);
    /** Fırtına karar satırı dedupe'u — takım başına (aynı fırtınada iki takımın kararı ayrı yazılır). */
    boolean existsByDedupeKeyAndUsernameAndTeamId(String dedupeKey, String username, Long teamId);
    /** Fırtına çözüm push'unun alıcıları: bu fırtınanın bu takıma giden açılış/tekrar satırları (anahtar öneki). */
    List<UserPushDelivery> findByDedupeKeyStartingWithAndTeamIdAndStatusOrderByIdAsc(String dedupeKeyPrefix, Long teamId,
                                                                                       String status);

    /** Alarm listesi "kanal durumu" çipi (2026-09-12, #16): sayfadaki alarmlar için durum × adet, tek sorgu. */
    @Query("SELECT d.alertEventId, d.status, COUNT(d) FROM UserPushDelivery d WHERE d.alertEventId IN :ids GROUP BY d.alertEventId, d.status")
    List<Object[]> countByAlertEventIdInGroupByStatus(@Param("ids") java.util.Collection<Long> ids);

    /** Saat tavanı: kullanıcı başına son bir saatte yazılmış GÖNDERİLEBİLİR satır sayısı — YALNIZ gerçekten
     *  gönderilmek üzere kuyruğa giren/gönderilen satırlar (PENDING / SENT / FAILED). Eskiden RATE_LIMITED ve
     *  CIRCUIT_OPEN satırlarını da sayıyordu (prod kapısı 2026-09-25, O-3): tavan kendi ret satırlarıyla
     *  besleniyor, flapping izlemeli kullanıcı saatlerce "tavanda" kalıyordu — javadoc ile kod ayrışmıştı. */
    @Query("""
           SELECT COUNT(d) FROM UserPushDelivery d
           WHERE d.username = :username AND d.createdAt >= :since
             AND d.status IN ('PENDING','SENT','FAILED')
           """)
    long countRecentForUser(@Param("username") String username, @Param("since") String since);

    /** Çözüm simetrisi: olaya daha önce gerçekten push GİTTİ Mİ? */
    boolean existsByAlertEventIdAndStatus(Long alertEventId, String status);

    /** Alarm modalı: olayın webhook teslimatları (e-posta satırlarının kanal-ayrımlı eşleniği). */
    List<UserPushDelivery> findByAlertEventIdOrderByIdAsc(Long alertEventId);

    /**
     * Fırtına push'u ↔ alarm bağı (2026-10-04): verilen fırtına bildirim anahtarlarının TÜM satırları (alıcılar + kanal karar
     * satırı) — alarm detayının "Fırtına push'u" bölümü ve teslimat günlüğü. Tek sorgu (anahtar sayısı alarm başına birkaç).
     */
    List<UserPushDelivery> findByDedupeKeyInOrderByIdAsc(java.util.Collection<String> dedupeKeys);

    /**
     * Push geçmişim (2026-10-04): verilen fırtına bildirimlerinde kişinin KENDİ satırları (kullanıcı adı büyük/küçük harf
     * duyarsız) + bildirimin kanal karar satırları ('-'). Başka kişinin satırı dönmez.
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE d.dedupeKey IN :keys
             AND (LOWER(d.username) = LOWER(CAST(:username AS string)) OR d.username = '-')
           ORDER BY d.id ASC
           """)
    List<UserPushDelivery> findStormNoticeRowsForViewer(@Param("keys") java.util.Collection<String> keys,
                                                        @Param("username") String username);

    /**
     * Teslimat günlüğü — K11 süzgeçleri. CAST kuralı {@code MonitorChangeLogRepository.search}
     * ile aynı: Postgres null metin parametresini tip bilgisi olmadan bytea bağlar ve LOWER(bytea)
     * yoktur → sorgu 500 verir. Yalnız null geçebilen metin parametreleri sarılır.
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE (:username IS NULL OR LOWER(d.username) = LOWER(CAST(:username AS string)))
             AND (:teamId IS NULL OR d.teamId = :teamId)
             AND (:monitorType IS NULL OR d.monitorType = :monitorType)
             AND (:level IS NULL OR d.alertLevel = :level)
             AND (:status IS NULL OR d.status = :status)
             AND (:trigger IS NULL OR d.trigger = :trigger)
             AND (:notificationId IS NULL OR d.notificationId = :notificationId)
             AND (:q IS NULL OR LOWER(d.monitorName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
             AND (:from IS NULL OR d.createdAt >= :from)
             AND (:to IS NULL OR d.createdAt <= :to)
           ORDER BY d.id DESC
           """)
    Page<UserPushDelivery> search(@Param("username") String username,
                                  @Param("teamId") Long teamId,
                                  @Param("monitorType") String monitorType,
                                  @Param("level") String level,
                                  @Param("status") String status,
                                  @Param("trigger") String trigger,
                                  @Param("notificationId") String notificationId,
                                  @Param("q") String q,
                                  @Param("from") String from,
                                  @Param("to") String to,
                                  Pageable pageable);

    /**
     * {@link #search} + TAKIM KAPSAMI (2026-09-28 regresyon taraması): kapsamlı müdür yalnız YÖNETTİĞİ takımların
     * alarmlarına ait satırları görür (takımsız test/sistem satırları dâhil değil). {@code teamIds} BOŞ geçilmez —
     * çağıran boş kapsamda sorguya hiç gitmez.
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE d.teamId IN :teamIds
             AND (:username IS NULL OR LOWER(d.username) = LOWER(CAST(:username AS string)))
             AND (:monitorType IS NULL OR d.monitorType = :monitorType)
             AND (:level IS NULL OR d.alertLevel = :level)
             AND (:status IS NULL OR d.status = :status)
             AND (:trigger IS NULL OR d.trigger = :trigger)
             AND (:notificationId IS NULL OR d.notificationId = :notificationId)
             AND (:q IS NULL OR LOWER(d.monitorName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
             AND (:from IS NULL OR d.createdAt >= :from)
             AND (:to IS NULL OR d.createdAt <= :to)
           ORDER BY d.id DESC
           """)
    Page<UserPushDelivery> searchInTeams(@Param("teamIds") java.util.Collection<Long> teamIds,
                                         @Param("username") String username,
                                         @Param("monitorType") String monitorType,
                                         @Param("level") String level,
                                         @Param("status") String status,
                                         @Param("trigger") String trigger,
                                         @Param("notificationId") String notificationId,
                                         @Param("q") String q,
                                         @Param("from") String from,
                                         @Param("to") String to,
                                         Pageable pageable);

    /** E3 istatistik şeridi: pencere içi durum dağılımı. */
    @Query("""
           SELECT d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since GROUP BY d.status
           """)
    List<Object[]> countByStatusSince(@Param("since") String since);

    /** KPI kartları takım kırılımı (2026-09-12): pencere içi durum × takım sayıları. teamId null = alarmın takımı yok (test/sistem). */
    @Query("""
           SELECT d.teamId, d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since GROUP BY d.teamId, d.status
           """)
    List<Object[]> countByTeamAndStatusSince(@Param("since") String since);

    /** E4 devre-kesici sağlık sinyali: pencere içi ardışık olmayan toplam FAILED. */
    long countByStatusAndCreatedAtGreaterThanEqual(String status, String since);

    /** "Sizin için — bugün" teslim edilemeyen bildirim kartı (2026-09-19): pencere içi FAILED satırları, yeni üstte. */
    List<UserPushDelivery> findByStatusAndCreatedAtGreaterThanEqualOrderByIdDesc(String status, String since);

    /**
     * Webhook Push Gönderim Logu penceresi (2026-09-19; PushLogQueryService) — {@code message} ve {@code rawResponse}
     * HARİÇ sütunlar (gövde yalnız satır detayında). Sıra: id, alertEventId, trigger, monitorType, monitorId, monitorName,
     * teamId, alertLevel, username, displayName, title, status, httpStatus, error, attempts, createdAt, sentAt, batchId,
     * notificationId, overflowSummaryId, pushLang (son ikisi 2026-10-04 — SONA eklendi; konumlar sabit kalır).
     */
    @Query("""
           SELECT d.id, d.alertEventId, d.trigger, d.monitorType, d.monitorId, d.monitorName, d.teamId, d.alertLevel,
                  d.username, d.displayName, d.title, d.status, d.httpStatus, d.error, d.attempts, d.createdAt, d.sentAt,
                  d.batchId, d.notificationId, d.overflowSummaryId, d.pushLang
           FROM UserPushDelivery d
           WHERE d.createdAt >= :from AND d.createdAt <= :to
           ORDER BY d.createdAt DESC, d.id DESC
           """)
    List<Object[]> findWindowRows(@Param("from") String from, @Param("to") String to);

    /** Aynı toplu isteğin (batch) tüm alıcı satırları — satır detayındaki "kime gitti / kim düştü". */
    List<UserPushDelivery> findByBatchIdOrderByIdAsc(String batchId);

    // ── Saat tavanı özeti (2026-10-04, onaylı öneri 2) ────────────────────────────────────────────

    /**
     * Özet adayları: henüz özetlenmemiş {@code RATE_LIMITED} satırı olan kullanıcılar — kullanıcı başına İLK satırın damgası
     * ve adet. Sistem satırları ('-') hariç. Tek gruplu sorgu (kullanıcı başına sorgu yok).
     */
    @Query("""
           SELECT d.username, MIN(d.createdAt), COUNT(d) FROM UserPushDelivery d
           WHERE d.status = 'RATE_LIMITED' AND d.overflowSummaryId IS NULL AND d.createdAt >= :since
             AND d.username <> '-'
           GROUP BY d.username
           """)
    List<Object[]> overflowCandidates(@Param("since") String since);

    /** Kullanıcının henüz özetlenmemiş RATE_LIMITED satırları (id sırasıyla, sayfa tavanlı). */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE d.username = :username AND d.status = 'RATE_LIMITED' AND d.overflowSummaryId IS NULL
             AND d.createdAt >= :since
           ORDER BY d.id ASC
           """)
    List<UserPushDelivery> findUnsummarizedOverflow(@Param("username") String username, @Param("since") String since,
                                                    Pageable pageable);

    /**
     * Özet SAHİPLENMESİ: satırlar yalnız hâlâ RATE_LIMITED ve özetsizse bu özete bağlanır — aynı satır iki özete giremez
     * (iki pod / yeniden başlatma). Dönen sayı bu çağrının gerçekten bağladığı satırlardır.
     */
    @Modifying
    @Transactional
    @Query("""
           UPDATE UserPushDelivery d SET d.overflowSummaryId = :summaryId
           WHERE d.id IN :ids AND d.overflowSummaryId IS NULL AND d.status = 'RATE_LIMITED'
           """)
    int claimOverflow(@Param("ids") java.util.Collection<Long> ids, @Param("summaryId") Long summaryId);

    /** Bir özetin kapsadığı satırlar (özet ↔ satır bağı; günlük detayı ve özet metni). */
    List<UserPushDelivery> findByOverflowSummaryIdOrderByIdAsc(Long overflowSummaryId);

    /** Bir özetin kapsadığı satır sayısı (push geçmişim satırı). */
    long countByOverflowSummaryId(Long overflowSummaryId);

    /** Kullanıcıya en son yazılan özet satırının damgası (özet aralığı: kullanıcı başına pencerede en çok bir özet). */
    @Query("SELECT MAX(d.createdAt) FROM UserPushDelivery d WHERE d.username = :username AND d.trigger = 'OVERFLOW_SUMMARY'")
    String lastOverflowSummaryAt(@Param("username") String username);

    // ── Push geçmişim (2026-10-04, onaylı öneri 3) ────────────────────────────────────────────────

    /**
     * Kişinin KENDİ satırları (kullanıcı adı büyük/küçük harf duyarsız) + üyesi olduğu takımların alarmlarına ait OLAY
     * düzeyi karar satırları (kullanıcı adı '-'; eskalasyon adımı kişi kararları hariç — onlar takımın değil tek bir kişinin
     * kaydıdır). {@code teamIds} BOŞ geçilmez (çağıran -1 nöbetçisi verir). Süzgeç:
     * {@code all} / {@code sent} (SENT) / {@code not_sent} (SENT ve PENDING dışı her şey).
     */
    @Query("""
           SELECT d FROM UserPushDelivery d
           WHERE d.createdAt >= :since
             AND (LOWER(d.username) = LOWER(CAST(:username AS string))
                  OR (d.username = '-' AND d.teamId IN :teamIds AND d.trigger <> 'ESCALATION_STEP'))
             AND (:filter = 'all' OR (:filter = 'sent' AND d.status = 'SENT')
                  OR (:filter = 'not_sent' AND d.status <> 'SENT' AND d.status <> 'PENDING'))
           ORDER BY d.id DESC
           """)
    Page<UserPushDelivery> myHistory(@Param("username") String username,
                                     @Param("teamIds") java.util.Collection<Long> teamIds,
                                     @Param("since") String since,
                                     @Param("filter") String filter,
                                     Pageable pageable);

    /** Push geçmişim KPI — kişinin kendi satırları: durum × adet (pencere içi). */
    @Query("""
           SELECT d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since AND LOWER(d.username) = LOWER(CAST(:username AS string))
           GROUP BY d.status
           """)
    List<Object[]> myStatusCounts(@Param("username") String username, @Param("since") String since);

    /** Push geçmişim KPI — kişinin özete katılmış RATE_LIMITED satırları (pencere içi). */
    @Query("""
           SELECT COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since AND LOWER(d.username) = LOWER(CAST(:username AS string))
             AND d.status = 'RATE_LIMITED' AND d.overflowSummaryId IS NOT NULL
           """)
    long mySummarizedCount(@Param("username") String username, @Param("since") String since);

    /** Push geçmişim KPI — üyesi olunan takımların olay düzeyi karar satırları: durum × adet (pencere içi). */
    @Query("""
           SELECT d.status, COUNT(d) FROM UserPushDelivery d
           WHERE d.createdAt >= :since AND d.username = '-' AND d.teamId IN :teamIds AND d.trigger <> 'ESCALATION_STEP'
           GROUP BY d.status
           """)
    List<Object[]> teamDecisionCounts(@Param("teamIds") java.util.Collection<Long> teamIds, @Param("since") String since);

    /** Test tavanı: son bir dakikadaki TEST satırları. */
    long countByTriggerAndCreatedAtGreaterThanEqual(String trigger, String since);

    /**
     * Retention temizliği RetentionCatalog motorundan koşar (doğrudan SQL); bu metot test/elle
     * temizlik içindir. Tx kuralı: deleteBy… @Transactional + int (open-in-view=false tuzağı).
     */
    @Modifying
    @Transactional
    int deleteByCreatedAtBefore(String cutoff);
}
