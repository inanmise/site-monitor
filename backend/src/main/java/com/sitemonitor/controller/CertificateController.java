package com.sitemonitor.controller;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.repository.AlertEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.service.CertificateHealthService;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import org.springframework.data.domain.PageRequest;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.ExtendedHealthService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SchedulerService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certService;
    private final CertificateCheckerService checkerService;
    private final SchedulerService schedulerService;
    private final AlertEventRepository alertEventRepository;
    private final CertificateInventoryRepository inventoryRepo;
    private final NetworkOutageEventRepository networkOutageRepo;
    private final ExtendedHealthService extendedHealthService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;
    private final com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;
    private final com.sitemonitor.service.CertificateHealthService healthService;
    private final com.sitemonitor.service.CertificateAppLayerProbe appLayerProbe;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/certificates")
    public ResponseEntity<Map<String, Object>> getCertificates(HttpSession session) {
        List<CertificateDto> data = certService.getAllLatestForTeams(SessionScope.viewTeamIds(session));
        return ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    @GetMapping("/certificates/list")
    public ResponseEntity<Map<String, Object>> getCertificatesPaginated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int per_page,
            @RequestParam(defaultValue = "domain") String sort_by,
            @RequestParam(defaultValue = "asc") String sort_dir,
            @RequestParam(defaultValue = "") String filter_domain,
            @RequestParam(defaultValue = "") String filter_issuer,
            @RequestParam(defaultValue = "") String filter_status,
            HttpSession session) {

        Map<String, Object> result = certService.getPaginated(page, per_page, sort_by, sort_dir,
                filter_domain, filter_issuer, filter_status, SessionScope.viewTeamIds(session));
        return ok(Map.of("success", true,
                "data", result.get("data"),
                "pagination", result.get("pagination"),
                "timestamp", now()));
    }

    @GetMapping("/warnings")
    public ResponseEntity<Map<String, Object>> getWarnings(HttpSession session) {
        List<CertificateDto> warnings = certService.getWarningsForTeams(SessionScope.viewTeamIds(session));
        return ok(Map.of("success", true, "data", warnings, "count", warnings.size(), "timestamp", now()));
    }

    /**
     * Domain-anahtarlı uçlar için takım denetimi: kayıt ENVANTERDE olmalı ve kullanıcının görüş
     * kapsamında bulunmalı. Eskiden bu uçların hiçbirinde denetim yoktu — herhangi bir takımın
     * kullanıcısı başka takımın sertifika geçmişini/alarmlarını okuyabiliyordu.
     * (MonitoringController.denyIfDomainNotViewable ile aynı kural.)
     */
    private CertificateInventory requireViewableDomain(HttpSession session, String domain) {
        var inv = inventoryRepo.findByDomain(domain)
                .orElseThrow(() -> new java.util.NoSuchElementException("Domain envanterde bulunamadı: " + domain));
        if (SessionScope.canView(session, inv.getTeamId())) return inv;
        if (inv.getUgTeamId() != null && SessionScope.canView(session, inv.getUgTeamId())) return inv;
        throw new SecurityException("Bu domain'i görüntüleme yetkiniz yok");
    }

    @GetMapping("/history/{domain}")
    public ResponseEntity<Map<String, Object>> getHistory(@PathVariable String domain, HttpSession session) {
        requireViewableDomain(session, domain);
        List<CertificateDto> history = certService.getHistory(domain, 30);
        return ok(Map.of("success", true, "domain", domain, "data", history, "timestamp", now()));
    }

    @GetMapping("/history/{domain}/alerts")
    public ResponseEntity<Map<String, Object>> getDomainAlerts(@PathVariable String domain, HttpSession session) {
        requireViewableDomain(session, domain);
        List<AlertEvent> alerts = alertEventRepository.findByDomainOrderByCreatedAtDesc(domain);
        return ok(Map.of("success", true, "domain", domain, "data", alerts, "timestamp", now()));
    }

    /**
     * Tek domain için elle kontrol (dashboard kartındaki ▶ ve toplu "Şimdi Kontrol Et" bunu kullanır).
     * YETKİ: domain envanterde OLMALI ve görüş kapsamında bulunmalı. Eskiden hiçbir denetim yoktu ve
     * her oturumlu kullanıcı (AUDIT dahil) rastgele bir host için dış bağlantı açtırıp, ensureInInventory
     * ile envantere KALICI kayıt ekletip evictAllCaches ile tüm cache'leri boşaltabiliyordu.
     * Kayıt zaten var olduğu için ensureInInventory de kaldırıldı (envanter kirlenmesi kapandı).
     */
    @GetMapping("/check/{domain}")
    public ResponseEntity<Map<String, Object>> checkDomain(@PathVariable String domain, HttpSession session) {
        CertificateInventory inv = requireViewableDomain(session, domain);
        boolean forceProxy = Boolean.TRUE.equals(inv.getUseProxy());
        String tlsOverride = inv.getTlsMode();
        // Envanterdeki GERÇEK port (zamanlayıcı da böyle yapıyor); 443'e sabitlemek 8443 gibi
        // portlardaki sertifikayı yanlış hedeften okutuyor ve UI'da yanlış port gösteriyordu.
        int port = inv.getPort() != null ? inv.getPort() : 443;
        Map<String, Object> result = new LinkedHashMap<>(
                checkerService.check(domain, port, forceProxy, tlsOverride, inv.getTimeoutSeconds()));
        result.put("run_id", "manual");
        result.put("port", port);
        certService.saveResult(result);
        // Manuel tetiklemede de cache evict gerekiyor (saveResult'tan kaldırıldı)
        certService.evictAllCaches();
        return ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    /**
     * Ad-hoc SERTIFIKA testi — envanter formundaki "Test et" dugmesi. KAYIT OLUSTURMAZ,
     * alarm URETMEZ, cache bosaltmaz.
     *
     * <p><b>Neden yeni bir uc.</b> Form "Test et"e basinca {@code DiagnosticsModal}'i aciyordu,
     * yani basliktaki "Tanilama" ile BIREBIR ayni isi yapiyordu: iki etiketli tek eylem, ve
     * sertifikanin kendisi (veren / bitis / kalan gun) hic test edilmiyordu. Dokuz izleme
     * formunun sozlesmesi ise "yazilan degerlerle gercek kontrolu kosur, sonucu formda goster".
     *
     * <p><b>Neden {@code check-preview} yetmedi.</b> O uc portu/TLS modunu/proxy'yi ENVANTERDEN
     * okuyor; envanterde OLMAYAN yeni bir kayit icin formda yazilan 8443 dikkate alinmiyor ve
     * kullanici 443'un sertifikasini goruyordu. Test, kaydedilecek olan degerlerin AYNISIYLA
     * kosmali -- yoksa testin dogruladigi sey kaydedilen sey degildir.
     *
     * <p>Emsal: {@code POST /monitoring/dns/test}. Yetki envanteri duzenleme yetkisidir
     * ({@code inventory.crud/edit}) -- serbest arama kutusundaki {@code check-preview}'dan
     * daha DAR: bu uc keyfi host:port'a canli el sikismasi actirabildigi icin oturum
     * yetmemeli. Hedef {@code CertificateCheckerService.check} icinde SsrfGuard'dan gecer.
     */
    @PostMapping("/certificates/test")
    public ResponseEntity<Map<String, Object>> testCertificate(
            @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "inventory.crud", "edit");

        Object rawDomain = body.get("domain");
        String domain = rawDomain == null ? "" : rawDomain.toString().trim();
        if (domain.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "domain zorunlu"));
        }
        // Kullanici "https://x.example.com/yol" yapistirabilir; ciplak host'a indir (form da boyle kaydeder).
        String host = com.sitemonitor.util.MonitorUrls.hostOrNull(domain);
        if (host != null && !host.isBlank()) domain = host;

        int port = 443;
        if (body.get("port") instanceof Number pn) port = pn.intValue();
        else if (body.get("port") != null && !body.get("port").toString().isBlank()) {
            try { port = Integer.parseInt(body.get("port").toString().trim()); } catch (NumberFormatException ignored) { /* 443 */ }
        }
        if (port < 1 || port > 65535) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Gecersiz port: " + port));
        }

        boolean useProxy = Boolean.TRUE.equals(body.get("useProxy"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("useProxy")));
        Object tlsRaw = body.get("tlsMode");
        String tlsMode = (tlsRaw == null || tlsRaw.toString().isBlank()) ? null : tlsRaw.toString().trim();
        Integer timeout = null;
        if (body.get("timeoutSeconds") instanceof Number tn) timeout = tn.intValue();

        // Denetim el sikismasindan ONCE: bu uc keyfi host:port'a canli TLS baglantisi actirir
        // (javadoc'un kendisi bunu "oturum yetmemeli" diye isaretliyor). Baglanti timeout'a
        // dusse de "kim neye baktirdi" sorusu cevaplanabilmeli.
        auditService.recordAction("MONITOR_TEST", session, "CERTIFICATE", "test",
                com.sitemonitor.service.AuditDetail.of("domain", domain, "port", port,
                        "use_proxy", useProxy, "tls_mode", tlsMode), null);

        Map<String, Object> r = new LinkedHashMap<>(checkerService.check(domain, port, useProxy, tlsMode, timeout));
        // Testin NE ILE kostugu yanitta durur: kullanici "hangi porta baktin" diye sormasin.
        r.put("port", port);
        return ok(Map.of("success", true, "data", r, "timestamp", now()));
    }

    /**
     * SSL Checker önizlemesi — envanterde OLMAYAN domainler de sorgulanabilir (Dashboard'daki
     * serbest arama kutusu bunu kullanır), bu yüzden takım kapsamı UYGULANMAZ.
     *
     * <p><b>Port düzeltmesi:</b> port 443'e sabitlenmişti; oysa proxy ve TLS modu zaten envanterden
     * okunuyordu. 8443 gibi bir portta duran envanter kaydında önizleme YANLIŞ hedefin
     * sertifikasını gösteriyordu (manuel kontrol yolu bunu doğru yapıyor, iki yüzey ayrışmıştı).
     * Envanterde yoksa 443 varsayılanı sürer.
     */
    @GetMapping("/check-preview/{domain}")
    public ResponseEntity<Map<String, Object>> previewDomain(@PathVariable String domain, HttpSession session) {
        // Kardeşi /check/{domain} requireViewableDomain ile korunuyordu; bu uç HİÇ oturum almıyordu:
        // herhangi bir rol/takım başka takımın envanter satırının canlı TLS sonucunu (port/proxy/TLS
        // modu dâhil) okuyabiliyordu. Envanterde varsa takım kapsamı; yoksa ad-hoc SSL Checker
        // (dashboard) için yalnız rol kapısı — SsrfGuard check() içinde zaten uygulanıyor.
        permissionService.require(session, "inventory.list", "view");
        var inv = inventoryRepo.findByDomain(domain);
        if (inv.isPresent()) requireViewableDomain(session, domain);
        boolean forceProxy = inv.map(ci -> Boolean.TRUE.equals(ci.getUseProxy())).orElse(false);
        String tlsOverride = inv.map(ci -> ci.getTlsMode()).orElse(null);
        int port = inv.map(CertificateInventory::getPort).filter(p -> p != null && p > 0).orElse(443);
        Map<String, Object> result = new LinkedHashMap<>(checkerService.check(domain, port, forceProxy,
                tlsOverride, inv.map(CertificateInventory::getTimeoutSeconds).orElse(null)));
        result.put("port", port);
        return ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    // NOT: eski /api/activity (yalnız sertifika, run-id gruplu) → yeni birleşik ActivityController devraldı
    // (tüm izleme türleri, sayfalı/filtreli, takım-izole). certService.getActivityLog artık kullanılmıyor.

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(HttpSession session) {
        return ok(Map.of("success", true, "data", certService.getStatsForTeams(SessionScope.viewTeamIds(session)), "timestamp", now()));
    }

    @GetMapping("/stats/teams")
    public ResponseEntity<Map<String, Object>> getTeamStats(HttpSession session) {
        List<Long> scope = SessionScope.viewTeamIds(session);
        if (scope == null) {  // global admin / AUDIT → all teams
            return ok(Map.of("success", true, "data", certService.getAllTeamsBreakdownStats(), "timestamp", now()));
        }
        if (scope.isEmpty()) return ok(Map.of("success", true, "data", Map.of(), "timestamp", now()));
        if (scope.size() == 1) {  // single team → personal breakdown (unchanged)
            String teamName = (String) session.getAttribute("teamName");
            return ok(Map.of("success", true, "data", certService.getTeamBreakdownStats(scope.get(0), teamName), "timestamp", now()));
        }
        // müdür / PO → per-team breakdown limited to their teams
        return ok(Map.of("success", true, "data", certService.getTeamsBreakdownStats(scope), "timestamp", now()));
    }

    @PostMapping("/scheduler/run")
    public ResponseEntity<Map<String, Object>> runScheduler(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "scheduler.run", "execute");
        schedulerService.triggerManualCheck();   // raw Thread yerine havuz (F4, CPU denetimi)
        // triggerManualCheck() void doner; UYDURMA sayi yazmak yerine kapsam ve tetigin ELLE
        // oldugu yazilir — "zamanlanmis mi, insan mi tetikledi" denetimdeki asil sorudur.
        auditService.recordAction("SCHEDULER_RUN", session, "SCHEDULER", "certificate-sweep",
                com.sitemonitor.service.AuditDetail.of("scope", "certificate-sweep", "trigger", "manual"), null);
        return ok(Map.of("success", true, "message", "Check started", "timestamp", now()));
    }

    @GetMapping("/scheduler/status")
    public ResponseEntity<Map<String, Object>> schedulerStatus() {
        return ok(Map.of("success", true, "data", schedulerService.getStatus(), "timestamp", now()));
    }

    /** Public (authenticated) network status — minimal alarm flag + detected timestamp.
     *  Used by Dashboard banner so all logged-in users see an outage notice. */
    @GetMapping("/system/network-status")
    public ResponseEntity<Map<String, Object>> publicNetworkStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alarm",       schedulerService.isNetworkOutageActive());
        data.put("detected_at", schedulerService.getNetworkOutageDetectedAt());
        return ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    /** Past network outage events (most recent first). Used by Warnings page history section. */
    @GetMapping("/system/network-outage-history")
    public ResponseEntity<Map<String, Object>> networkOutageHistory(
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> events = networkOutageRepo.findRecent(PageRequest.of(0, n))
                .stream().map(this::outageEventToMap).toList();
        return ok(Map.of("success", true, "events", events, "count", events.size(), "timestamp", now()));
    }

    private Map<String, Object> outageEventToMap(NetworkOutageEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("detected_at", e.getDetectedAt());
        m.put("resolved_at", e.getResolvedAt());
        m.put("duration_ms", e.getDurationMs());
        m.put("network_errors", e.getNetworkErrors());
        m.put("total_checks", e.getTotalChecks());
        // Kaynak: null ⇒ sertifika sweep'i (eski kayıtlar). İzleme sweep'lerinin bastırması
        // 2026-08'den beri buraya yazılıyor; ayrılmazsa iki farklı olgu tek listede karışır.
        m.put("source", e.getSource() == null ? "CERT" : e.getSource());
        m.put("error_rate", e.getErrorRate());
        m.put("threshold", e.getThreshold());
        m.put("status", e.getStatus());
        return m;
    }

    /**
     * Rozet kumesi: acik ama bildirimi GITMEMIS alarmi olan domainler.
     *
     * <p>TAKIM KAPSAMI (A2): bu uc ve kardesi `/notifications/failure-domains` eskiden
     * `HttpSession` parametresi BILE almiyordu ve sistemdeki TUM takimlarin domain adlarini
     * donuyordu. Ayni sinifin diger uclari kapsamli: `/certificates` ve `/renewal-advice`
     * `SessionScope.viewTeamIds`, `/history/{domain}` ise `requireViewableDomain` kullaniyor.
     * Domain adi bu uründe izolasyon konusudur.
     *
     * <p>Gorunur davranis DEGISMEZ: arayuz bu listeleri yalniz `silentAlertDomains.has(cert.domain)`
     * seklinde bir arama kumesi olarak kullaniyor ve kart listesi zaten takim kapsamli geliyor —
     * yani suzulen adlar hicbir zaman ekrana cizilmiyordu, sadece yanitta sizyorlardi.
     */
    @GetMapping("/alerts/silent-domains")
    public ResponseEntity<Map<String, Object>> getSilentAlertDomains(HttpSession session) {
        List<String> domains = retainViewableDomains(session,
                alertEventRepository.findDomainsWithUnnotifiedOpenAlerts());
        return ok(Map.of("success", true, "data", domains, "timestamp", now()));
    }

    /** Domains whose last N consecutive mail delivery attempts (within {days}d) have all failed.
     *  Surfaced as a warning badge on the certificate card. Takim kapsami icin bkz.
     *  {@link #getSilentAlertDomains}. */
    @GetMapping("/notifications/failure-domains")
    public ResponseEntity<Map<String, Object>> getMailFailureDomains(
            @RequestParam(defaultValue = "3") int consecutive,
            @RequestParam(defaultValue = "7") int days,
            HttpSession session) {
        int c = Math.max(2, Math.min(consecutive, 10));
        int d = Math.max(1, Math.min(days, 90));
        List<String> domains = retainViewableDomains(session,
                extendedHealthService.findDomainsWithConsecutiveMailFailures(c, d));
        return ok(Map.of("success", true, "data", domains, "count", domains.size(), "timestamp", now()));
    }

    /**
     * {@link #requireViewableDomain} kuralinin LISTE karsiligi: birincil VEYA UG takimi gorus
     * kapsamindaysa domain kalir, degilse dusulur.
     *
     * <p>Envanterde HIC olmayan bir domain (ornegin standalone bir izlemenin alarmi) kapsamli
     * oturumda dusulur — takima baglanamayan bir adi gostermek, tekil kapinin reddettigi seyi
     * liste uzerinden vermek olurdu. Global goruntuleyici (admin/AUDIT) icin suzme yapilmaz.
     */
    private List<String> retainViewableDomains(HttpSession session, List<String> domains) {
        if (domains.isEmpty() || SessionScope.isGlobalViewer(session)) return domains;
        List<Long> teams = SessionScope.viewTeamIds(session);
        // null/bos kapsam = hicbir takimi goremez (SessionScope.canView ile ayni sonuc).
        // Ayrica bos liste ile sorgu `IN ()` uretecegi icin depoya HIC gidilmemeli.
        if (teams == null || teams.isEmpty()) return List.of();
        java.util.Set<String> viewable = new java.util.HashSet<>(inventoryRepo.findDomainsForTeams(teams));
        return domains.stream().filter(viewable::contains).toList();
    }

    @GetMapping("/renewal-advice")
    public ResponseEntity<Map<String, Object>> getRenewalAdvice(HttpSession session) {
        List<Map<String, Object>> advice = certService.getRenewalAdviceForTeams(SessionScope.viewTeamIds(session));
        return ok(Map.of("success", true, "data", advice, "count", advice.size(), "timestamp", now()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns null (ADMIN sees all) or the user's teamId. */
    private Long teamId(HttpSession session) {
        if ("ADMIN".equals(session.getAttribute("systemRole"))) return null;
        Object raw = session.getAttribute("teamId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {   // global-only (müdür scoped-admin excluded)
            throw new SecurityException("Admin access required");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    // ── Sertifika sağlık kontrol listesi ────────────────────────────────────

    /** Aynı domain için arka arkaya tazeleme isteklerini frenler (manuel tetik cooldown deseni). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> healthRefreshAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long HEALTH_REFRESH_COOLDOWN_MS = 30_000L;

    /**
     * Sertifika sağlık kontrol listesi — KALICI son kontrolden anında üretilir (K2).
     *
     * <p>Modal açılışı ağ beklemez: değerlendirme {@code latest_checks} satırından yapılır.
     * Canlı el sıkışması yalnız kullanıcı "Şimdi kontrol et" derse koşar (refresh ucu). Böylece
     * her kart tıklamasında izlenen sunucuya el sıkışma yükü binmez.
     *
     * <p>Kapsam: domain envanterde olmalı ve takımı kullanıcının görüş alanında olmalı; değilse
     * 404 + güvenlik olayı (403 "var ama giremezsin" bilgisini sızdırır).
     */
    @GetMapping("/certificates/{domain}/health")
    public ResponseEntity<Map<String, Object>> certificateHealth(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = requireViewableForHealth(session, domain, request);
        if (inv == null) return notFoundBody();

        LatestCheck lc = latestCheckRepo.findById(domain).orElse(null);
        boolean hasPageMonitor = pageMonitorRepo.existsByUrlContainingIgnoreCaseAndActiveTrue(domain);
        var result = healthService.evaluate(lc, inv, hasPageMonitor);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("port", inv.getPort() != null ? inv.getPort() : 443);
        out.put("not_before", lc == null ? null : lc.getNotBefore());
        out.put("not_after", lc == null ? null : lc.getNotAfter());
        out.put("days_remaining", lc == null ? null : lc.getDaysRemaining());
        out.put("checked_at", lc == null ? null : lc.getCheckedAt());
        out.put("next_check_at", schedulerService.nextCertificateSweepAt());
        out.put("tls_mode_used", lc == null ? null : lc.getTlsModeUsed());
        out.put("has_page_monitor", hasPageMonitor);
        out.put("ok_count", result.okCount());
        out.put("evaluated_count", result.evaluatedCount());
        out.put("rows", result.rows().stream().map(CertificateController::healthRowJson).toList());
        return ok(Map.of("success", true, "data", out, "timestamp", now()));
    }

    /**
     * "Şimdi kontrol et" — envanter PORTUYLA canlı kontrol koşar, sonucu kalıcılaştırır ve
     * güncellenmiş sağlık listesini döner.
     *
     * <p>Aynı domain için 30 sn'lik soğuma: düğmeye üst üste basmak izlenen sunucuya el sıkışma
     * yağmuru olmasın (mevcut manuel tetik deseni).
     */
    @PostMapping("/certificates/{domain}/health/refresh")
    public ResponseEntity<Map<String, Object>> refreshCertificateHealth(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = requireViewableForHealth(session, domain, request);
        if (inv == null) return notFoundBody();

        long nowMs = System.currentTimeMillis();
        Long last = healthRefreshAt.get(domain);
        if (last != null && nowMs - last < HEALTH_REFRESH_COOLDOWN_MS) {
            long waitSec = (HEALTH_REFRESH_COOLDOWN_MS - (nowMs - last) + 999) / 1000;
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "error", "Çok sık kontrol — " + waitSec + " sn sonra tekrar deneyin"));
        }
        healthRefreshAt.put(domain, nowMs);

        int port = inv.getPort() != null ? inv.getPort() : 443;
        Map<String, Object> result = new LinkedHashMap<>(checkerService.check(
                domain, port, Boolean.TRUE.equals(inv.getUseProxy()), inv.getTlsMode(),
                inv.getTimeoutSeconds()));
        result.put("run_id", "health-refresh");
        result.put("port", port);
        certService.saveResult(result);
        // Uygulama katmanı satırları (HSTS, karışık içerik) yalnız BURADA doldurulur — saatlik
        // süpürmeye eklenseydi tüm envanter için her saat HTML çekilirdi. Best-effort: bu
        // kontroller patlasa da sertifika tazelemesi tamamlanmış sayılır.
        try {
            // İzlemenin kendi vekil tercihi proba da geçer: yukarıdaki sertifika kontrolü
            // aynı tercihle koşuyor, ikisi ayrışırsa aynı domain için iki farklı cevap çıkar.
            appLayerProbe.refresh(domain, port, Boolean.TRUE.equals(inv.getUseProxy()));
        } catch (Exception e) {
            log.debug("Uygulama katmanı kontrolleri atlandı ({}): {}", domain, e.toString());
        }
        certService.evictAllCaches();
        auditService.recordAction("CERT_HEALTH_REFRESH", session, "CERTIFICATE", domain,
                com.sitemonitor.service.AuditDetail.of("domain", domain, "caches_evicted", true), null);

        return certificateHealth(domain, session, request);
    }

    /** Envanter kaydını takım kapsamıyla döndürür; yetkisizse güvenlik olayı yazıp null döner. */
    private CertificateInventory requireViewableForHealth(HttpSession session, String domain,
                                                          HttpServletRequest request) {
        var inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null || !SessionScope.canView(session, inv.getTeamId())) {
            if (inv != null) {
                auditService.recordSecurityEvent("CERT_HEALTH_DENIED", request, session,
                        "CERTIFICATE", domain, "Yetkisiz sertifika sağlığı erişimi");
            }
            return null;
        }
        return inv;
    }

    private ResponseEntity<Map<String, Object>> notFoundBody() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Kayıt bulunamadı"));
    }

    /** Satır → JSON. Durum ve anahtarlar taşınır; CÜMLE kurulmaz (arayüz i18n'den kurar). */
    private static Map<String, Object> healthRowJson(CertificateHealthService.HealthRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", r.key());
        m.put("group", r.group());
        m.put("status", r.status().name());
        m.put("value_key", r.valueKey());
        m.put("value_args", r.valueArgs());
        m.put("action_key", r.actionKey());
        m.put("action_args", r.actionArgs());
        m.put("evidence", r.evidence());
        return m;
    }
}
