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

    /**
     * r177 kolonları (tablolar bu sürümden önce prod'da VAR): ddl-auto boş kolonları zaten ekler; bu liste eksikse (ddl-auto
     * kapalı / yarım kalmış açılış) idempotent ekler. Hepsi boş olabilir (null = eski davranış: {@code mail_columns_mode}
     * null = FIRST_N, {@code unknown_runs} null = 0, {@code oracle_tcps} null = kapalı, {@code last_privileges_json} null =
     * denetlenmedi) — varsayılan / NOT NULL yaması gerekmez. Sıra: [tablo, kolon, tür].
     */
    static final List<String[]> COLUMNS = List.of(
            new String[]{"dbquery_monitors", "mail_columns_mode", "VARCHAR(10)"},
            new String[]{"dbquery_monitors", "unknown_runs", "INTEGER"},
            new String[]{"db_connection_profiles", "oracle_tcps", "BOOLEAN"},
            new String[]{"db_connection_profiles", "last_privileges_json", "TEXT"});

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensureColumns();
        } catch (Exception e) {
            log.warn("Veritabanı sorgu izlemesi kolon yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}",
                    e.getClass().getSimpleName());
        }
        try {
            apply();
        } catch (Exception e) {
            log.warn("Veritabanı sorgu izlemesi ad indeksi yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}",
                    e.getClass().getSimpleName());
        }
    }

    /** @return eklenen kolon sayısı (var olan kolon sorgu denemesiyle anlaşılır — ifade hiç çalışmaz). */
    public int ensureColumns() {
        int added = 0;
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        for (String[] c : COLUMNS) {
            if (columnExists(c[0], c[1])) continue;
            try {
                guard.executeBounded("ALTER TABLE " + c[0] + " ADD COLUMN IF NOT EXISTS " + c[1] + " " + c[2], false);
                added++;
            } catch (Exception e) {
                if (columnExists(c[0], c[1])) continue;   // başka pod aynı anda ekledi
                log.warn("Veritabanı sorgu izlemesi kolonu eklenemedi ({}.{}) — sonraki açılışta yeniden denenecek: {}",
                        c[0], c[1], e.getClass().getSimpleName());
            }
        }
        if (added > 0) log.info("Veritabanı sorgu izlemesi: {} kolon eklendi (r177)", added);
        return added;
    }

    private boolean columnExists(String table, String column) {
        try {
            jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (Exception e) {
            return false;
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
