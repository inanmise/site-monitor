package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * TEK SEFERLİK veri yaması (2026-09-27, kullanıcı kararı): DNS/Port standalone izlemelerde "silinmiş" ile
 * "duraklatılmış" ayrıştırılırken ESKİ satırların işaretlenmesi.
 *
 * <p><b>Neden.</b> Bu sürüme dek silme ({@code deletePort}/{@code deleteDns}) ve duraklatma aynı durumu yazıyordu:
 * {@code active=false}. İkisini geriye dönük ayırmak mümkün değil. GÜVENLİ seçim: yükseltme anında pasif olan
 * her standalone satır SİLİNMİŞ sayılır ({@code deleted_at} doldurulur) — silinmiş bir izleme asla geri gelmez;
 * bedeli, yükseltmeden önce duraklatılmış satırların görünmez kalmasıdır (bugünkü davranışın aynısı).
 *
 * <p><b>Neden TEK SEFER — ve neden "kolon yeni mi eklendi" kapısı DEĞİL.</b> Yama her açılışta koşsaydı,
 * yükseltmeden SONRA duraklatılan satırlar bir sonraki yeniden başlatmada silinmiş işaretlenirdi. Kolonun yokluğuna
 * bakmak işe yaramaz: {@code spring.jpa.hibernate.ddl-auto=update} kolonu uygulama açılırken, şema yamalarından
 * ÖNCE ekliyor — kapı hiç açılmaz ve geri doldurma sessizce hiç koşmazdı. Bu yüzden projede genel bir
 * uygulanmış-yama nişanı yoktu; {@code schema_patch_markers} tablosu kuruldu (anahtar başına tek satır):
 * <ol>
 *   <li>nişan varsa → hiçbir şey yapılmaz (ikinci ve sonraki açılışlar);</li>
 *   <li>yoksa → UPDATE + nişan INSERT'i AYNI transaction'da. UPDATE düşerse nişan yazılmaz, sonraki açılış
 *       yeniden dener; iki pod aynı anda açılırsa ikinci INSERT birincil anahtar çakışmasıyla düşer ve
 *       O pod'un UPDATE'i de geri alınır — kalıcı olan tek bir koşudur.</li>
 * </ol>
 * Kapı: {@code StandaloneMonitorDeletionBackfillTest} (ikinci koşu, ilkinden sonra duraklatılan satıra dokunmaz).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneMonitorDeletionBackfill {

    /** Genel, ham DDL nişan tablosu — tek seferlik veri yamaları için (RetentionCoverageTest'te muaf). */
    public static final String MARKERS_DDL = "CREATE TABLE IF NOT EXISTS schema_patch_markers ("
            + "patch_key VARCHAR(120) PRIMARY KEY, applied_at VARCHAR(30) NOT NULL, rows_affected INTEGER)";

    /** Bu yamanın nişan anahtarı — DEĞİŞTİRİLMEZ (değişirse yama yeniden koşar ve sonradan duraklatılanları siler). */
    static final String KEY = "2026-09-27-standalone-port-dns-deleted-at";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    /**
     * Yamayı en çok BİR KEZ uygular. Dönüş: bu çağrıda silinmiş işaretlenen satır sayısı; nişan zaten varsa -1.
     * {@code deleted_at} kolonları önceden var olmalı (ddl-auto ya da {@code SchedulerService} açık ADD COLUMN yaması).
     */
    @Transactional
    public int applyOnce() {
        jdbc.execute(MARKERS_DDL);
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
        if (done != null && done > 0) return -1;
        String now = ISO.format(Instant.now());
        int port = jdbc.update("UPDATE port_monitors SET deleted_at = ? "
                + "WHERE standalone = TRUE AND active = FALSE AND deleted_at IS NULL", now);
        int dns = jdbc.update("UPDATE dns_monitors SET deleted_at = ? "
                + "WHERE standalone = TRUE AND active = FALSE AND deleted_at IS NULL", now);
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, now, port + dns);
        log.info("Tek seferlik yama {}: {} Port + {} DNS pasif standalone izleme SİLİNMİŞ işaretlendi", KEY, port, dns);
        return port + dns;
    }
}
