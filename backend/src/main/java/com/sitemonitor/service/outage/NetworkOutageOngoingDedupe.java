package com.sitemonitor.service.outage;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kaynak başına TEK açık ağ kesintisi olayı (2026-10-21, çok pod — MP-01, MP-16) — tek seferlik veri düzeltmesi + kısmi tekil
 * indeks.
 *
 * <p><b>Ne yapar.</b> (1) Her kaynak için ({@code COALESCE(source,'CERT')} — {@code source} NULL olan eski kayıtlar
 * sertifikadır) EN YENİ {@code ONGOING} satır kalır, eskiler {@code RESOLVED} olur (çözüm postası gitmez:
 * {@code resolved_mail_status = SKIPPED_DUPLICATE}). Çiftler çok pod öncesinin bellek içi durumundan doğdu (iki pod iki
 * ONGOING satır; yeniden başlatmada bastırma bayrağı sıfırlanınca ikinci satır). (2) PostgreSQL'de kısmi ifade indeksi
 * {@value #INDEX}: aynı kaynağa ikinci açık satır artık eklenemez — {@link CertNetworkOutageService} "yoksa ekle"
 * yarışının ikinci ekleyicisi tipli {@code DuplicateKeyException} alır ve açılış postası göndermez. H2'de (testler) kısmi /
 * ifade indeksi yok: uygulama düzeyindeki "yoksa ekle" + {@code cert-check} kilidi korur.
 *
 * <p><b>Kurallar</b> (CLAUDE.md "one-time data fixes"): açılış yolunda değil — arka planda, kendi kilidi
 * ({@value #LOCK}) altında; nişan ({@value #KEY}) yalnız TAM başarıdan sonra yazılır ve kilidin İÇİNDE yeniden okunur;
 * veritabanı türü okunamazsa "PostgreSQL değil → bitti" denmez (sonraki tur yeniden dener). İndeks kurulamazsa (ör. eski
 * pod arada yeni çift yazdı → 23505, kilit 20 sn'de alınamadı) nişan yazılmaz, bir saat sonra temizlik + indeks yeniden
 * denenir; pod başına en çok {@value #MAX_ATTEMPTS} başarısız deneme. Nişan varken indeks yoksa (ör. döküm geri yüklendi)
 * iş yeniden koşar. Adlar yayından sonra DEĞİŞTİRİLMEZ. Kapı: {@code NetworkOutageOngoingDedupeTest} (H2),
 * {@code NetworkOutageIndexPostgresTest}.
 */
@Slf4j
@Service
public class NetworkOutageOngoingDedupe {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-21-noe-one-ongoing-v1";
    static final String LOCK = "noe-ongoing-dedupe";
    static final String INDEX = "ux_noe_one_ongoing";
    static final String INDEX_DDL = "CREATE UNIQUE INDEX IF NOT EXISTS " + INDEX
            + " ON network_outage_events((COALESCE(source, 'CERT'))) WHERE status = 'ONGOING'";
    static final int MAX_ATTEMPTS = 24;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public NetworkOutageOngoingDedupe(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.network-outage.dedupe-initial-delay-ms:150000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::runLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Ağ kesintisi tekil açık olay düzeltmesi {} denemede tamamlanamadı — bu pod denemeyi bırakıyor "
                        + "(nişan yazılmadı)", n, e);
            } else {
                log.warn("Ağ kesintisi tekil açık olay düzeltmesi başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /**
     * Bitti mi: nişan var VE (PostgreSQL değil ya da indeks var). Okunamazsa {@code true} (bu tur beklenir — "bitmedi" deyip
     * okunamayan veritabanında iş koşturulmaz).
     */
    boolean done() {
        try {
            if (!markerPresent()) return false;
            Boolean pg = postgresOrNull();
            if (pg == null) return true;
            return !pg || indexExists();
        } catch (RuntimeException e) {
            log.debug("Ağ kesintisi tekil olay nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: temizle → (PG) indeks → nişan. Dönüş: kapatılan çift satır sayısı. Hata dışarı (nişan yazılmaz). */
    Integer runLocked() {
        if (done()) return 0;
        Boolean pg = postgresOrNull();
        if (pg == null) throw new IllegalStateException("veritabanı türü okunamadı — sonraki tur yeniden denenecek");
        int resolved = dedupe();
        if (pg) {
            SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
            if (!guard.alreadyInPlace(INDEX_DDL)) guard.executeBounded(INDEX_DDL, false);
            if (!indexExists()) throw new IllegalStateException(INDEX + " kurulamadı");
        }
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", KEY);   // indeks kayıpken yeniden koşu
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), resolved);
        log.info("Ağ kesintisi olayları: kaynak başına tek açık olay — {} çift açık olay kapatıldı{}", resolved,
                pg ? ", " + INDEX + " hazır" : " (H2: indeks yok)");
        return resolved;
    }

    /** Kaynak başına en yeni ONGOING satırı bırakır, eskileri koşullu kapatır. */
    int dedupe() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, source, detected_at FROM network_outage_events WHERE status = 'ONGOING' ORDER BY id DESC");
        Set<String> kept = new HashSet<>();
        long nowMs = System.currentTimeMillis();
        String nowIso = ISO.format(Instant.ofEpochMilli(nowMs));
        int resolved = 0;
        for (Map<String, Object> r : rows) {
            Object src = r.get("source");
            String source = src == null ? CertNetworkOutageService.SOURCE : src.toString();
            if (kept.add(source)) continue;   // kaynağın en yeni açık satırı kalır
            Object id = r.get("id");
            if (!(id instanceof Number n)) continue;
            Object det = r.get("detected_at");
            Long duration = CertNetworkOutageService.durationMs(det == null ? null : det.toString(), nowMs);
            int changed = jdbc.update("UPDATE network_outage_events SET status = 'RESOLVED', resolved_at = ?, duration_ms = ?, "
                    + "resolved_mail_status = ? WHERE id = ? AND status = 'ONGOING'",
                    nowIso, duration, CertNetworkOutageService.MAIL_SKIPPED_DUPLICATE, n.longValue());
            if (changed == 1) {
                resolved++;
                log.info("Ağ kesintisi çift açık olayı kapatıldı: #{} (kaynak {}, en yeni açık olay kaldı)", n, source);
            }
        }
        return resolved;
    }

    boolean markerPresent() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
        return n != null && n > 0;
    }

    boolean indexExists() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, INDEX);
        return Boolean.TRUE.equals(exists);
    }

    /** PostgreSQL mi: true / false; okunamazsa null (asla "PostgreSQL değil" varsayılmaz). */
    Boolean postgresOrNull() {
        try {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            return product == null ? null : product.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (RuntimeException e) {
            log.debug("Veritabanı türü okunamadı: {}", e.getMessage());
            return null;
        }
    }
}
