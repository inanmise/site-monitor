package com.sitemonitor.service.outage;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.InactiveRecipientGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Sertifika taramasının TOPLU AĞ KESİNTİSİ durumu — veritabanı satırı durum makinesidir (2026-10-21, çok pod — MP-01).
 *
 * <p><b>Neden.</b> Eskiden kesinti durumu ({@code networkOutageActive}) ve "yönetici postası gönderilemedi, yeniden dene"
 * bayrakları sertifika taramasını koşan pod'un BELLEĞİNDEYDİ. Saatlik tarama küme turunu hangi pod kazanırsa onda, 5 dk'lık
 * bayat tarama HER pod'da koşar: iki pod aynı kesinti için iki ONGOING satırı + iki "ağ kesintisi" postası üretiyor, çözüm
 * postası kayboluyor ya da iki kez gidiyor, ONGOING satır saatlerce takılı kalıp sertifika eskalasyon adımlarını bastırıyor
 * ({@code EscalationStepService}), pano şeridi / Sistem Sağlığı / envanter ekleme kapısı pod'a göre farklı cevap veriyordu.
 *
 * <p><b>Kural.</b>
 * <ul>
 *   <li><b>Tespit</b> = "açık CERT satırı yoksa ekle" ({@code INSERT … SELECT … WHERE NOT EXISTS}); PostgreSQL'de ayrıca
 *       kısmi tekil indeks {@code ux_noe_one_ongoing} ({@link NetworkOutageOngoingDedupe}) yarışın ikinci ekleyicisini
 *       düşürür. Açılış postasını YALNIZ ekleyen pod gönderir; ekleyemeyen pod yalnız açık satırın son tur sayılarını
 *       tazeler.</li>
 *   <li><b>Çözüm</b> = açık satır başına koşullu {@code UPDATE … WHERE id = ? AND status = 'ONGOING'}; çözüm postasını
 *       yalnız satırı GERÇEKTEN kapatan pod gönderir (1 satır). Toplam kontrolü {@code min-errors}'tan küçük tur (ör. 1–2
 *       alan adlık bayat tarama) kesintiyi KAPATAMAZ — açık kesinti sürerken o turun alarmları da işlenmez.</li>
 *   <li><b>Posta durumu satırda</b> ({@code alert_mail_*}, {@code resolved_mail_*}): koşullu sahiplenme (PENDING / RETRY ya
 *       da 10 dk'dan eski SENDING) → gönder → sonuç. Başarısız gönderim (SMTP de kesintiden etkilenmiş olabilir) herhangi
 *       bir pod'un sonraki turunda yeniden denenir; {@value #MAX_ATTEMPTS} denemede FAILED. Açılış postası gitmeden kesinti
 *       çözülürse açılış SUPERSEDED olur (çözüm postası tüm zaman çizelgesini taşır — eski kural). NULL = sütunlardan önceki
 *       satır = gönderilmiş sayılır (eski kesintiler yeniden postalanmaz).</li>
 *   <li><b>Okuyucular</b> (pano şeridi {@code /api/system/network-status}, Sistem Sağlığı, envanter ekleme anındaki alarm
 *       kapısı) veritabanını pod başına {@value #MEMO_MS} ms'lik bir ezberle okur — bütün pod'lar aynı cevabı verir; bu pod
 *       yazınca ezber hemen düşer.</li>
 * </ul>
 * Tek pod'da davranış öncekiyle aynıdır (bir açılış + bir çözüm postası); fark: durum yeniden başlatmada kaybolmaz ve
 * 421 kuyruğa alınan posta ({@code QUEUED_RETRY…}) e-posta servisinin sorumluluğundadır — bir saat sonra yeniden
 * GÖNDERİLMEZ (kuyruk teslim ettiyse ikinci posta olurdu).
 */
@Service
public class CertNetworkOutageService {

    private static final Logger log = LoggerFactory.getLogger(CertNetworkOutageService.class);

    /** Sertifika taramasının olay kaynağı ({@code source} NULL = 2026-08 öncesi kayıt, o da sertifikadır). */
    public static final String SOURCE = "CERT";
    static final String ONGOING = "ONGOING";
    static final String RESOLVED = "RESOLVED";

    static final String MAIL_PENDING = "PENDING";
    static final String MAIL_RETRY = "RETRY";
    static final String MAIL_SENDING = "SENDING";
    static final String MAIL_SUPERSEDED = "SUPERSEDED";
    static final String MAIL_FAILED = "FAILED";
    /** Yönetici adresi ayarlı değil — kardeş tüketicilerle aynı: adres yoksa posta yok (kesin sonuç). */
    static final String MAIL_NO_ADDRESS = "SKIPPED: yönetici adresi yok";
    /** Aynı kaynağın eski çift açık satırı (çok pod öncesi) — çözüm postası yalnız en yeni satır için gider. */
    static final String MAIL_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";

    /** Bir postanın en çok deneme sayısı — sonra FAILED (her pod'un her turunda sonsuza dek denenmesin). */
    static final int MAX_ATTEMPTS = 24;
    /** Sahiplenen pod öldüyse SENDING bu süreden sonra başka pod'ca yeniden sahiplenilir. */
    static final long CLAIM_STALE_MS = 10L * 60_000;
    /** Okuyucu ezberinin ömrü (ms). */
    static final long MEMO_MS = 10_000L;
    /** Bekleyen çözüm postası taranırken bakılan en yeni satır sayısı (sınırlı tarama). */
    static final int RETRY_SCAN_LIMIT = 5;
    private static final int MAX_STATUS_CHARS = 255;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** CERT kapsamı — {@code source IS NULL} şart (2026-08 öncesi kayıtlar). İzleme sweep'lerinin satırları ayrı. */
    static final String CERT_SCOPE = "(source IS NULL OR source = 'CERT')";

    static final String SQL_INSERT_IF_ABSENT =
            "INSERT INTO network_outage_events (detected_at, network_errors, total_checks, error_rate, threshold, status, "
          + "source, alert_mail_status, alert_mail_attempts) "
          + "SELECT CAST(? AS VARCHAR(255)), CAST(? AS INTEGER), CAST(? AS INTEGER), CAST(? AS DOUBLE PRECISION), "
          + "CAST(? AS DOUBLE PRECISION), 'ONGOING', 'CERT', 'PENDING', 0 "
          + "WHERE NOT EXISTS (SELECT 1 FROM network_outage_events WHERE status = 'ONGOING' AND " + CERT_SCOPE + ")";

    static final String SQL_UPDATE_STATS =
            "UPDATE network_outage_events SET network_errors = ?, total_checks = ?, error_rate = ? "
          + "WHERE status = 'ONGOING' AND " + CERT_SCOPE;

    static final String SQL_RESOLVE =
            "UPDATE network_outage_events SET status = 'RESOLVED', resolved_at = ?, duration_ms = ?, "
          + "resolved_mail_status = ?, resolved_mail_attempts = 0, "
          + "alert_mail_status = CASE WHEN alert_mail_status IN ('PENDING', 'RETRY') THEN 'SUPERSEDED' ELSE alert_mail_status END "
          + "WHERE id = ? AND status = 'ONGOING'";

    static final String SQL_CLAIM_ALERT =
            "UPDATE network_outage_events SET alert_mail_status = 'SENDING', alert_mail_claimed_ms = ? "
          + "WHERE id = ? AND status = 'ONGOING' AND (alert_mail_status IN ('PENDING', 'RETRY') "
          + "OR (alert_mail_status = 'SENDING' AND (alert_mail_claimed_ms IS NULL OR alert_mail_claimed_ms < ?)))";

    static final String SQL_CLAIM_RESOLVED =
            "UPDATE network_outage_events SET resolved_mail_status = 'SENDING', resolved_mail_claimed_ms = ? "
          + "WHERE id = ? AND status = 'RESOLVED' AND (resolved_mail_status IN ('PENDING', 'RETRY') "
          + "OR (resolved_mail_status = 'SENDING' AND (resolved_mail_claimed_ms IS NULL OR resolved_mail_claimed_ms < ?)))";

    private final JdbcTemplate jdbc;
    private final EmailNotificationService email;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.system-admin.email:}")
    private String systemAdminEmail = "";

    /** Saat (ms) — test paylaşılan saati verir. */
    LongSupplier clock = System::currentTimeMillis;

    /** Ezber: okunan durum + okuma anı. {@code null} = geçersiz (bu pod yazdı). */
    private record Memo(Snapshot snapshot, long at) {}

    private final java.util.concurrent.atomic.AtomicReference<Memo> memo = new java.util.concurrent.atomic.AtomicReference<>();
    /** Yazma kuşağı — okuma sürerken bu pod yazdıysa (invalidate) eski okuma ezberlenmez. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    /** Okuma düştüğünde gösterilen son bilinen durum (ezberden bağımsız). */
    private volatile Snapshot lastKnown;

    @Autowired
    public CertNetworkOutageService(JdbcTemplate jdbc, EmailNotificationService email, AppSettingsService appSettings) {
        this.jdbc = jdbc;
        this.email = email;
        this.appSettings = appSettings;
    }

    /** Kurulum dışı (bean'siz) kullanım: yönetici adresinin yapılandırma varsayılanı. */
    public CertNetworkOutageService withSystemAdminEmailDefault(String fallback) {
        this.systemAdminEmail = fallback == null ? "" : fallback;
        return this;
    }

    /**
     * Okuyucuların gördüğü durum. {@code detectedAt} / {@code resolvedAt} / son tur sayıları kaynağın EN YENİ satırından
     * (açık satır varsa o); kesinti yoksa ve hiç kayıt yoksa {@link #NONE}.
     */
    public record Snapshot(boolean active, Long eventId, String detectedAt, String resolvedAt,
                           int lastNetworkErrors, int lastTotal, double lastErrorRate,
                           boolean pendingAlertEmail, boolean pendingResolvedEmail) {
    }

    public static final Snapshot NONE = new Snapshot(false, null, null, null, 0, 0, 0.0, false, false);

    // ── Okuyucular (ezberli) ──────────────────────────────────────────────────────────────────────────────────────

    /** Pano / Sistem Sağlığı / envanter ekleme kapısı: ≤ {@value #MEMO_MS} ms bayat, bütün pod'larda aynı. */
    public Snapshot snapshot() {
        long now = clock.getAsLong();
        Memo m = memo.get();
        if (m != null && now >= m.at() && now - m.at() < MEMO_MS) return m.snapshot();
        long gen = generation.get();
        try {
            Snapshot fresh = readSnapshot();
            lastKnown = fresh;
            if (generation.get() == gen) memo.set(new Memo(fresh, now));
            return fresh;
        } catch (RuntimeException e) {
            // Okunamadı: son bilinen durum (yoksa "kesinti yok") — ezberlenmez, sonraki çağrı yeniden dener.
            log.debug("Ağ kesintisi durumu okunamadı: {}", e.getMessage());
            Snapshot known = lastKnown;
            return known != null ? known : NONE;
        }
    }

    public boolean isActive() {
        return snapshot().active();
    }

    /** Ezberi düşürür (bu pod yazdı). */
    public void invalidate() {
        generation.incrementAndGet();
        memo.set(null);
    }

    private Snapshot readSnapshot() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, detected_at, resolved_at, status, network_errors, total_checks, error_rate, "
              + "alert_mail_status, resolved_mail_status FROM network_outage_events WHERE " + CERT_SCOPE
              + " ORDER BY CASE WHEN status = 'ONGOING' THEN 0 ELSE 1 END, id DESC LIMIT 1");
        if (rows.isEmpty()) return NONE;
        Map<String, Object> r = rows.get(0);
        boolean active = ONGOING.equals(str(r.get("status")));
        String alertStatus = str(r.get("alert_mail_status"));
        String resolvedStatus = str(r.get("resolved_mail_status"));
        return new Snapshot(active, num(r.get("id")), str(r.get("detected_at")),
                active ? null : str(r.get("resolved_at")),
                intOf(r.get("network_errors")), intOf(r.get("total_checks")), dbl(r.get("error_rate")),
                active && pending(alertStatus), !active && pending(resolvedStatus));
    }

    static boolean pending(String status) {
        return MAIL_PENDING.equals(status) || MAIL_RETRY.equals(status) || MAIL_SENDING.equals(status);
    }

    // ── Tur kararı ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sertifika taramasının bir turunun sonucu → kesinti durumu (veritabanında) + bu turun alarmları işlenmeli mi.
     *
     * @param networkErrors ağ-sınıfı (NETWORK / DNS) hata sayısı
     * @param total         turdaki kontrol sayısı
     * @param rate          ağ-sınıfı hata oranı
     * @param suspected     tur toplu kesinti şüphesi mi ({@code networkErrors ≥ minErrors ∧ rate ≥ eşik})
     * @param minErrors     {@code site.monitor.network.min-errors} — bundan küçük tur kesintiyi kapatamaz
     * @param threshold     oran eşiği (açılış postasına ve satıra yazılır)
     * @return {@code true} = alarmlar işlenir (şüphe yok VE açık kesinti yok); {@code false} = bu turun yeni alarmları
     *         bastırılır (çağıran yalnız doğrulanmış sağlıklı sonuçların açık alarmlarını sessizce kapatır)
     */
    public boolean onRun(long networkErrors, int total, double rate, boolean suspected, int minErrors, double threshold) {
        long nowMs = clock.getAsLong();
        String nowIso = ISO.format(Instant.ofEpochMilli(nowMs));
        try {
            if (suspected) {
                java.util.Set<Long> tried = new java.util.HashSet<>();
                Long id = insertOngoing(nowIso, (int) networkErrors, total, rate, threshold);
                if (id != null) {
                    invalidate();
                    log.warn("⚠ Suspected network outage detected: {}/{} domains failed with network-class errors "
                            + "(rate={}, threshold={}) — alarm processing SKIPPED for this run (olay #{})",
                            networkErrors, total, String.format("%.2f", rate), threshold, id);
                    sendAlert(id);
                    tried.add(id);
                } else {
                    int n = jdbc.update(SQL_UPDATE_STATS, (int) networkErrors, total, rate);
                    if (n > 0) invalidate();
                    log.warn("⚠ Suspected network outage still ongoing: {}/{} domains failed with network-class errors "
                            + "(rate={}) — alarm processing remains SKIPPED", networkErrors, total, String.format("%.2f", rate));
                }
                retryPendingMails(tried);
                return false;
            }
            java.util.Set<Long> tried = new java.util.HashSet<>();
            if (total >= Math.max(0, minErrors)) {
                for (Long id : resolveOngoing(nowMs, nowIso)) {
                    sendResolved(id);
                    tried.add(id);
                }
            } else if (ongoingExists()) {
                log.info("Ağ kesintisi sürüyor — bu tur küçük ({} kontrol < min-errors {}), kesintiyi kapatamaz; "
                        + "alarm işleme bekletiliyor", total, minErrors);
            }
            retryPendingMails(tried);
            return !ongoingExists();
        } catch (RuntimeException e) {
            // Durum yazılamadı/okunamadı (veritabanı sorunu): güvenli taraf — şüpheli turun alarmları yine bastırılır,
            // sağlıklı tur bugünkü gibi işlenir. Posta gönderilmez (çift posta riski yerine).
            log.warn("Ağ kesintisi durumu güncellenemedi (tur kararı yalnız bu turun sonucundan): {}", e.toString());
            return !suspected;
        }
    }

    // ── Satır işlemleri ──────────────────────────────────────────────────────────────────────────────────────────

    /** Açık CERT satırı yoksa ekler; eklenen satırın kimliği, eklenmediyse (açık satır var / yarışı kaybetti) null. */
    Long insertOngoing(String detectedAt, int networkErrors, int total, double rate, double threshold) {
        KeyHolder keys = new GeneratedKeyHolder();
        int n;
        try {
            n = jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(SQL_INSERT_IF_ABSENT, new String[]{"id"});
                ps.setString(1, detectedAt);
                ps.setInt(2, networkErrors);
                ps.setInt(3, total);
                ps.setDouble(4, rate);
                ps.setDouble(5, threshold);
                return ps;
            }, keys);
        } catch (DuplicateKeyException race) {
            // ux_noe_one_ongoing: aynı anda başka pod ekledi — açılış postası onun
            log.info("Ağ kesintisi kaydını başka pod açtı (tekil indeks) — bu pod açılış postası göndermez");
            return null;
        }
        if (n != 1) return null;
        Number key = null;
        try {
            key = keys.getKey();
        } catch (RuntimeException ignored) {
            // bazı sürücüler birden çok kolon döner — aşağıda satırdan okunur
        }
        if (key != null) return key.longValue();
        List<Long> ids = jdbc.queryForList("SELECT id FROM network_outage_events WHERE status = 'ONGOING' AND "
                + CERT_SCOPE + " ORDER BY id DESC", Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Açık CERT satırlarını koşullu kapatır; YALNIZ bu pod'un kapattığı satırlar döner (çözüm postası onlar için). Birden çok
     * açık satır (çok pod öncesinden kalan çift) varsa en yeni satır çözüm postasını alır, eskiler {@value #MAIL_SKIPPED_DUPLICATE}.
     */
    List<Long> resolveOngoing(long nowMs, String nowIso) {
        List<Map<String, Object>> open = jdbc.queryForList("SELECT id, detected_at FROM network_outage_events "
                + "WHERE status = 'ONGOING' AND " + CERT_SCOPE + " ORDER BY id DESC");
        List<Long> mine = new ArrayList<>();
        boolean first = true;
        for (Map<String, Object> r : open) {
            Long id = num(r.get("id"));
            if (id == null) continue;
            Long duration = durationMs(str(r.get("detected_at")), nowMs);
            String mailStatus = first ? MAIL_PENDING : MAIL_SKIPPED_DUPLICATE;
            first = false;
            int n = jdbc.update(SQL_RESOLVE, nowIso, duration, mailStatus, id);
            if (n == 1) {
                invalidate();
                log.info("✓ Network outage cleared — alarm processing resumed (olay #{})", id);
                if (MAIL_PENDING.equals(mailStatus)) mine.add(id);
            }
        }
        return mine;
    }

    boolean ongoingExists() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING' AND "
                + CERT_SCOPE, Integer.class);
        return n != null && n > 0;
    }

    /**
     * Bekleyen açılış / çözüm postalarını (herhangi bir pod'un yazdığı) sahiplenip yeniden dener — sınırlı tarama. Bu turda
     * zaten denenen satırlar ({@code tried}) atlanır: SMTP kesintisinde aynı tur içinde ardışık ikinci deneme olmasın.
     */
    void retryPendingMails(java.util.Set<Long> tried) {
        long stale = clock.getAsLong() - CLAIM_STALE_MS;
        try {
            for (Long id : jdbc.queryForList("SELECT id FROM network_outage_events WHERE status = 'ONGOING' AND " + CERT_SCOPE
                    + " AND (alert_mail_status IN ('PENDING', 'RETRY') OR (alert_mail_status = 'SENDING' AND "
                    + "(alert_mail_claimed_ms IS NULL OR alert_mail_claimed_ms < ?))) ORDER BY id DESC LIMIT "
                    + RETRY_SCAN_LIMIT, Long.class, stale)) {
                if (!tried.contains(id)) sendAlert(id);
            }
            for (Long id : jdbc.queryForList("SELECT id FROM network_outage_events WHERE status = 'RESOLVED' AND " + CERT_SCOPE
                    + " AND (resolved_mail_status IN ('PENDING', 'RETRY') OR (resolved_mail_status = 'SENDING' AND "
                    + "(resolved_mail_claimed_ms IS NULL OR resolved_mail_claimed_ms < ?))) ORDER BY id DESC LIMIT "
                    + RETRY_SCAN_LIMIT, Long.class, stale)) {
                if (!tried.contains(id)) sendResolved(id);
            }
        } catch (RuntimeException e) {
            log.warn("Bekleyen ağ kesintisi postaları taranamadı (sonraki tur yeniden dener): {}", e.getMessage());
        }
    }

    // ── Postalar ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Açılış postası: sahiplen → gönder → sonuç. Sahiplenemezse (başka pod / zaten gitti / kesinti kapandı) hiçbir şey. */
    void sendAlert(long id) {
        long claimMs = clock.getAsLong();
        if (jdbc.update(SQL_CLAIM_ALERT, claimMs, id, claimMs - CLAIM_STALE_MS) != 1) return;
        invalidate();
        String status;
        String adminEmail = adminEmail();
        if (adminEmail == null || adminEmail.isBlank()) {
            status = MAIL_NO_ADDRESS;
            log.info("Ağ kesintisi bildirimi atlandı — system-admin.email boş");
        } else {
            Map<String, Object> r = row(id);
            try {
                status = email.sendSystemAdminNetworkAlert(adminEmail, str(r.get("detected_at")),
                        intOf(r.get("network_errors")), intOf(r.get("total_checks")), dbl(r.get("error_rate")),
                        r.get("threshold") instanceof Number t ? t.doubleValue() : thresholdSetting());
            } catch (Exception e) {
                status = "FAILED: " + e.getMessage();
            }
        }
        finish("alert_mail", id, claimMs, status, adminEmail);
    }

    /** Çözüm postası — yalnız satırı kapatan pod'un sahiplendiği (ya da yeniden denenen) satır için. */
    void sendResolved(long id) {
        long claimMs = clock.getAsLong();
        if (jdbc.update(SQL_CLAIM_RESOLVED, claimMs, id, claimMs - CLAIM_STALE_MS) != 1) return;
        invalidate();
        String status;
        String adminEmail = adminEmail();
        if (adminEmail == null || adminEmail.isBlank()) {
            status = MAIL_NO_ADDRESS;
            log.info("Ağ kesintisi çözüm bildirimi atlandı — system-admin.email boş");
        } else {
            Map<String, Object> r = row(id);
            Long duration = num(r.get("duration_ms"));
            try {
                status = email.sendSystemAdminNetworkResolved(adminEmail, str(r.get("detected_at")),
                        str(r.get("resolved_at")), duration == null ? 0L : duration,
                        intOf(r.get("network_errors")), intOf(r.get("total_checks")), dbl(r.get("error_rate")));
            } catch (Exception e) {
                status = "FAILED: " + e.getMessage();
            }
        }
        finish("resolved_mail", id, claimMs, status, adminEmail);
    }

    /** Gönderimin sonucunu YALNIZ bu sahiplenmenin satırına yazar: kesin sonuç ya da RETRY (tavanda FAILED). */
    private void finish(String col, long id, long claimMs, String status, String adminEmail) {
        boolean finalResult = isFinal(status);
        try {
            if (finalResult) {
                jdbc.update("UPDATE network_outage_events SET " + col + "_status = ? WHERE id = ? AND " + col
                        + "_status = 'SENDING' AND " + col + "_claimed_ms = ?", clip(status), id, claimMs);
                log.info("Ağ kesintisi {} postası (olay #{}) → {} ({})", col.startsWith("alert") ? "açılış" : "çözüm", id,
                        status, adminEmail == null || adminEmail.isBlank() ? "-" : "yönetici");
            } else {
                int rows = jdbc.update("UPDATE network_outage_events SET " + col + "_attempts = COALESCE(" + col
                        + "_attempts, 0) + 1, " + col + "_status = CASE WHEN COALESCE(" + col + "_attempts, 0) + 1 >= ? "
                        + "THEN 'FAILED' ELSE 'RETRY' END WHERE id = ? AND " + col + "_status = 'SENDING' AND " + col
                        + "_claimed_ms = ?", MAX_ATTEMPTS, id, claimMs);
                String now = rows == 1 ? jdbc.queryForObject("SELECT " + col + "_status FROM network_outage_events WHERE id = ?",
                        String.class, id) : null;
                if (MAIL_FAILED.equals(now)) {
                    log.error("Ağ kesintisi {} postası (olay #{}) {} denemede gönderilemedi — vazgeçildi: {}",
                            col.startsWith("alert") ? "açılış" : "çözüm", id, MAX_ATTEMPTS, status);
                } else {
                    log.warn("Ağ kesintisi {} postası (olay #{}) gönderilemedi — sonraki turda (herhangi bir pod) yeniden "
                            + "denenecek: {}{}", col.startsWith("alert") ? "açılış" : "çözüm", id, status,
                            rows == 1 ? "" : " (sonuç yazılamadı)");
                }
            }
        } catch (RuntimeException e) {
            log.warn("Ağ kesintisi posta sonucu yazılamadı (olay #{}): {}", id, e.getMessage());
        } finally {
            invalidate();
        }
    }

    /** E-posta servisinin kesin sonucu mu (yeniden denenmez). 421 kuyruğu ({@code QUEUED_RETRY…}) servisin işidir. */
    static boolean isFinal(String status) {
        if (status == null) return false;
        return "SENT".equals(status) || "SKIPPED_DISABLED".equals(status)
                || InactiveRecipientGuard.STATUS_SKIPPED.equals(status)
                || MAIL_NO_ADDRESS.equals(status)
                || status.startsWith("QUEUED_RETRY");
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT detected_at, resolved_at, duration_ms, network_errors, total_checks, error_rate, "
                + "threshold FROM network_outage_events WHERE id = ?", id);
    }

    private String adminEmail() {
        return appSettings.getString("site.monitor.system-admin.email", systemAdminEmail);
    }

    private double thresholdSetting() {
        return appSettings.getDouble("site.monitor.network.error-rate-threshold", 0.50);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────────────────

    static Long durationMs(String detectedAt, long nowMs) {
        if (detectedAt == null) return null;
        try {
            return nowMs - Instant.from(ISO.parse(detectedAt)).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= MAX_STATUS_CHARS ? s : s.substring(0, MAX_STATUS_CHARS);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long num(Object o) {
        return o instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
