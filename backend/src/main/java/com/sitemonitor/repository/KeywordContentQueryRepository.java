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
     * {@code idx_kwr_monitor_id} / {@code idx_kwr_checked_at} üzerinden.
     */
    @Query("SELECT r.checkedAt, r.ok, r.occurrences, r.bodyBytes, r.contentChanged FROM KeywordResult r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> contentSeriesRaw(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                                    @Param("limit") int limit);

    /** Değişim değerlendirilmiş (content_changed dolu) en yeni kontrol satırı — en çok bir satır. */
    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId = :id AND r.contentChanged IS NOT NULL "
         + "ORDER BY r.checkedAt DESC LIMIT 1")
    List<KeywordResult> latestChangeRow(@Param("id") Long id);

    /** En yeni kontrol satırı — en çok bir satır (değişim panelinin "son kontrolde neden ölçülmedi" notu, kw son inceleme D-7). */
    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC, r.id DESC LIMIT 1")
    List<KeywordResult> latestRow(@Param("id") Long id);
}
