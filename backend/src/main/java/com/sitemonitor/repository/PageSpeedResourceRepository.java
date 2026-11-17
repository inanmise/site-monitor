package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PageSpeedResourceRepository extends JpaRepository<PageSpeedResource, Long>, PageSpeedResourceBatchWriter {

    /** Son ölçümün kırılımı — en ağırdan hafife. */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.monitorId = :id AND r.keepReason = :reason "
         + "ORDER BY r.bytes DESC NULLS LAST LIMIT :limit")
    List<PageSpeedResource> findHeaviest(@Param("id") Long id, @Param("reason") String reason, @Param("limit") int limit);

    /**
     * Gosterilen listenin KAC kaynaktan secildigi — arayuz "N kaynaktan en agir 50'si" diyebilsin
     * diye. Kirilim sessizce kirpilirsa kullanici 50 satiri sayfanin TAMAMI sanir ve agirligin
     * nereden geldigini yanlis okur. Iki sorgu da indeksli COUNT'tur.
     */
    long countByMonitorIdAndKeepReason(Long monitorId, String keepReason);

    /**
     * DIKKAT: izleme kimligiyle birlikte sayilir. Yalniz checkId ile saymak, BASKA bir izlemenin
     * checkId'si gecildiginde o izlemenin kaynak sayisini sizdiriyordu (satirlar zaten filtrelenip
     * bosaliyor, ama sayi "0 / 57" diye ekrana geliyordu).
     */
    @Query("SELECT COUNT(r) FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.checkId = :checkId " + ONE_COPY)
    long countByMonitorIdAndCheckId(@Param("monitorId") Long monitorId, @Param("checkId") Long checkId);

    /**
     * Aynı ölçümün TEK kopyası (inceleme B4): ihlalin başladığı ölçümün satırları hem LATEST hem BREACH olarak yazılır — ölçüm
     * seçilince ikisi birden dönüp liste / CSV / sayaçlar iki kat oluyordu. BREACH kopyası varsa yalnız o (kalıcı delil),
     * yoksa LATEST. {@code :monitorId} / {@code :checkId} adlı parametreleri kullanır; ana sorgu da AYNI iki parametreyle
     * süzer ({@code r.monitorId = :monitorId AND r.checkId = :checkId}).
     *
     * <p>Performans (2026-11-17, EXPLAIN denetimi): alt sorgu artık dış satıra BAĞLI DEĞİL — bağlı biçim
     * ({@code b.monitorId = r.monitorId AND b.checkId = r.checkId}) {@code OR} içinde kaldığı için birleştirmeye
     * çevrilemiyor, satır başına bir SubPlan koşuyordu (ölçümün kaynak sayısında karesel; 2000 kaynaklık ölçüm ≈ 1,5 sn).
     * Dış satır zaten aynı izleme + ölçüm kimliğine süzüldüğünden iki biçim AYNI satırları döndürür; bağımsız biçim tek bir
     * InitPlan'dır ({@code idx_psr_check}). Kapı: {@code PageSpeedResourceRepositoryTest.oneCopyRuleMatchesJavaOracle}.
     */
    String ONE_COPY = "AND (r.keepReason = 'BREACH' OR NOT EXISTS (SELECT 1 FROM PageSpeedResource b WHERE b.monitorId = :monitorId "
            + "AND b.checkId = :checkId AND b.keepReason = 'BREACH')) ";

    /** Son ölçümün (LATEST) kontrol kimliği — CSV bir kez okur ve o ölçümü sayfalar (inceleme B5); kırılım yoksa null. */
    @Query("SELECT MAX(r.checkId) FROM PageSpeedResource r WHERE r.monitorId = :id AND r.keepReason = 'LATEST'")
    Long latestCheckId(@Param("id") Long id);

    /**
     * Görsel aracı köprüsü (2026-11-15): izlemenin SAKLADIĞI kaynaklar arasında adresi birebir eşleşen görsel satırı (en çok 1;
     * herhangi bir kopya yeter — boyut kopyalar arasında aynı ölçümün değeridir). Bulunamazsa boş — uç adresi çekmez (sunucu açık
     * vekil olamaz). Bilerek ORDER BY YOK (inceleme 2026-11-15): "id DESC LIMIT 1" biçimi bulunamayan adreste birincil anahtarı
     * geriye tarayıp tüm tabloyu okuyabilirdi; böyle izleme kimliği önce gelen indeksle yalnız o izlemenin satırları okunur.
     */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.monitorId = :id AND r.type = 'IMG' AND r.url = :url LIMIT 1")
    List<PageSpeedResource> findStoredImage(@Param("id") Long id, @Param("url") String url);

    /**
     * Görsel aracı köprüsü, ölçüm kimliği verilmişse (performans 2026-11-17): adres yalnız O ölçümün saklanan görselleri
     * arasında aranır ({@code idx_psr_check} — izlemenin tüm satırları taranmaz; toplu ZIP'te görsel başına bir kez). Güvenlik
     * kuralı aynı: satır BU izlemenin saklanan IMG kaynağı olmalı.
     */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.monitorId = :id AND r.checkId = :checkId AND r.type = 'IMG' "
         + "AND r.url = :url LIMIT 1")
    List<PageSpeedResource> findStoredImageInCheck(@Param("id") Long id, @Param("checkId") Long checkId, @Param("url") String url);

    /**
     * Ölçümün bu izlemede HİÇ saklanan satırı var mı (en çok 1 kimlik; {@code idx_psr_check}). Görsel köprüsü: ölçüm kimliği
     * verilen ama satırı artık olmayan (son ölçüm yeniden yazıldı — LATEST her kontrolde silinip yazılır) istek bugünkü
     * izleme geneli aramaya düşer; satırı olan başka bir ölçümde adres yoksa 404.
     */
    @Query("SELECT r.id FROM PageSpeedResource r WHERE r.monitorId = :id AND r.checkId = :checkId LIMIT 1")
    List<Long> anyRowOfCheck(@Param("id") Long id, @Param("checkId") Long checkId);

    /**
     * Şelale (2026-11-15): bir ölçümün kaynakları isteğin ATILDIĞI ana göre (start_ms; eski satırda NULL → sonda), ölçümün TEK
     * kopyası, en çok {@code :limit}. İzleme kimliğiyle sınırlı (başka izlemenin checkId'siyle satır sızmaz).
     */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.checkId = :checkId " + ONE_COPY
            + "ORDER BY r.startMs ASC NULLS LAST, r.id ASC LIMIT :limit")
    List<PageSpeedResource> waterfall(@Param("monitorId") Long monitorId, @Param("checkId") Long checkId, @Param("limit") int limit);

    /** Bir eşik-ihlali anının kırılımı (delil). */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.checkId = :checkId ORDER BY r.bytes DESC NULLS LAST LIMIT :limit")
    List<PageSpeedResource> findByCheck(@Param("checkId") Long checkId, @Param("limit") int limit);

    // ── Sunucu tarafı sayfalama + süzgeç (2026-11-14, Sayfa Hızı zenginleştirme madde 6) ─────────────────────────────────
    // 50 satır tavanı kaldırıldı: arayüz sayfalar, CSV seçili ölçümün TÜM satırlarını akıtır. Süzgeçler:
    //  allTypes / types — tür kovası (TYPE_ALL ya da ilgili normalize türler); party 0 tümü, 1 birinci taraf, 2 üçüncü taraf;
    //  failed — yalnız alınamayan (durum kodu yok ya da ≥ 400; ölçüm motorunun "failed" kuralı). Sıra: bayt azalan, kimlik
    //  artan (benzersiz bağ kırıcı — sayfalar arası satır yinelenmez / kaybolmaz).
    String FILTER = "AND (:allTypes = TRUE OR r.type IN :types) "
            + "AND (:party = 0 OR (:party = 1 AND (r.thirdParty = FALSE OR r.thirdParty IS NULL)) OR (:party = 2 AND r.thirdParty = TRUE)) "
            + "AND (:failed = FALSE OR r.statusCode IS NULL OR r.statusCode >= 400) ";

    /** Son ölçümün (LATEST) kırılımı, süzgeçli ve sayfalı. */
    @Query(value = "SELECT r FROM PageSpeedResource r WHERE r.monitorId = :id AND r.keepReason = 'LATEST' " + FILTER
                 + "ORDER BY r.bytes DESC NULLS LAST, r.id ASC",
           countQuery = "SELECT COUNT(r) FROM PageSpeedResource r WHERE r.monitorId = :id AND r.keepReason = 'LATEST' " + FILTER)
    org.springframework.data.domain.Page<PageSpeedResource> latestPage(@Param("id") Long id,
            @Param("allTypes") boolean allTypes, @Param("types") java.util.Collection<String> types,
            @Param("party") int party, @Param("failed") boolean failed, org.springframework.data.domain.Pageable p);

    /** Bir ihlal anının (checkId) kırılımı, süzgeçli ve sayfalı — izleme kimliğiyle birlikte (başka izlemenin satırı dönmez). */
    @Query(value = "SELECT r FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.checkId = :checkId " + ONE_COPY + FILTER
                 + "ORDER BY r.bytes DESC NULLS LAST, r.id ASC",
           countQuery = "SELECT COUNT(r) FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.checkId = :checkId " + ONE_COPY + FILTER)
    org.springframework.data.domain.Page<PageSpeedResource> checkPage(@Param("monitorId") Long id, @Param("checkId") Long checkId,
            @Param("allTypes") boolean allTypes, @Param("types") java.util.Collection<String> types,
            @Param("party") int party, @Param("failed") boolean failed, org.springframework.data.domain.Pageable p);

    /** Süzgeç sayaçları (tek gruplu sorgu): [tür, üçüncü taraf, alınamadı (1/0), adet] — LATEST. */
    @Query("SELECT r.type, r.thirdParty, CASE WHEN r.statusCode IS NULL OR r.statusCode >= 400 THEN 1 ELSE 0 END, COUNT(r) "
         + "FROM PageSpeedResource r WHERE r.monitorId = :id AND r.keepReason = 'LATEST' "
         + "GROUP BY r.type, r.thirdParty, CASE WHEN r.statusCode IS NULL OR r.statusCode >= 400 THEN 1 ELSE 0 END")
    List<Object[]> latestFacets(@Param("id") Long id);

    /** Süzgeç sayaçları — bir ihlal anı. */
    @Query("SELECT r.type, r.thirdParty, CASE WHEN r.statusCode IS NULL OR r.statusCode >= 400 THEN 1 ELSE 0 END, COUNT(r) "
         + "FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.checkId = :checkId " + ONE_COPY
         + "GROUP BY r.type, r.thirdParty, CASE WHEN r.statusCode IS NULL OR r.statusCode >= 400 THEN 1 ELSE 0 END")
    List<Object[]> checkFacets(@Param("monitorId") Long id, @Param("checkId") Long checkId);

    /**
     * İhlal anları listesi (hangi ölçümlerin donmuş kırılımı var) — en yeniden eskiye. Her şelale / kaynak okumasında çağrılır;
     * PostgreSQL'de ertelenmiş {@code idx_psr_monitor_reason_checked (monitor_id, keep_reason, checked_at)} dizininden geriye
     * okunur (2026-11-17: yalnız {@code idx_psr_reason_checked} varken BAŞKA izlemelerin BREACH satırları da taranıp
     * {@code monitor_id} süzgeçle eleniyordu). Kapı: {@code PageSpeedQueryPlanPostgresTest.breachSnapshotsUseMonitorIndex}.
     */
    @Query("SELECT DISTINCT r.checkId, r.checkedAt FROM PageSpeedResource r "
         + "WHERE r.monitorId = :id AND r.keepReason = 'BREACH' ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> breachSnapshots(@Param("id") Long id, @Param("limit") int limit);

    /**
     * SON ölçümün kırılımını sil — her kontrolde yeniden yazılır.
     * {@code @Transactional} ŞART: türetilmiş silme sorgularına Spring Data kendiliğinden transaction
     * SARMAZ ve {@code open-in-view=false} olduğu için çağrı tx'siz düşer
     * ({@code RepositoryWriteTransactionGuardTest} bunun kapısıdır).
     *
     * <p>TOPLU silme (prod kapısı veri katmanı incelemesi, 2026-11-15): türetilmiş silme satırları önce VARLIK olarak yüklüyor,
     * sonra tek tek siliyordu — ölçüm başına satır kadar gidiş-dönüş (sayfa başına kaynak tavanı 2000). Tek JPQL DELETE.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM PageSpeedResource r WHERE r.monitorId = :monitorId AND r.keepReason = :keepReason")
    int deleteByMonitorIdAndKeepReason(@Param("monitorId") Long monitorId, @Param("keepReason") String keepReason);

    /**
     * Son ölçümün kırılımını DEĞİŞTİR: eski LATEST satırlarını sil + yenilerini yaz — TEK transaction.
     *
     * <p>BO4/O1 (bug regresyon 2026-09-27): bu ikili eskiden {@code SchedulerService.writeResourceBreakdown}
     * içindeydi ve orada {@code @Transactional} taşıyordu, ama metot aynı sınıftan ({@code this.}) çağrıldığı
     * için Spring proxy'si hiç devreye girmiyordu: silme kendi transaction'ında commit ediliyor, {@code saveAll}
     * düşerse LATEST kırılımı silinmiş kalıyor, çağıran istisnayı {@code log.warn} ile yutuyordu → "Kaynak
     * Kırılımı" ekranı bir sonraki başarılı kontrole kadar boş. Transaction sınırı artık repository
     * proxy'sinde: hangi sınıftan çağrılırsa çağrılsın atomik. Kapı:
     * {@code PageSpeedResourceRepositoryTest} ({@code NOT_SUPPORTED} ile, üretim koşulu).
     */
    @Transactional
    default void replaceLatest(Long monitorId, List<PageSpeedResource> rows) {
        deleteByMonitorIdAndKeepReason(monitorId, PageSpeedResource.KEEP_LATEST);   // tek toplu DELETE
        insertAll(rows);   // JDBC toplu INSERT (500'lük öbekler) — aynı transaction
    }

    /** İzleme silinince tüm kırılımını temizle — TOPLU silme (kapı incelemesi 2026-11-15): türetilmiş {@code deleteBy…} her satırı varlık olarak yükleyip tek tek siler; tek {@code DELETE}. */
    @Transactional
    @Modifying
    @Query("DELETE FROM PageSpeedResource r WHERE r.monitorId = :id")
    int deleteByMonitorId(@Param("id") Long monitorId);

    /** Retention: YALNIZ donmuş ihlal delilleri yaşa göre temizlenir; LATEST satırları zaten üzerine yazılıyor. */
    @Transactional
    @Modifying
    @Query("DELETE FROM PageSpeedResource r WHERE r.keepReason = 'BREACH' AND r.checkedAt < :before")
    int deleteBreachesOlderThan(@Param("before") String before);
}
