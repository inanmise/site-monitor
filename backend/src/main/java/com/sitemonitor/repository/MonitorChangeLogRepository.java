package com.sitemonitor.repository;

import com.sitemonitor.model.MonitorChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitorChangeLogRepository extends JpaRepository<MonitorChangeLog, Long> {

    /** Bir kaynağın geçmişi — en yeni üstte. Sıralama createdAt+id ile KESİN (seq best-effort). */
    Page<MonitorChangeLog> findByResourceKindAndResourceIdOrderByCreatedAtDescIdDesc(
            String resourceKind, Long resourceId, Pageable pageable);

    Optional<MonitorChangeLog> findByResourceKindAndResourceIdAndSeq(
            String resourceKind, Long resourceId, Integer seq);

    /** Yeni satırın {@code seq}'i bundan türetilir (yoksa 0 = CREATE). */
    @Query("SELECT MAX(c.seq) FROM MonitorChangeLog c WHERE c.resourceKind = :kind AND c.resourceId = :id")
    Optional<Integer> findMaxSeq(@Param("kind") String kind, @Param("id") Long id);

    /** Kaynak silindiyse kapsam kararı SON satırın takımından okunur. */
    Optional<MonitorChangeLog> findTopByResourceKindAndResourceIdOrderByCreatedAtDesc(
            String resourceKind, Long resourceId);

    /**
     * Yönetici konsolu ve takım akışı — süzgeçler null geçilebilir (tek sorgu, dallanma yok).
     *
     * <p>Geri doldurmanın {@code SYSTEM} nişan satırı DIŞLANIR: o bir izleme değişikliği değil,
     * çalışma kaydı — konsolda "kim neyi değiştirdi" listesinde yeri yok.
     *
     * <p>{@code teamScopeAll} true ise takım kısıtı UYGULANMAZ (global admin/AUDIT); false ise
     * satır İKİ yoldan görünür (2026-09-25): izlemenin takımı {@code teamIds} içinde VEYA değişikliği
     * yapan aktör o takımların üyesi ({@code actorIds} / küçük harf {@code actorNames}) — YALNIZ takımı boş
     * satırlarda (envanter türevi, sentetik): ekip arkadaşının oralarda yaptığı değişiklik de görünsün.
     * Takımı DOLU satırda aktör yolu YOK (kullanıcı kararı 2026-09-25, regresyon R1): ekip arkadaşının başka
     * takımın izlemesinde yaptığı değişiklik yalnız o takıma görünür — kaynak bazlı geçmiş uçları da aynı
     * satırı zaten 404'lüyor, iki uç çelişmesin.
     * Takım süzgeci ({@code teamId}) aynı kuralla: o takımın izlemesi VEYA (takımsız) o takım üyesinin değişikliği. İki ayrı sorgu yazmak yerine tek yerde tutuluyor ki
     * kapsam mantığı ikiye ayrılıp ayrışmasın.
     *
     * <p><b>{@code CAST(:x AS string)} ZORUNLU</b> — süslemedir sanılmasın: PostgreSQL'de null
     * bir metin parametresi tip bilgisi olmadan {@code bytea} olarak bağlanır ve
     * {@code lower(bytea)} diye bir fonksiyon yoktur → sorgu 500 ile düşer (2026-08-22'de
     * konsol tam bu yüzden "Değişiklik geçmişi yüklenemedi" verdi). Hibernate'in bir kısıtı
     * değil, Postgres'in tip çıkarımı; {@code AlertEventRepository} aynı çözümü kullanıyor.
     * Yalnız NULL geçilebilen parametreler sarılır — eşitlik aramalarındaki zorunlu
     * parametrelerin cast'e ihtiyacı yok.
     */
    @Query("""
           SELECT c FROM MonitorChangeLog c
           WHERE c.resourceKind <> 'SYSTEM'
             AND (:kind IS NULL OR c.resourceKind = :kind)
             AND (:eventType IS NULL OR c.eventType = :eventType)
             AND (:actor IS NULL OR LOWER(c.actor) = LOWER(CAST(:actor AS string)))
             AND (:teamId IS NULL OR c.teamId = :teamId OR (c.teamId IS NULL
                  AND (c.actorId IN :filterActorIds OR LOWER(c.actor) IN :filterActorNames)))
             AND (:from IS NULL OR c.createdAt >= :from)
             AND (:to IS NULL OR c.createdAt <= :to)
             AND (:q IS NULL OR LOWER(c.resourceName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
             AND (:teamScopeAll = TRUE OR c.teamId IN :teamIds OR (c.teamId IS NULL
                  AND (c.actorId IN :actorIds OR LOWER(c.actor) IN :actorNames)))
           ORDER BY c.createdAt DESC, c.id DESC
           """)
    Page<MonitorChangeLog> search(@Param("kind") String kind,
                                  @Param("eventType") String eventType,
                                  @Param("actor") String actor,
                                  @Param("teamId") Long teamId,
                                  @Param("filterActorIds") Collection<Long> filterActorIds,
                                  @Param("filterActorNames") Collection<String> filterActorNames,
                                  @Param("from") String from,
                                  @Param("to") String to,
                                  @Param("q") String q,
                                  @Param("teamScopeAll") boolean teamScopeAll,
                                  @Param("teamIds") Collection<Long> teamIds,
                                  @Param("actorIds") Collection<Long> actorIds,
                                  @Param("actorNames") Collection<String> actorNames,
                                  Pageable pageable);

    /**
     * Konsolun özet şeridi: olay tipi dağılımı (aynı kapsam kuralıyla).
     *
     * <p>{@code teamId} süzgecini {@link #countByKindAndEventType} ile AYNI gerekçeyle alır —
     * oradaki uzun açıklamaya bakınız: takım bir GÖRÜNÜRLÜK daraltmasıdır, tür ise bir gezinme
     * süzgeci.
     */
    @Query("""
           SELECT c.eventType, COUNT(c) FROM MonitorChangeLog c
           WHERE c.resourceKind <> 'SYSTEM'
             AND (:teamId IS NULL OR c.teamId = :teamId OR (c.teamId IS NULL
                  AND (c.actorId IN :filterActorIds OR LOWER(c.actor) IN :filterActorNames)))
             AND (:from IS NULL OR c.createdAt >= :from)
             AND (:to IS NULL OR c.createdAt <= :to)
             AND (:teamScopeAll = TRUE OR c.teamId IN :teamIds OR (c.teamId IS NULL
                  AND (c.actorId IN :actorIds OR LOWER(c.actor) IN :actorNames)))
           GROUP BY c.eventType
           """)
    List<Object[]> countByEventType(@Param("from") String from,
                                    @Param("to") String to,
                                    @Param("teamId") Long teamId,
                                    @Param("filterActorIds") Collection<Long> filterActorIds,
                                    @Param("filterActorNames") Collection<String> filterActorNames,
                                    @Param("teamScopeAll") boolean teamScopeAll,
                                    @Param("teamIds") Collection<Long> teamIds,
                                    @Param("actorIds") Collection<Long> actorIds,
                                    @Param("actorNames") Collection<String> actorNames);

    /**
     * Konsolun tür kartları: (izleme türü × olay türü) dağılımı.
     *
     * <p>"Hangi izlemede ne kadar oluşturma/değişiklik/silme oldu" sorusunun tek sorguluk cevabı.
     * Sayfalanan listeden hesaplanamaz — o yalnız GÖRÜNEN sayfayı taşır; kartlar tüm pencereyi
     * özetlemek zorunda.
     *
     * <p><b>TÜR süzgecini kasten ALMAZ:</b> kartlar tür seçmenin GİRİŞ noktası, tür süzgeci
     * uygulanırsa tek karta düşer ve karşılaştırma imkânı kaybolurdu.
     *
     * <p><b>TAKIM süzgecini ise ALIR</b> — ikisi aynı şey değil. Tür, kartların kendi ürettiği
     * bir gezinme süzgeci; takım ise NE KADARINA BAKTIĞIMIZI belirleyen bir görünürlük
     * daraltmasıdır. Alınmasaydı yönetici bir takım seçtiğinde liste daralır ama kartlar tüm
     * takımları saymaya devam eder, rakamlar listeyle çelişirdi.
     *
     * <p>Tarih aralığı da aynı sebeple uygulanır — kullanıcı 30 günü seçmişken kartların 90 günü
     * sayması şeridi ile kartları çelişirdi.
     */
    @Query("""
           SELECT c.resourceKind, c.eventType, COUNT(c) FROM MonitorChangeLog c
           WHERE c.resourceKind <> 'SYSTEM'
             AND (:teamId IS NULL OR c.teamId = :teamId OR (c.teamId IS NULL
                  AND (c.actorId IN :filterActorIds OR LOWER(c.actor) IN :filterActorNames)))
             AND (:from IS NULL OR c.createdAt >= :from)
             AND (:to IS NULL OR c.createdAt <= :to)
             AND (:teamScopeAll = TRUE OR c.teamId IN :teamIds OR (c.teamId IS NULL
                  AND (c.actorId IN :actorIds OR LOWER(c.actor) IN :actorNames)))
           GROUP BY c.resourceKind, c.eventType
           """)
    List<Object[]> countByKindAndEventType(@Param("from") String from,
                                           @Param("to") String to,
                                           @Param("teamId") Long teamId,
                                           @Param("filterActorIds") Collection<Long> filterActorIds,
                                           @Param("filterActorNames") Collection<String> filterActorNames,
                                           @Param("teamScopeAll") boolean teamScopeAll,
                                           @Param("teamIds") Collection<Long> teamIds,
                                           @Param("actorIds") Collection<Long> actorIds,
                                           @Param("actorNames") Collection<String> actorNames);

    /**
     * "Ne zamandır duraklatılmış" (2026-09-23, bugün paneli): verilen kaynakların {@code active} alanının
     * EN SON değiştiği an (tür, id, zaman). Duraklatılmış bir izleme için bu, duraklatılma anıdır.
     * {@code changes} {@code AuditDiff} JSON'u ({@code {"active":{"from":..,"to":..}}}); anahtar metni
     * tırnaklı arandığı için "isActive" gibi başka bir alan yanlış eşleşmez. {@code ids} yalnız
     * DURAKLATILMIŞ izlemelerdir (onlarca) — tablo taranmaz, idx_mchg_resource kullanılır.
     */
    @Query("""
           SELECT c.resourceKind, c.resourceId, MAX(c.createdAt) FROM MonitorChangeLog c
           WHERE c.resourceId IN :ids AND c.eventType = 'UPDATE' AND c.changes LIKE '%"active":{"from":%'
           GROUP BY c.resourceKind, c.resourceId
           """)
    List<Object[]> lastActiveChange(@Param("ids") Collection<Long> ids);

    /** Backfill idempotensi: aynı kaynak+olay+zaman üçlüsü ikinci kez yazılmasın. */
    boolean existsByResourceKindAndResourceIdAndEventTypeAndCreatedAt(
            String resourceKind, Long resourceId, String eventType, String createdAt);

    /**
     * "Geri doldurma koştu mu" nişanı — nişan satırı bu tablonun KENDİSİNDE tutulur.
     *
     * <p>{@code app_settings}'e konmadı: oradaki her anahtar yönetici ayar ekranında listeleniyor
     * ve bu bir ayar değil, tek seferlik bir çalışma kaydı. Aynı tabloda durması ayrıca veritabanı
     * geri yüklemelerinde nişan ile veriyi birlikte tutar — ayrı yerlerde olsalardı geri yüklenen
     * bir yedek "koştu" der ama satırlar olmazdı.
     */
    boolean existsByResourceKindAndEventType(String resourceKind, String eventType);

    /**
     * Nişanın SÜRÜMÜ ({@code seq} kolonunda). Geri doldurma kapsamı sonradan genişleyebilir:
     * yeni bir izleme türü eklendiğinde o türün eski denetim satırları hâlâ taşınmamıştır ve
     * "koştu" nişanı ikinci koşuyu sonsuza dek engellerdi — tür sessizce geçmişsiz kalırdı.
     * Sürüm karşılaştırması yalnız kapsam büyüdüğünde bir kez daha koşmayı mümkün kılar;
     * satır bazındaki kaynak+olay+zaman kontrolü çiftlemeyi zaten engelliyor.
     */
    Optional<MonitorChangeLog> findFirstByResourceKindAndEventTypeOrderByIdDesc(
            String resourceKind, String eventType);

    /**
     * Elle/test temizliği. Saklama süresi temizliğini {@code RetentionCatalog} yapıyor (doğrudan
     * SQL, tüm tablolar için tek motor) — bu metot onun yerine geçmez, ondan bağımsızdır.
     *
     * <p>{@code @Transactional} ŞART: türetilmiş silme sorgusuna Spring Data kendiliğinden tx
     * sarmaz ve {@code open-in-view=false} olduğu için tx'siz çağrı düşer
     * (bkz. {@code RepositoryWriteTransactionGuardTest}). Dönüş silinen satır sayısıdır.
     */
    @Transactional
    int deleteByCreatedAtBefore(String cutoff);
}
