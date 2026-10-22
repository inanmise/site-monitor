package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Alan adı hatırlatmasının sahiplenme belirteci kolonu (2026-10-23, çok pod R2a · D2, MP-45) — açılışta, İDEMPOTENT
 * ({@code NotificationDurabilitySchemaPatch} deseni; paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz).
 *
 * <p>{@code domain_expiry_reminders.claim_token VARCHAR(40)} NULLABLE, varsayılansız: yeni kod her eklediği satıra yazar, eski
 * sürümler hiç yazmaz. Tekil kısmi indeks {@code ux_der_mon_exp_thr} ({@code DeferredIndexBuilder.CATALOG}) yalnız dolu
 * satırları kapsar — eski kodun yazdığı (olası çift) satırlar kurulumu düşüremez. Kolon varlıkta da tanımlı (ddl-auto ekler);
 * bu yama ddl-auto'nun sessizce atladığı ortamları kapatır. Kolon eksikken ekleme düşer → o tur hatırlatma göndermez, sonraki
 * tur yeniden dener. Kapı: {@code DomainReminderSchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainReminderSchemaPatch {

    static final String TABLE = "domain_expiry_reminders";
    static final String COLUMN = "claim_token";

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Alan adı hatırlatması şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return kolon bu koşuda eklendiyse true (false = güncel ya da tablo yok). */
    public boolean apply() {
        if (!exists("SELECT count(*) FROM information_schema.tables WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                TABLE)) return false;
        if (exists("SELECT count(*) FROM information_schema.columns WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) "
                + "AND lower(column_name)=lower(?)", TABLE, COLUMN)) return false;
        new SchemaDdlGuard(jdbc).executeBounded("ALTER TABLE " + TABLE + " ADD COLUMN " + COLUMN + " VARCHAR(40)", false);
        log.info("Alan adı hatırlatması şema yaması: {}.{} eklendi", TABLE, COLUMN);
        return true;
    }

    boolean columnExists() {
        return exists("SELECT count(*) FROM information_schema.columns WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) "
                + "AND lower(column_name)=lower(?)", TABLE, COLUMN);
    }

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }
}
