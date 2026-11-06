package com.sitemonitor.repository;

import com.sitemonitor.model.KeywordResult;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Anahtar kelime İÇERİK uçlarının salt okuma sorguları (kw sözleşmesi §10.3) — {@code keyword_results} üzerinde, ortak
 * {@link KeywordResultRepository}'den ayrı (içerik serisi / son değişim satırı yalnız {@code KeywordContentController}'ın).
 * Sabit sorgu sayısı, ham satır tavanı çağıranda.
 */
public interface KeywordContentQueryRepository extends Repository<KeywordResult, Long> {

    /**
     * İçerik serisi ham verisi: [checked_at, ok, occurrences, body_bytes, content_changed] — aralık + tavan (en yeni :limit),
     * bileşik {@code idx_kwr_monitor_checked} geriye taranır ({@code MonitorIndexSort}: IN (:id, :id) biçimi, performans 2026-11-06).
     */
    @Query("SELECT r.checkedAt, r.ok, r.occurrences, r.bodyBytes, r.contentChanged FROM KeywordResult r "
         + "WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<Object[]> contentSeriesRaw(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                                    @Param("limit") int limit);

    /**
     * Değişim değerlendirilmiş (content_changed dolu) en yeni kontrol satırı — en çok bir satır. PostgreSQL'de kısmi
     * {@code idx_kwr_change_checked} (yalnız değişim ölçülmüş satırlar) sırayı da verir: değişimi hiç ölçülmemiş ya da uzun
     * süredir ölçülmeyen izlemede tüm sonuç dizini taranmaz (yerel 496 ms, performans 2026-11-06).
     */
    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId IN (:id, :id) AND r.contentChanged IS NOT NULL "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT 1")
    List<KeywordResult> latestChangeRow(@Param("id") Long id);

    /** En yeni kontrol satırı — en çok bir satır (değişim panelinin "son kontrolde neden ölçülmedi" notu, kw son inceleme D-7). */
    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId IN (:id, :id) "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC, r.id DESC LIMIT 1")
    List<KeywordResult> latestRow(@Param("id") Long id);
}
