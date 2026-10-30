package com.sitemonitor.service.k6engine;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.DeploymentHistoryService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK k6 ayar normalleştirmesi (2026-10-30, k6 0.49 → 2.3 geçişi — Yayın B + C tek sürümde, kullanıcı kararı).
 *
 * <p>Yayın A'da (20.172.x) 2.3 ikilisi {@code /opt/k6-next/k6}'daydı; "Adayı etkin yap" etkin ayarı o yola, yedeği
 * {@code k6}'ya (= 0.49) yazıyordu. Bu sürümde imajda tek ikili var: {@code /usr/bin/k6} = 2.3 ve {@code /opt/k6-next/k6}
 * yalnız bu kayıtlı değerler çalışmaya devam etsin diye {@code /usr/bin/k6}'ya sembolik bağdır. Bu iş eski değerleri
 * varsayılana döndürür:
 * <ul>
 *   <li>etkin ikili {@code /opt/k6-next/k6} → varsayılan ({@code k6});</li>
 *   <li>yedek ikili {@code k6} / {@code /usr/bin/k6} / {@code /opt/k6-next/k6} → varsayılan (yok) — bu yollar artık etkin
 *       ikilinin AYNISI; "Yedeğe geri dön" aynı sürüme dönerdi;</li>
 *   <li>aday ikili {@code /opt/k6-next/k6} → varsayılan (yok).</li>
 * </ul>
 * Başka bir değer (elle girilmiş özel yol, kapalı işareti) olduğu gibi kalır; silme koşulludur (arada bir yönetici başka
 * değer kaydettiyse satıra dokunulmaz — {@link AppSettingsService#systemClearOverrideIf}).
 *
 * <p><b>Neden bütün pod'lar bu sürümdeyken:</b> dağıtım sürerken eski sürüm pod'unda varsayılan {@code k6} hâlâ 0.49'dur —
 * iş erken koşsa eski pod'lar dağıtım bitene kadar 0.49 ile koşardı. Bu yüzden başka sürüm koşan canlı pod varken (ya da pod
 * sürümleri okunamazken) tur atlanır ve sonraki turda yeniden bakılır. Açılış yolunda değil: açılıştan 3 dk sonra, 10 dk'da
 * bir, kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız iş bitince yazılır.
 */
@Slf4j
@Service
public class K6SettingsNormalizer {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-30-k6-settings-normalize";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "k6-settings-normalize";
    /** Hata sayacı (bekleme turları sayılmaz): 10 dk × 36 = 6 saat. */
    static final int MAX_ATTEMPTS = 36;
    /** Yayın A'nın aday ikili yolu; bu sürümde {@code /usr/bin/k6}'ya sembolik bağ. */
    public static final String LEGACY_CANDIDATE_PATH = "/opt/k6-next/k6";

    /** Anahtar → varsayılana döndürülecek kayıtlı değerler (sıralı: yedek, aday, etkin). */
    static final Map<String, Set<String>> RESET = resetMap();

    private static Map<String, Set<String>> resetMap() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, Set.of("k6", "/usr/bin/k6", LEGACY_CANDIDATE_PATH));
        m.put(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, Set.of(LEGACY_CANDIDATE_PATH));
        m.put(ScriptedCheckerService.K6_BIN_KEY, Set.of(LEGACY_CANDIDATE_PATH));
        return java.util.Collections.unmodifiableMap(m);
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService settings;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<DeploymentHistoryService> history;
    private final ObjectProvider<AuditService> auditService;
    private final AtomicInteger failures = new AtomicInteger();
    /**
     * Nişan bu pod'da bir kez görüldü mü (bellek). İş 10 dk'da bir tetiklenir; nişan yazıldıktan sonra her turda tablo DDL'i +
     * sorgu koşmasın diye ilk "var" cevabı hatırlanır (inceleme 2026-10-30). Okuma hatası hatırlanmaz.
     */
    private volatile boolean markerSeen;

    public K6SettingsNormalizer(AppSettingsService settings, JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                ObjectProvider<DeploymentHistoryService> history, ObjectProvider<AuditService> auditService) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.history = history;
        this.auditService = auditService;
    }

    @Scheduled(initialDelayString = "${site.monitor.k6.settings-normalize.initial-delay-ms:180000}", fixedDelay = 600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;          // bu pod denemeyi bıraktı (bir kez ERROR yazıldı)
        if (done()) return;
        if (!singleVersion()) return;                        // dağıtım sürüyor ya da okunamadı: sonraki tur
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::normalizeLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("[K6] Ayar normalleştirmesi {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("[K6] Ayar normalleştirmesi başarısız (10 dk sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Başka sürüm koşan canlı pod yok mu; okunamazsa "hayır" (bekle). Geçmiş servisi yoksa (dar bağlam) "evet". */
    boolean singleVersion() {
        DeploymentHistoryService h = history.getIfAvailable();
        if (h == null) return true;
        List<Map<String, String>> others = h.liveOtherVersions();
        if (others == null) {
            log.debug("[K6] Ayar normalleştirmesi bekliyor: pod sürümleri okunamadı");
            return false;
        }
        if (!others.isEmpty()) {
            log.debug("[K6] Ayar normalleştirmesi bekliyor: başka sürüm koşan pod var {}", others);
            return false;
        }
        return true;
    }

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        if (markerSeen) return true;
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            boolean seen = n != null && n > 0;
            if (seen) markerSeen = true;
            return seen;
        } catch (RuntimeException e) {
            log.debug("[K6] Ayar normalleştirmesi nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: eski değerleri varsayılana döndürür, sonra nişanı yazar. Dönüş: değişen anahtar sayısı. */
    Integer normalizeLocked() {
        if (done()) return 0;
        List<String> changed = new ArrayList<>();
        Map<String, String> before = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : RESET.entrySet()) {
            String old = settings.getOverride(e.getKey());
            if (settings.systemClearOverrideIf(e.getKey(), e.getValue(), ACTOR)) {
                changed.add(e.getKey());
                before.put(e.getKey(), old);
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), changed.size());
        markerSeen = true;
        if (changed.isEmpty()) {
            log.info("[K6] Ayar normalleştirmesi: değiştirilecek eski ikili yolu yok (etkin k6 = imajdaki {})", "/usr/bin/k6");
        } else {
            log.info("[K6] Ayar normalleştirmesi tamamlandı — eski ikili yolları varsayılana döndü: {}", before);
            AuditService a = auditService.getIfAvailable();
            if (a != null) {
                try {
                    a.recordSystemEvent("K6_ENGINE_SETTINGS_NORMALIZE", "SETTINGS", "k6-engine",
                            AuditDetail.of("keys", changed, "previous", before));
                } catch (RuntimeException ex) {
                    log.warn("[K6] Ayar normalleştirmesi denetim kaydı yazılamadı: {}", ex.toString());
                }
            }
        }
        return changed.size();
    }
}
