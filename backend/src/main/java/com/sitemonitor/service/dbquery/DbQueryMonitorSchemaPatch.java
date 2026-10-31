package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.schema.DeferredIndexBuilder;
import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Veritabanı sorgu izlemesi adının TÜM takımlarda tekilliği (inceleme 2026-10-31, K1) — PostgreSQL tekil ifade indeksi
 * {@value #NAME_INDEX} ({@code LOWER(name)}). Alarm anahtarı izleme ADIDIR: denetleyicinin "önce bak, sonra kaydet" denetimi
 * iki pod'da aynı anda geçebilirdi; indeks ikinci kaydı keser, denetleyici ihlali aynı 409'a ({@code DUPLICATE_RECORD},
 * alan {@code name}) çevirir.
 *
 * <p>Açılışta ({@link ApplicationReadyEvent}; tablo ddl-auto ile kurulmuş olur), İDEMPOTENT: indeks katalogda varsa ifade hiç
 * çalışmaz ({@link SchemaDdlGuard#alreadyInPlace}), yoksa kilit beklemesi {@value SchemaDdlGuard#LOCK_TIMEOUT_SECONDS} sn ile
 * sınırlı kurulur (tablo küçüktür — izleme tanımları; CONCURRENTLY gerekmez). Var olan aynı adlı kayıtlar (özellik yayına
 * çıkmadan önceki geliştirme / deneme veritabanları) indeksi kurdurmaz: UYARI, adlar günlükte (en çok 10), bir sonraki açılışta
 * yeniden denenir — uygulama denetimi bu arada yeni çift adı yine reddeder. H2'de (testler) ifade indeksi yok: aynı pod'daki
 * yarışı denetleyicinin ad kilidi keser. Tür okunamazsa bu açılışta hiçbir şey yapılmaz (asla "PostgreSQL değil → bitti").
 */
@Slf4j
@Service
public class DbQueryMonitorSchemaPatch {

    /** İndeks adı — KALICI (denetleyici ihlal tanıması da bunu arar). */
    public static final String NAME_INDEX = "ux_dbqm_name_lower";

    /** Tek dize: {@code SchemaDdlGuardPostgresTest} kaynak taramasıyla "açılıştan sonra zaten yerinde" kapısına girer. */
    public static final String DDL = "CREATE UNIQUE INDEX IF NOT EXISTS ux_dbqm_name_lower ON dbquery_monitors ((LOWER(name)))";

    static final String SQL_DUPLICATES = "SELECT MIN(name) FROM dbquery_monitors GROUP BY LOWER(name) HAVING COUNT(*) > 1 "
            + "ORDER BY MIN(name) LIMIT 10";

    private final JdbcTemplate jdbc;

    public DbQueryMonitorSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Veritabanı sorgu izlemesi ad indeksi yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}",
                    e.getClass().getSimpleName());
        }
    }

    /** @return {@code true} = indeks bu koşuda kuruldu; {@code false} = zaten vardı, PostgreSQL değil, tür bilinmiyor ya da kurulamadı. */
    public boolean apply() {
        Boolean pg = DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (pg == null) {
            log.info("Veritabanı sorgu izlemesi ad indeksi: veritabanı türü okunamadı — bu açılışta atlandı, sonraki açılışta yeniden denenecek");
            return false;
        }
        if (!pg) return false;
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        if (guard.alreadyInPlace(DDL)) return false;
        try {
            guard.executeBounded(DDL, false);
            log.info("Veritabanı sorgu izlemesi ad indeksi kuruldu ({}) — izleme adı tüm takımlarda tekil", NAME_INDEX);
            return true;
        } catch (Exception e) {
            if (SchemaDdlGuard.isLockTimeout(e)) {
                log.warn("Veritabanı sorgu izlemesi ad indeksi kurulamadı: tablo kilidi {} sn içinde alınamadı — sonraki açılışta "
                        + "yeniden denenecek", SchemaDdlGuard.LOCK_TIMEOUT_SECONDS);
                return false;
            }
            List<String> dup = duplicates();
            if (!dup.isEmpty()) {
                log.warn("Veritabanı sorgu izlemesi ad indeksi kurulamadı: aynı adlı izlemeler var ({}) — biri yeniden adlandırılınca "
                        + "sonraki açılışta kurulur; uygulama yeni çift adı bu arada da reddeder", String.join(", ", dup));
            } else {
                log.warn("Veritabanı sorgu izlemesi ad indeksi kurulamadı (sonraki açılışta yeniden denenecek): {}",
                        e.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** Var olan aynı adlı izlemeler (en çok 10, ilk adlarıyla); okunamazsa boş. */
    List<String> duplicates() {
        try {
            return jdbc.queryForList(SQL_DUPLICATES, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
