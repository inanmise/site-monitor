package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateFacts;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.ChainValidationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.RevocationReason;
import com.sitemonitor.service.TrustEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Elle yüklenen sertifikaların ÇEVRİM-DIŞI değerlendirmesi (2026-10-06).
 *
 * <p>Geçerli sürümün açık zincirinden ({@code chain_pem}) ağ kontrolünün ürettiği sonuç haritasının AYNI biçimi
 * kurulur ({@link CertificateFacts#leafResult} — tek kural) ve aynı huniye verilir: {@link CertificateService#saveResult}
 * ({@code certificate_checks} + {@code latest_checks} + etkinlik akışı) ardından alarm hattı. Fark yalnız:
 * {@code via="upload"}, {@code deployment_status="UNKNOWN"}, ağa özgü anahtarlar (TLS sürümü, şifre takımı, rota,
 * HSTS, HTTP durumu) {@code null}, güven yalnız zincir TAMSA hükme bağlanır ({@link ManualCertificateChains#trust}),
 * {@code manual=true} işareti (alarm hattı HOSTNAME_MISMATCH üretmez).
 *
 * <p><b>İki giriş:</b>
 * <ul>
 *   <li>{@link #evaluateScheduled} — zamanlanmış süpürmenin ayrı adımı (SchedulerService kendi kilidiyle çağırır):
 *       normal alarm kuralları ({@code processResults}; sertifika alarmları kapalıysa yalnız kapanış).</li>
 *   <li>{@link #evaluateNow} — elle tetik (yükleme, yenileme, "şimdi değerlendir", {@code /check}): yalnız kapanış
 *       uzlaştırması ({@code resolveVerifiedStaleCertAlerts}) — "elle kontrol alarm açmaz" kuralı.</li>
 * </ul>
 *
 * <p><b>İptal durumu</b> (OCSP → CRL) CA'ya ağ çağrısıdır: parmak izi başına en çok {@value #REVOCATION_TTL_HOURS}
 * saatte bir sorulur (önbellek); hata → {@code UNKNOWN} (asla alarm). Elle tetiklerde istek iş parçacığı CA'yı
 * beklemez: önbellekte yoksa sonuç {@code UNKNOWN} döner ve sorgu arka planda ısıtılır.
 */
@Slf4j
@Service
public class ManualCertificateEvaluationService {

    public static final String VIA_UPLOAD = com.sitemonitor.service.CertificateHealthRules.VIA_UPLOAD;
    static final int REVOCATION_TTL_HOURS = 6;
    private static final int REVOCATION_CACHE_MAX = 10_000;

    private final CertificateService certService;
    private final EscalationService escalationService;
    private final ChainValidationService chainValidator;
    private final TrustEvaluator trustEvaluator;
    private final ManualCertificateVersionRepository versionRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.warning-days:30}")
    private int warningDays = 30;

    /** İptal sorgusunun arka plan ısıtması — isteğe bağlı (test bağlamında yok → ısıtma yapılmaz). */
    @Autowired(required = false)
    @Qualifier("certCheckExecutor")
    private Executor certCheckExecutor;

    private record CachedRevocation(ChainValidationService.RevocationCheck check, Instant at) { }

    private final Map<String, CachedRevocation> revocationCache = new ConcurrentHashMap<>();
    private final Set<String> revocationInFlight = ConcurrentHashMap.newKeySet();

    public ManualCertificateEvaluationService(CertificateService certService, EscalationService escalationService,
                                              ChainValidationService chainValidator, TrustEvaluator trustEvaluator,
                                              ManualCertificateVersionRepository versionRepo,
                                              CertificateInventoryRepository inventoryRepo,
                                              AppSettingsService appSettings) {
        this.certService = certService;
        this.escalationService = escalationService;
        this.chainValidator = chainValidator;
        this.trustEvaluator = trustEvaluator;
        this.versionRepo = versionRepo;
        this.inventoryRepo = inventoryRepo;
        this.appSettings = appSettings;
    }

    /** Değerlendirilecek AKTİF manuel kayıtlar (silinmiş kayıt zaten aktif değildir). */
    public List<CertificateInventory> activeManualRows() {
        List<CertificateInventory> out = new ArrayList<>();
        for (CertificateInventory inv : inventoryRepo.findByCertSourceAndActiveTrueOrderByDomainAsc(CertificateInventory.SOURCE_MANUAL)) {
            if (inv.getDeletedAt() == null && inv.isManual()) out.add(inv);
        }
        return out;
    }

    // ── Sonuç haritası ───────────────────────────────────────────────────────

    /**
     * Geçerli sürümden kontrol sonucu — ağ kontrolünün başarılı sonucuyla AYNI anahtarlar. Zincir okunamazsa null.
     *
     * @param allowRevocationFetch iptal durumu önbellekte yoksa CA'ya şimdi sorulsun mu (yalnız arka plan süpürmesi)
     */
    public Map<String, Object> buildResult(CertificateInventory inv, ManualCertificateVersion v, boolean allowRevocationFetch) {
        List<X509Certificate> chain = CertificateFileParser.readPemChain(v.getChainPem());
        if (chain.isEmpty()) return null;
        Map<String, Object> r = buildFromChain(inv.getDomain(), chain,
                allowRevocationFetch ? RevocationMode.FETCH : RevocationMode.CACHED_WARM);
        r.put("manual_version", v.getVersion());
        return r;
    }

    /** İptal durumu nereden okunur: CA'ya sor / önbellek + arka planda ısıt / YALNIZ önbellek (hiç ağ yok). */
    enum RevocationMode { FETCH, CACHED_WARM, CACHED_ONLY }

    // ── Önizleme (yazmaz, alarm yok) ─────────────────────────────────────────

    /**
     * Kayıtlı manuel satırın sertifika penceresi "SSL" sekmesi için ÖNİZLEME (2026-10-07): geçerli sürümden aynı sonuç
     * haritası — {@code certificate_checks} satırı YOK, alarm / uzlaştırma YOK, önbellek boşaltma YOK. İptal durumu
     * elle tetikteki gibi önbellekten (yoksa {@code UNKNOWN} + arka planda ısıtma).
     *
     * @return geçerli sürüm yoksa, zincir okunamazsa ya da kayıt manuel değilse null
     */
    public Map<String, Object> previewCurrent(CertificateInventory inv) {
        if (inv == null || inv.getId() == null || !inv.isManual()) return null;
        ManualCertificateVersion v = versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(inv.getId()).orElse(null);
        if (v == null) return null;
        Map<String, Object> r = buildResult(inv, v, false);
        // Yalnız önizlemede (kaydedilen sonuçta YOK): SSL sekmesinin "Hiyerarşi" görünümü sürüm zincirini bu kimlikle çeker
        if (r != null) r.put("manual_version_id", v.getId());
        return r;
    }

    /**
     * Yükleme analizindeki zincir başı için ÖNİZLEME (2026-10-07): sihirbazın İnceleme adımı zinciri ağ sertifikasının SSL
     * sekmesindeki görünümle çizer. Hiçbir şey YAZMAZ ve AĞA ÇIKMAZ (iptal durumu yalnız önbellekten; ısıtma da yok —
     * analiz edilen dosya henüz takipte değil).
     *
     * @param key     önerilen takip adı (sonuç haritasının {@code domain} alanı)
     * @param leaf    zincirin başı
     * @param issuers başın zinciri (kendisi HARİÇ, yapraktan köke)
     */
    public Map<String, Object> previewChain(String key, X509Certificate leaf, List<X509Certificate> issuers) {
        if (leaf == null) return null;
        List<X509Certificate> full = new ArrayList<>(1 + (issuers == null ? 0 : issuers.size()));
        full.add(leaf);
        if (issuers != null) full.addAll(issuers);
        return buildFromChain(key, full, RevocationMode.CACHED_ONLY);
    }

    /** Sonuç haritası — {@code chain[0]} yaprak (baş), ardından verenler. */
    Map<String, Object> buildFromChain(String domain, List<X509Certificate> chain, RevocationMode revocationMode) {
        long start = System.currentTimeMillis();
        X509Certificate leaf = chain.get(0);
        Instant now = Instant.now();
        Map<String, Object> r = CertificateFacts.leafResult(leaf, domain, warningDays, chainValidator, now);
        // Ağa özgü alanlar — anahtar VAR, değer yok (sonuç biçimi ağ kontrolüyle aynı kalsın).
        r.put("source_ip", null);
        r.put("source_port", null);
        r.put("peer_ip", null);
        r.put("peer_port", null);
        r.put("tls_version", null);
        r.put("cipher_suite", null);
        r.put("alpn", null);

        Certificate[] arr = chain.toArray(new Certificate[0]);
        Map<String, Object> chainInfo = chainValidator.analyzeChain(arr);
        List<X509Certificate> issuers = chain.subList(1, chain.size());
        boolean selfSignedLeaf = ManualCertificateChains.isSelfSigned(leaf);
        // Yalnız yaprak yüklendiyse zincir hakkında bilgi YOK → UNKNOWN (CHAIN_BROKEN alarmı üretmez). Ara sertifika
        // dosyada varsa ağ yoluyla aynı hüküm (süresi dolmuş ara → BROKEN).
        String chainStatus = (!issuers.isEmpty() || selfSignedLeaf)
                ? String.valueOf(chainInfo.get("chain_status")) : "UNKNOWN";
        r.put("chain_status", chainStatus);
        r.put("intermediate_expiry", chainInfo.get("intermediate_expiry"));
        r.put("intermediate_days_remaining", chainInfo.get("intermediate_days_remaining"));
        r.put("chain", chainInfo.get("chain"));

        String fingerprint = chainValidator.calculateFingerprint(leaf);
        r.put("fingerprint", fingerprint);

        ChainValidationService.RevocationCheck rev = revocationCheck(fingerprint, arr, revocationMode);
        String revocation = rev.status();
        r.put("revocation_status", revocation);
        // NEDEN (2026-10-08): adres yok / yalnız LDAP / ulaşılamadı / sorgu bekleniyor — arayüz doğru açıklamayı seçer.
        if (rev.reason() != null) r.put("revocation_reason", rev.reason());
        if (rev.detail() != null) r.put("revocation_detail", rev.detail());
        if ("REVOKED".equals(revocation)) r.put("chain_status", "REVOKED");

        ManualCertificateChains.Trust trust = ManualCertificateChains.trust(trustEvaluator, leaf, new ArrayList<>(issuers));
        r.put("trust_status", trust.status());

        r.put("deployment_status", "UNKNOWN");
        r.put("resolved_ip", null);
        r.put("hsts", null);
        r.put("http_status", null);
        r.put("via", VIA_UPLOAD);
        r.put("tls_mode_used", null);
        r.put("elapsed_ms", System.currentTimeMillis() - start);
        r.put("manual", true);
        return r;
    }

    // ── Elle tetik ───────────────────────────────────────────────────────────

    /**
     * Elle tetik (yükleme, yenileme, "şimdi değerlendir", {@code /check}, sağlık tazeleme): sonuç kaydedilir, yalnız
     * KAPANIŞ uzlaştırması koşar — yeni alarm / eskalasyon / yeniden uyarı YOK. Sağlıklı yeni sürüm açık süre
     * alarmını normal çözüm bildirimiyle kapatır.
     *
     * @return kaydedilen sonuç; geçerli sürüm yoksa ya da zincir okunamazsa null
     */
    public Map<String, Object> evaluateNow(CertificateInventory inv, String runId) {
        if (inv == null || inv.getId() == null || !inv.isManual()) return null;
        ManualCertificateVersion v = versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(inv.getId()).orElse(null);
        if (v == null) {
            log.warn("Manuel sertifika değerlendirilemedi — geçerli sürüm yok: {}", inv.getDomain());
            return null;
        }
        Map<String, Object> built = buildResult(inv, v, false);
        if (built == null) {
            log.warn("Manuel sertifika değerlendirilemedi — saklanan zincir okunamadı: {} (sürüm {})", inv.getDomain(), v.getVersion());
            return null;
        }
        Map<String, Object> r = new LinkedHashMap<>(built);
        r.put("run_id", runId);
        certService.saveResult(r);
        certService.evictAllCaches();
        try {
            escalationService.resolveVerifiedStaleCertAlerts(List.of(r));
        } catch (Exception e) {
            log.warn("Manuel sertifika kapanış uzlaştırması başarısız {}: {}", inv.getDomain(), e.getMessage());
        }
        return r;
    }

    // ── Zamanlanmış adım ─────────────────────────────────────────────────────

    /**
     * Zamanlanmış süpürmenin manuel adımı — çağıran (SchedulerService) kendi dağıtık kilidini tutar. Ağ kesintisi
     * bastırması UYGULANMAZ (ağ kullanılmıyor). Alarmlar normal kurallarla; sertifika alarmları kapalıysa yalnız
     * kapanış (ağ süpürmesiyle aynı ayar).
     *
     * @return değerlendirilen kayıt sayısı
     */
    public int evaluateScheduled(List<CertificateInventory> rows, String runId) {
        if (rows == null || rows.isEmpty()) return 0;
        List<Long> ids = rows.stream().map(CertificateInventory::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return 0;
        Map<Long, ManualCertificateVersion> current = new HashMap<>();
        for (ManualCertificateVersion v : versionRepo.findByInventoryIdInAndCurrentTrue(ids)) {
            current.merge(v.getInventoryId(), v, (a, b) -> a.getVersion() >= b.getVersion() ? a : b);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (CertificateInventory inv : rows) {
            if (!inv.isManual()) continue;
            ManualCertificateVersion v = current.get(inv.getId());
            if (v == null) {
                log.warn("Manuel sertifika atlandı — geçerli sürüm yok: {}", inv.getDomain());
                continue;
            }
            try {
                Map<String, Object> built = buildResult(inv, v, true);
                if (built == null) {
                    log.warn("Manuel sertifika atlandı — saklanan zincir okunamadı: {} (sürüm {})", inv.getDomain(), v.getVersion());
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>(built);
                r.put("run_id", runId);
                results.add(r);
            } catch (Exception e) {
                log.warn("Manuel sertifika değerlendirmesi başarısız {}: {}", inv.getDomain(), e.toString());
            }
        }
        if (results.isEmpty()) return 0;
        for (Map<String, Object> r : results) {
            try { certService.saveResult(r); }
            catch (Exception e) { log.warn("Manuel sertifika sonucu kaydedilemedi {}: {}", r.get("domain"), e.getMessage()); }
        }
        certService.evictAllCaches();
        if (appSettings.getBoolean("site.monitor.expiry.alert-enabled", true)) {
            escalationService.processResults(results);
        } else {
            escalationService.resolveVerifiedStaleCertAlerts(results);
        }
        log.info("Manuel sertifika değerlendirmesi tamamlandı — runId={}, {} kayıt", runId, results.size());
        return results.size();
    }

    // ── İptal durumu (önbellekli) ────────────────────────────────────────────

    String revocationStatus(String fingerprint, Certificate[] chain, boolean allowFetch) {
        return revocationCheck(fingerprint, chain, allowFetch ? RevocationMode.FETCH : RevocationMode.CACHED_WARM).status();
    }

    ChainValidationService.RevocationCheck revocationCheck(String fingerprint, Certificate[] chain, RevocationMode mode) {
        // Ağsız ön karar (adres yok / yalnız desteklenmeyen şema / veren yok): önbellek ve ısıtma BEKLETMEZ — adresi
        // olmayan sertifikaya "sorgu bekleniyor" denmez (2026-10-08).
        ChainValidationService.RevocationCheck pre;
        try {
            pre = chainValidator.revocationPrecheck(chain);
        } catch (Exception e) {
            pre = null;
        }
        if (pre != null) return pre;
        if (fingerprint == null) return ChainValidationService.RevocationCheck.unknown(null, null);
        CachedRevocation c = revocationCache.get(fingerprint);
        if (c != null && Duration.between(c.at(), Instant.now()).toHours() < REVOCATION_TTL_HOURS) return c.check();
        ChainValidationService.RevocationCheck pending =
                ChainValidationService.RevocationCheck.unknown(RevocationReason.PENDING, null);
        if (mode == RevocationMode.CACHED_ONLY) return c != null ? c.check() : pending;
        if (mode == RevocationMode.CACHED_WARM) {
            warmRevocation(fingerprint, chain);
            return c != null ? c.check() : pending;
        }
        return fetchRevocation(fingerprint, chain);
    }

    private ChainValidationService.RevocationCheck fetchRevocation(String fingerprint, Certificate[] chain) {
        ChainValidationService.RevocationCheck s;
        try {
            s = chainValidator.checkRevocationDetailed(chain);
        } catch (Exception e) {
            s = null;
        }
        if (s == null || s.status() == null || s.status().isBlank()) s = ChainValidationService.RevocationCheck.unknown(null, null);
        if (revocationCache.size() > REVOCATION_CACHE_MAX) revocationCache.clear();
        revocationCache.put(fingerprint, new CachedRevocation(s, Instant.now()));
        return s;
    }

    private void warmRevocation(String fingerprint, Certificate[] chain) {
        if (certCheckExecutor == null || !revocationInFlight.add(fingerprint)) return;
        try {
            certCheckExecutor.execute(() -> {
                try { fetchRevocation(fingerprint, chain); }
                finally { revocationInFlight.remove(fingerprint); }
            });
        } catch (Exception e) {
            revocationInFlight.remove(fingerprint);
        }
    }
}
