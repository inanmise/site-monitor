package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedResource;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sayfa Hızı kaynak satırlarının TOPLU yazımı (prod kapısı veri katmanı incelemesi, 2026-11-15). Her sayfa hızı ölçümü
 * son kırılımı yeniden yazar; satır sayısı sayfa başına kaynak tavanına (varsayılan 2000) ve genişletilmiş ölçümle daha da
 * büyüyebilir. {@code saveAll} IDENTITY kimlikli varlıkları tek tek INSERT ediyordu (JDBC toplu gönderim yok) — ölçüm başına
 * satır kadar gidiş-dönüş. Bu parça satırları {@code JdbcTemplate.batchUpdate} ile 500'lük öbeklerde yazar; çağıranın
 * transaction'ına katılır ({@link PageSpeedResourceRepository#replaceLatest} silme + yazmayı TEK transaction'da yapar).
 */
public interface PageSpeedResourceBatchWriter {

    /** Satırları toplu INSERT eder (kimlik veritabanından); boş liste hiçbir şey yazmaz. Döner: yazılan satır sayısı. */
    @Transactional
    int insertAll(List<PageSpeedResource> rows);
}
