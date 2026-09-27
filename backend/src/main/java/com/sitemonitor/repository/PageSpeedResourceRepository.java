package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PageSpeedResourceRepository extends JpaRepository<PageSpeedResource, Long> {

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
    long countByMonitorIdAndCheckId(Long monitorId, Long checkId);

    /** Bir eşik-ihlali anının kırılımı (delil). */
    @Query("SELECT r FROM PageSpeedResource r WHERE r.checkId = :checkId ORDER BY r.bytes DESC NULLS LAST LIMIT :limit")
    List<PageSpeedResource> findByCheck(@Param("checkId") Long checkId, @Param("limit") int limit);

    /** İhlal anları listesi (hangi ölçümlerin donmuş kırılımı var) — en yeniden eskiye. */
    @Query("SELECT DISTINCT r.checkId, r.checkedAt FROM PageSpeedResource r "
         + "WHERE r.monitorId = :id AND r.keepReason = 'BREACH' ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> breachSnapshots(@Param("id") Long id, @Param("limit") int limit);

    /**
     * SON ölçümün kırılımını sil — her kontrolde yeniden yazılır.
     * {@code @Transactional} ŞART: türetilmiş silme sorgularına Spring Data kendiliğinden transaction
     * SARMAZ ve {@code open-in-view=false} olduğu için çağrı tx'siz düşer
     * ({@code RepositoryWriteTransactionGuardTest} bunun kapısıdır).
     */
    @Transactional
    @Modifying
    int deleteByMonitorIdAndKeepReason(Long monitorId, String keepReason);

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
        deleteByMonitorIdAndKeepReason(monitorId, PageSpeedResource.KEEP_LATEST);
        saveAll(rows);
    }

    /** İzleme silinince tüm kırılımını temizle. */
    @Transactional
    @Modifying
    int deleteByMonitorId(Long monitorId);

    /** Retention: YALNIZ donmuş ihlal delilleri yaşa göre temizlenir; LATEST satırları zaten üzerine yazılıyor. */
    @Transactional
    @Modifying
    @Query("DELETE FROM PageSpeedResource r WHERE r.keepReason = 'BREACH' AND r.checkedAt < :before")
    int deleteBreachesOlderThan(@Param("before") String before);
}
