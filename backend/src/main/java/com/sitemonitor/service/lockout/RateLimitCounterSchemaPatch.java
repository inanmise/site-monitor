package com.sitemonitor.service.lockout;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Ortak oran sınırı tablosu (2026-10-23, çok pod Sürüm 2a — MP-31) — açılışta, İDEMPOTENT. Tablo varlıkta da tanımlı
 * ({@code RateLimitCounter}, ddl-auto kurar); bu yama ddl-auto'nun sessizce atladığı ortamları kapatır
 * ({@code NotificationDurabilitySchemaPatch} deseni, paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz).
 * Her ifade kendi try/catch'inde ({@link SchemaDdlGuard} kilit beklemesi sınırlı); düşerse açılış durmaz. Tablo eksikken
 * {@link RateLimitCounterService} bellek yedeğine düşer (20.165.1 davranışı). Kapı: {@code RateLimitCounterSchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitCounterSchemaPatch {

    /** Taşınabilir (PostgreSQL + H2) tablo tanımı — testler de bunu kurar. */
    public static final String DDL = "CREATE TABLE IF NOT EXISTS " + RateLimitCounterService.TABLE + " ("
            + "limit_key VARCHAR(" + RateLimitCounterService.KEY_MAX + ") NOT NULL PRIMARY KEY, "
            + "window_start BIGINT NOT NULL, "
            + "attempts INTEGER NOT NULL, "
            + "blocked_until BIGINT NOT NULL, "
            + "expires_at BIGINT NOT NULL, "
            + "updated_at BIGINT NOT NULL)";

    /** Küçük tablo — temizlik aralık taraması için. */
    public static final String INDEX = "CREATE INDEX IF NOT EXISTS idx_rlc_expires ON "
            + RateLimitCounterService.TABLE + "(expires_at)";

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Oran sınırı şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda çalıştırılan ifade sayısı (0 = tablo ve indeks zaten var). */
    public int apply() {
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        int ran = 0;
        boolean tablePresent;
        try {
            tablePresent = tableExists();
        } catch (Exception e) {
            tablePresent = false;   // katalog okunamadı → IF NOT EXISTS ifadeleri yine güvenli
        }
        try {
            if (!tablePresent) {
                guard.executeBounded(DDL, false);
                ran++;
            }
        } catch (Exception e) {
            log.warn("Oran sınırı tablosu kurulamadı: {}", e.getMessage());
        }
        try {
            if (!(tablePresent && guard.alreadyInPlace(INDEX))) {
                guard.executeBounded(INDEX, false);
                ran++;
            }
        } catch (Exception e) {
            log.warn("Oran sınırı indeksi kurulamadı: {}", e.getMessage());
        }
        if (ran > 0) log.info("Oran sınırı şema yaması: {} ifade uygulandı", ran);
        return ran;
    }

    boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, RateLimitCounterService.TABLE);
        return n != null && n > 0;
    }
}
