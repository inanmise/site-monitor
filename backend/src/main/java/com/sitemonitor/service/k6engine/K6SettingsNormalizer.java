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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK k6 ayar normalleştirmesi — iki GEÇİŞ, her birinin KENDİ nişanı var ({@link #PASSES}); bir geçiş nişanı
 * yazıldıktan sonra bir daha koşmaz.
 *
 * <h2>1. geçiş — {@link #KEY} (2026-10-30, k6 0.49 → 2.3 geçişi, Yayın B + C tek sürümde, kullanıcı kararı)</h2>
 *
 * <p>Yayın A'da (20.172.x) 2.3 ikilisi {@code /opt/k6-next/k6}'daydı; "Adayı etkin yap" etkin ayarı o yola, yedeği
 * {@code k6}'ya (= 0.49) yazıyordu. O sürümden beri imajda {@code /opt/k6-next/k6}, kayıtlı değerler çalışmaya devam etsin
 * diye {@code /usr/bin/k6}'ya sembolik bağdır. Bu geçiş yalnız YEDEK ve ADAY yollarını varsayılana (yok) döndürür:
 * <ul>
 *   <li>yedek ikili {@code k6} / {@code /usr/bin/k6} / {@code /opt/k6-next/k6} → yok — bu yollar etkin ikilinin
 *       AYNISI; "Yedeğe geri dön" aynı sürüme dönerdi;</li>
 *   <li>aday ikili {@code /opt/k6-next/k6} → yok (aynı ikili; 20.172.x'e geri alınırsa oradaki varsayılan zaten bu yol).</li>
 * </ul>
 *
 * <h2>2. geçiş — {@link #KEY_PROMOTION_24} (2026-11-20, k6 2.3 → 2.4 TERFİSİ, kullanıcı kararı)</h2>
 *
 * <p>20.212 – 20.217'de (1. adım) 2.4.0 imajda ADAY olarak {@code /opt/k6-candidate/k6}'daydı; aday varsayılanı o yoldu ve
 * "Adayı etkin yap" etkin ayarı o yola, yedeği {@code k6}'ya (= gerçek 2.3.0) yazıyordu. Terfi sürümünde imajda TEK ikili
 * var: {@code k6}, {@code /usr/bin/k6}, {@code /opt/k6-next/k6} ve {@code /opt/k6-candidate/k6} AYNI 2.4.0 ikilisidir (son
 * ikisi sembolik bağ). Bu yüzden:
 * <ul>
 *   <li>yedek ikili {@code k6} / {@code /usr/bin/k6} / {@code /opt/k6-next/k6} / {@code /opt/k6-candidate/k6} → yok —
 *       hangisini gösterirse göstersin "Yedeğe geri dön" aynı sürüme dönerdi (2.3.0 imajda yok);</li>
 *   <li>aday ikili {@code /opt/k6-candidate/k6} → yok — o yol artık etkin ikilinin kendisi; kayıtlı kalsaydı sayfa "aday
 *       hazır" derdi ve "Adayı etkin yap" hiçbir şeyi değiştirmeyen bir geçiş olurdu.</li>
 * </ul>
 * {@code helm rollback} ile 20.212 – 20.217'ye dönülürse yönetici yedek alanına {@code k6} (orada gerçek 2.3.0) yeniden
 * yazabilir; aday varsayılanı orada zaten {@code /opt/k6-candidate/k6}'dır.
 *
 * <h2>İki geçişin ortak kuralları</h2>
 *
 * <p><b>Etkin ikiliye ({@code k6-bin}) DOKUNULMAZ</b> (inceleme 2026-10-30, M1): kayıtlı {@code /opt/k6-next/k6} bu imajda
 * etkin ikiliye sembolik bağ, {@code helm rollback} ile 20.172.x'e dönülürse GERÇEK 2.3'tür; kayıtlı
 * {@code /opt/k6-candidate/k6} bu imajda yine etkin ikiliye bağ, 20.212 – 20.217'ye dönülürse GERÇEK 2.4.0 adayıdır.
 * Varsayılana ({@code k6}) çevirmek geri dönüşte bütün senaryo izlemelerini sessizce eski sürüme düşürürdü. Bu yüzden iki
 * sembolik bağ, kayıtlı o değeri ÖNCE taşıyan gelecekteki bir sürüme dek imajda kalır — kaldırma kararı nişana değil,
 * KAYITLI DEĞERE bakmalıdır.
 *
 * <p><b>Yedek, etkin ikili gerçekten BAŞKA bir ikiliyse korunur:</b> kayıtlı etkin değer o geçişin takma adlarının
 * ({@link Pass#activeAliases}) dışındaysa (elle girilmiş özel bir yol) yedekteki {@code k6} / {@code /usr/bin/k6} etkin
 * ikiliden FARKLI bir ikilidir — sıfırlanırsa "Yedeğe geri dön" kapanırdı. Aday sıfırlaması hiçbir zaman atlanmaz.
 *
 * <p>Başka bir değer (elle girilmiş özel yol, kapalı işareti) olduğu gibi kalır; silme koşulludur (arada bir yönetici başka
 * değer kaydettiyse satıra dokunulmaz — {@link AppSettingsService#systemClearOverrideIf}).
 *
 * <p><b>Neden bütün pod'lar bu sürümdeyken:</b> dağıtım sürerken eski sürüm pod'unda {@code k6} başka bir sürümdür (20.172.x'te
 * 0.49, 20.212 – 20.217'de 2.3.0) ve yedek / aday değerlerini o pod'lar da okur. Temkin için başka sürüm koşan canlı pod
 * varken (ya da pod sürümleri okunamazken) tur atlanır ve sonraki turda yeniden bakılır. Açılış yolunda değil: açılıştan 3 dk
 * sonra, 10 dk'da bir, kendi kilidi altında; her nişan kilit İÇİNDE yeniden okunur ve yalnız o geçiş bitince yazılır.
 */
@Slf4j
@Service
public class K6SettingsNormalizer {

    /** 1. geçişin nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-30-k6-settings-normalize";
    /** 2. geçişin (k6 2.3 → 2.4 terfisi) nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY_PROMOTION_24 = "2026-11-20-k6-24-promotion-normalize";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "k6-settings-normalize";
    /** Hata sayacı (bekleme turları sayılmaz): 10 dk × 36 = 6 saat. */
    static final int MAX_ATTEMPTS = 36;
    /** Yayın A'nın (20.172.x) aday ikili yolu; o günden beri imajda {@code /usr/bin/k6}'ya sembolik bağ. */
    public static final String LEGACY_CANDIDATE_PATH = "/opt/k6-next/k6";
    /** 2.3 → 2.4 geçişinin 1. adımının (20.212 – 20.217) aday ikili yolu; terfiden beri {@code /usr/bin/k6}'ya sembolik bağ. */
    public static final String LEGACY_CANDIDATE_PATH_24 = "/opt/k6-candidate/k6";

    /**
     * 1. geçiş: anahtar → varsayılana döndürülecek kayıtlı değerler (sıralı: yedek, aday). Etkin ikili ({@code k6-bin})
     * BİLEREK yok — sınıf açıklamasına bakın.
     */
    static final Map<String, Set<String>> RESET = resetMap(
            Set.of("k6", "/usr/bin/k6", LEGACY_CANDIDATE_PATH),
            Set.of(LEGACY_CANDIDATE_PATH));

    /**
     * 2. geçiş (terfi): yedek için dört takma adın hepsi, aday için yalnız 1. adımın aday yolu. Etkin ikili
     * ({@code k6-bin}) yine YOK: kayıtlı {@code /opt/k6-candidate/k6} kalmalı (20.212 – 20.217'ye geri dönüşte gerçek 2.4.0).
     */
    static final Map<String, Set<String>> RESET_PROMOTION_24 = resetMap(
            Set.of("k6", "/usr/bin/k6", LEGACY_CANDIDATE_PATH, LEGACY_CANDIDATE_PATH_24),
            Set.of(LEGACY_CANDIDATE_PATH_24));

    private static Map<String, Set<String>> resetMap(Set<String> rollback, Set<String> candidate) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, rollback);
        m.put(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, candidate);
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Bir geçiş: kendi nişanı, sıfırlanacak değerler ve o geçişin yayınlandığı imajda ETKİN ikilinin takma adları (kayıtlı
     * etkin değer bunların dışındaysa yedek sıfırlanmaz — sınıf açıklaması).
     */
    record Pass(String key, Map<String, Set<String>> reset, Set<String> activeAliases) {}

    /** Geçişler, koşma sırasıyla. Yeni bir geçiş SONA eklenir; var olan bir geçişin nişanı / kümeleri değiştirilmez. */
    static final List<Pass> PASSES = List.of(
            new Pass(KEY, RESET, RESET.get(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)),
            new Pass(KEY_PROMOTION_24, RESET_PROMOTION_24, RESET_PROMOTION_24.get(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)));

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService settings;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<DeploymentHistoryService> history;
    private final ObjectProvider<AuditService> auditService;
    private final AtomicInteger failures = new AtomicInteger();
    /**
     * Bu pod'da bir kez görülen nişanlar (bellek). İş 10 dk'da bir tetiklenir; nişanlar yazıldıktan sonra her turda tablo
     * DDL'i + sorgu koşmasın diye ilk "var" cevabı hatırlanır (inceleme 2026-10-30). Okuma hatası hatırlanmaz. En çok
     * {@link #PASSES} kadar öğe tutar.
     */
    private final Set<String> markersSeen = ConcurrentHashMap.newKeySet();

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
        if (allDone()) return;
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

    /** Bütün geçişlerin nişanı yazılmış mı (geçiş sayısı sabit: {@link #PASSES}). */
    boolean allDone() {
        for (Pass p : PASSES) {
            if (!done(p.key())) return false;
        }
        return true;
    }

    /** Bu nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar — hatırlanmaz). */
    boolean done(String key) {
        if (markersSeen.contains(key)) return true;
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, key);
            boolean seen = n != null && n > 0;
            if (seen) markersSeen.add(key);
            return seen;
        } catch (RuntimeException e) {
            log.debug("[K6] Ayar normalleştirmesi nişanı okunamadı ({}): {}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 1. geçişin anlamıyla: etkin ikili ayarı eski 2.3 takma adlarının ({@code k6}, {@code /usr/bin/k6},
     * {@code /opt/k6-next/k6}) DIŞINDA bir yola mı çevrildi ({@link #activeSwitchedAway(Set)}).
     */
    boolean activeSwitchedAway() {
        return activeSwitchedAway(PASSES.get(0).activeAliases());
    }

    /**
     * Etkin ikili ayarı verilen takma adların DIŞINDA bir yola mı çevrildi. Kayıtlı değer yoksa ya da boşsa varsayılan
     * ({@code k6}) geçerlidir → hayır. Takma adlar = o geçişin imajında etkin ikilinin AYNISI olan yollar.
     */
    boolean activeSwitchedAway(Set<String> aliases) {
        String active = settings.getOverride(ScriptedCheckerService.K6_BIN_KEY);
        if (active == null || active.isBlank()) return false;
        return aliases == null || !aliases.contains(active.strip());
    }

    /**
     * Kilit altında: nişanı yazılmamış her geçişi sırayla uygular (eski değerleri varsayılana döndürür, sonra O geçişin
     * nişanını yazar — değişen bir şey olmasa da). Dönüş: değişen anahtar sayısı (bütün geçişlerin toplamı).
     */
    Integer normalizeLocked() {
        int total = 0;
        for (Pass pass : PASSES) {
            if (done(pass.key())) continue;
            total += apply(pass);
        }
        return total;
    }

    /** Tek geçiş: koşullu sıfırlamalar → nişan → günlük + (yalnız bir şey değiştiyse) denetim kaydı. */
    private int apply(Pass pass) {
        List<String> changed = new ArrayList<>();
        Map<String, String> before = new LinkedHashMap<>();
        boolean switched = activeSwitchedAway(pass.activeAliases());
        for (Map.Entry<String, Set<String>> e : pass.reset().entrySet()) {
            if (switched && ScriptedCheckerService.K6_BIN_ROLLBACK_KEY.equals(e.getKey())) {
                // 2026-11-16 (k6 2.4 aday ikili, inceleme ORTA): etkin ikili bu geçişin takma adlarından biri DEĞİL (1.
                // geçişte ör. "Adayı etkin yap" ile /opt/k6-candidate/k6 = 2.4; 2. geçişte elle girilmiş özel bir yol). O
                // zaman yedekteki k6 / /usr/bin/k6 etkin ikiliden FARKLI bir ikilidir — sıfırlanırsa "Yedeğe geri dön" ve
                // belgelenen geri alma yolu kapanır. Yedeğe dokunulmaz.
                log.info("[K6] Ayar normalleştirmesi: etkin ikili bu geçişin takma adlarından biri değil — yedek ikili yolu "
                        + "korunuyor [geçiş {}]", pass.key());
                continue;
            }
            String old = settings.getOverride(e.getKey());
            if (settings.systemClearOverrideIf(e.getKey(), e.getValue(), ACTOR)) {
                changed.add(e.getKey());
                before.put(e.getKey(), old);
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                pass.key(), ISO.format(Instant.now()), changed.size());
        markersSeen.add(pass.key());
        // Günlük satırlarının başı (… tamamlandı — / … değiştirilecek eski yedek / aday yolu yok) belgelerde aranır
        // (PROD_DEPLOY_CHECKLIST, RUNBOOK) — değiştirmeyin; geçiş nişanı sonda.
        if (changed.isEmpty()) {
            log.info("[K6] Ayar normalleştirmesi: değiştirilecek eski yedek / aday yolu yok (etkin ikili ayarına dokunulmaz) "
                    + "[geçiş {}]", pass.key());
        } else {
            log.info("[K6] Ayar normalleştirmesi tamamlandı — eski yedek / aday yolları varsayılana döndü (etkin ikili ayarına "
                    + "dokunulmaz): {} [geçiş {}]", before, pass.key());
            AuditService a = auditService.getIfAvailable();
            if (a != null) {
                try {
                    // Geçişin nişanı "marker" adıyla yazılır — "pass" adı SecretMask'te hassas sayılır (parola), değer
                    // denetimde "*****" görünürdü.
                    a.recordSystemEvent("K6_ENGINE_SETTINGS_NORMALIZE", "SETTINGS", "k6-engine",
                            AuditDetail.of("keys", changed, "previous", before, "marker", pass.key()));
                } catch (RuntimeException ex) {
                    log.warn("[K6] Ayar normalleştirmesi denetim kaydı yazılamadı: {}", ex.toString());
                }
            }
        }
        return changed.size();
    }
}
