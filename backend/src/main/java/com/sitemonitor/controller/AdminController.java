package com.sitemonitor.controller;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.ConnectionDiagnosticsService;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.DomainExpiryDiagnosticsService;
import com.sitemonitor.service.DomainExpiryRefreshService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.HstsDiagnosticsService;
import com.sitemonitor.service.MonitoringGroupService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.NetworkDiagnosticsService;
import com.sitemonitor.service.OpensslDiagnosticsService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ProxyCaExportService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuditService auditService;
    private final CertificateInventoryRepository inventoryRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final EscalationContactRepository contactRepo;
    private final AlertEventRepository alertEventRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final EscalationService escalationService;
    private final LatestCheckRepository latestCheckRepo;
    private final CertificateCheckRepository certificateCheckRepo;
    private final CertificateNoteRepository noteRepo;
    private final CertificateNoteRevisionRepository noteRevisionRepo;
    private final UserService userService;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final EmailNotificationService emailNotificationService;
    private final ConnectionDiagnosticsService diagnosticsService;
    private final OpensslDiagnosticsService opensslDiagnosticsService;
    private final SsrfGuard ssrfGuard;
    private final NetworkDiagnosticsService networkDiagnosticsService;
    private final HstsDiagnosticsService hstsDiagnosticsService;
    private final DiagnosticHistoryService diagnosticHistoryService;
    private final DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;
    private final DomainExpiryRefreshService domainExpiryRefreshService;
    private final ProxyCaExportService proxyCaExportService;
    private final PublicSuffixService publicSuffixService;
    private final ClientIpResolver clientIpResolver;

    private final PermissionService permissionService;
    private final MonitoringGroupService monitoringGroupService;
    private final SchedulerService schedulerService;

    /** "Uzun süredir açık" eşiği (saat) — bu yaştan eski AÇIK alarm unutulmuş kabul edilir. */
    private static final int ALERT_STALE_HOURS = 24;

    /** Tekrar rozetinin penceresi (gün) — bu süre içindeki aynı (domain, tip) alarmları sayılır. */
    private static final int ALERT_REPEAT_WINDOW_DAYS = 30;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Alan Adı Tanılama için basit kullanıcı-başı sliding-window (10/dk) — registry'leri dövmemek için.
     *  Tek-pod prod'da bellek-içi yeterli. Key = user id (yoksa client IP). */
    private final Map<String, Deque<Long>> domainDiagRate = new ConcurrentHashMap<>();
    private static final int  DOMAIN_DIAG_MAX_PER_MIN = 10;
    private static final long DOMAIN_DIAG_WINDOW_MS = 60_000L;

    // ── Inventory ─────────────────────────────────────────────────────────────

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> listInventory(
            @RequestParam(defaultValue = "false") boolean showDeleted,
            HttpSession session) {
        requirePerm(session, "inventory.list", "view");
        List<CertificateInventory> items;
        if (isAdminOrAudit(session)) {                     // global admin / AUDIT → all
            items = showDeleted
                    ? inventoryRepo.findAllByOrderByDomainAsc()
                    : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
        } else {                                           // scoped (müdür/PO/USER)
            List<Long> scope = viewScope(session);
            items = (scope == null || scope.isEmpty())
                    ? List.of()
                    : inventoryRepo.findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(scope);
        }
        // SY/UG takım adlarını sunucuda çöz — USER rolü tüm takım listesini çekemediğinden
        // (kendi takımıyla filtreli) liste kolonlarında takım adları boş kalmasın.
        Map<Long, String> teamNames = new HashMap<>();
        for (Team tm : userService.listTeams()) {
            if (tm.getId() != null) teamNames.put(tm.getId(), tm.getName());
        }
        for (CertificateInventory it : items) {
            if (it.getTeamId() != null)   it.setTeamName(teamNames.get(it.getTeamId()));
            if (it.getUgTeamId() != null) it.setUgTeamName(teamNames.get(it.getUgTeamId()));
        }
        return ok(Map.of("data", items));
    }

    /** Tek domain'in envanter kaydı (kart modalındaki "Envanter Bilgileri" tab'ı için).
     *  listInventory ile AYNI izin + kapsam; domain tekil → en fazla tek kayıt (yoksa data:null). */
    @GetMapping("/inventory/by-domain")
    public ResponseEntity<Map<String, Object>> getInventoryByDomain(
            @RequestParam String domain,
            HttpSession session) {
        requirePerm(session, "inventory.list", "view");
        CertificateInventory rec = inventoryRepo.findByDomain(domain).orElse(null);
        // Kapsam: admin/audit değilse yalnız kendi görüş kapsamındaki takımın kaydı görünür
        // (çapraz-takım sızıntısı olmasın — listInventory'deki viewScope semantiği).
        if (rec != null && !isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            if (scope == null || scope.isEmpty()
                    || rec.getTeamId() == null || !scope.contains(rec.getTeamId())) {
                rec = null;
            }
        }
        if (rec != null) {
            Map<Long, String> teamNames = new HashMap<>();
            for (Team tm : userService.listTeams()) {
                if (tm.getId() != null) teamNames.put(tm.getId(), tm.getName());
            }
            if (rec.getTeamId() != null)   rec.setTeamName(teamNames.get(rec.getTeamId()));
            if (rec.getUgTeamId() != null) rec.setUgTeamName(teamNames.get(rec.getUgTeamId()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("data", rec);   // Map.of null değer almaz → HashMap
        return ok(body);
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PostMapping("/inventory")
    public ResponseEntity<Map<String, Object>> addInventory(
            @Valid @RequestBody CertificateInventory item, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "inventory.crud", "edit");
        item.setDomain(validateDomain(item.getDomain()));   // URL yapıştırılırsa host'a normalize edilir
        if (item.getTeamId() == null) {
            throw new IllegalArgumentException("A team must be selected for the certificate");
        }
        // The chosen team must be within the caller's manage scope (global admin: any;
        // PO: only teams they lead; müdür: none).
        requireTeamScopedAdmin(session, item.getTeamId());
        String now = now();
        item.setId(null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        if (item.getPort() == null) item.setPort(443);
        if (item.getPort() < 1 || item.getPort() > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (item.getActive() == null) item.setActive(true);
        item.setTlsMode(normalizeTlsMode(item.getTlsMode()));
        item.setGroupName(monitoringGroupService.getOrCreate(item.getTeamId(), "cert", item.getGroupName(), actor(session)));
        CertificateInventory saved = inventoryRepo.save(item);
        auditService.recordAction("DOMAIN_ADD", session, request,
                "CERTIFICATE", saved.getDomain(),
                "{\"port\":" + saved.getPort() + ",\"teamId\":" + saved.getTeamId() + "}");
        // Anında tek-domain kontrol (async): latest_checks satırı hemen oluşsun → Genel Bakış'ta
        // gecikmeden görünür ve kontrollere dahil olur (5-dk stale sweep'i beklemeden).
        schedulerService.checkSingleDomainAsync(saved.getDomain(),
                saved.getPort() != null ? saved.getPort() : 443,
                Boolean.TRUE.equals(saved.getUseProxy()), saved.getTlsMode());
        return ok(Map.of("data", saved, "message", "Domain added to inventory"));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PutMapping("/inventory/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateInventory(
            @PathVariable Long id, @Valid @RequestBody CertificateInventory item,
            HttpSession session, HttpServletRequest request) {
        CertificateInventory existing = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        requireTeamScopedAdmin(session, existing.getTeamId());
        requirePerm(session, "inventory.crud", "edit");
        // TEAM_ADMIN cannot transfer an item to another team via this endpoint —
        // freeze teamId to its current value.
        if (isTeamAdmin(session)) {
            item.setTeamId(existing.getTeamId());
        }
        item.setTlsMode(normalizeTlsMode(item.getTlsMode()));

        // Build diff BEFORE applying changes
        String diffJson = buildInventoryDiff(existing, item, true);

        // ── Domain rename: latest_checks + geçmiş tabloları yeni isme taşı ────
        // Aksi halde SchedulerService.syncLatestChecksToInventory (artık devre
        // dışı ama her ihtimale karşı koruyalım) ya da legacy data kalıntıları
        // eski domain'i boş metadata ile yeniden oluşturuyordu. Plus rename
        // çakışmasını UNIQUE constraint patlamadan önce yakala.
        String oldDomain = existing.getDomain();
        String newDomain = item.getDomain();
        boolean renamed = newDomain != null && oldDomain != null
                && !newDomain.equalsIgnoreCase(oldDomain);
        if (renamed) {
            if (inventoryRepo.existsByDomain(newDomain)) {
                throw new IllegalStateException(
                        "Bu domain (\"" + newDomain + "\") envanterde zaten var. "
                        + "Önce diğer kaydı silmelisin veya farklı bir isim seç.");
            }
            int lc = latestCheckRepo.renameDomain(oldDomain, newDomain);
            int cc = certificateCheckRepo.renameDomain(oldDomain, newDomain);
            int ae = alertEventRepo.renameDomain(oldDomain, newDomain);
            int nt = noteRepo.renameDomain(oldDomain, newDomain);
            log.info("Domain renamed: '{}' → '{}' (latest={}, checks={}, alerts={}, notes={})",
                    oldDomain, newDomain, lc, cc, ae, nt);
        }

        existing.setDomain(item.getDomain());
        existing.setPort(item.getPort() != null ? item.getPort() : 443);
        existing.setDescription(item.getDescription());
        existing.setOwner(item.getOwner());
        existing.setTags(item.getTags());
        boolean wasActive = Boolean.TRUE.equals(existing.getActive());
        existing.setActive(item.getActive() != null ? item.getActive() : true);
        existing.setExpectedFingerprint(item.getExpectedFingerprint());
        existing.setExpectedSubject(item.getExpectedSubject());
        if (item.getTeamId() != null) existing.setTeamId(item.getTeamId());
        existing.setUgTeamId(item.getUgTeamId());
        existing.setExternalVendor(item.getExternalVendor());
        existing.setActionRequired(item.getActionRequired());
        existing.setOpenshift(item.getOpenshift());
        existing.setSslPinning(item.getSslPinning());
        existing.setInternalCert(item.getInternalCert());
        existing.setJksKeystore(item.getJksKeystore());
        existing.setServerUpdate(item.getServerUpdate());
        existing.setNetscaler(item.getNetscaler());
        existing.setWafEnabled(item.getWafEnabled());
        existing.setInUse(item.getInUse());
        existing.setEvCertificate(item.getEvCertificate());
        existing.setTransferredToSy(item.getTransferredToSy());
        existing.setUseProxy(item.getUseProxy());
        existing.setTlsMode(item.getTlsMode());
        existing.setPurchasedBy(item.getPurchasedBy());
        existing.setChangeDescription(item.getChangeDescription());
        existing.setTier(item.getTier());
        existing.setGroupName(monitoringGroupService.getOrCreate(existing.getTeamId(), "cert", item.getGroupName(), actor(session)));
        existing.setUpdatedAt(now());
        CertificateInventory saved = inventoryRepo.save(existing);

        // Pasife alındıysa (true→false) açık alarmları sessizce kapat — izleme durduğundan
        // aksi halde otomatik çözülemezler (toplu pasif akışıyla aynı semantik).
        int alertsClosed = 0;
        if (wasActive && Boolean.FALSE.equals(saved.getActive()) && saved.getDeletedAt() == null) {
            alertsClosed = escalationService.closeAlertsOnDeactivate(saved.getDomain());
        }

        if (!diffJson.equals("{}")) {
            auditService.recordAction("DOMAIN_EDIT", session, request,
                    "CERTIFICATE", saved.getDomain(), diffJson);
        }
        return ok(Map.of("data", saved, "alertsClosed", alertsClosed));
    }

    private String buildInventoryDiff(CertificateInventory o, CertificateInventory n, boolean isAdmin) {
        StringBuilder sb = new StringBuilder("{");
        fieldDiff(sb, "domain",             o.getDomain(),               n.getDomain());
        fieldDiff(sb, "port",               o.getPort(),                 n.getPort() != null ? n.getPort() : 443);
        fieldDiff(sb, "active",             o.getActive(),               n.getActive() != null ? n.getActive() : true);
        fieldDiff(sb, "tier",               o.getTier(),                 n.getTier());
        fieldDiff(sb, "description",        o.getDescription(),          n.getDescription());
        fieldDiff(sb, "owner",              o.getOwner(),                n.getOwner());
        fieldDiff(sb, "tags",               o.getTags(),                 n.getTags());
        fieldDiff(sb, "externalVendor",     o.getExternalVendor(),       n.getExternalVendor());
        fieldDiff(sb, "actionRequired",     o.getActionRequired(),       n.getActionRequired());
        fieldDiff(sb, "openshift",          o.getOpenshift(),            n.getOpenshift());
        fieldDiff(sb, "sslPinning",         o.getSslPinning(),           n.getSslPinning());
        fieldDiff(sb, "internalCert",       o.getInternalCert(),         n.getInternalCert());
        fieldDiff(sb, "jksKeystore",        o.getJksKeystore(),          n.getJksKeystore());
        fieldDiff(sb, "serverUpdate",       o.getServerUpdate(),         n.getServerUpdate());
        fieldDiff(sb, "netscaler",          o.getNetscaler(),            n.getNetscaler());
        fieldDiff(sb, "wafEnabled",         o.getWafEnabled(),           n.getWafEnabled());
        fieldDiff(sb, "inUse",              o.getInUse(),                n.getInUse());
        fieldDiff(sb, "evCertificate",      o.getEvCertificate(),        n.getEvCertificate());
        fieldDiff(sb, "transferredToSy",    o.getTransferredToSy(),      n.getTransferredToSy());
        fieldDiff(sb, "useProxy",           o.getUseProxy(),             n.getUseProxy());
        fieldDiff(sb, "tlsMode",            o.getTlsMode(),              n.getTlsMode());
        fieldDiff(sb, "purchasedBy",        o.getPurchasedBy(),          n.getPurchasedBy());
        fieldDiff(sb, "changeDescription",  o.getChangeDescription(),    n.getChangeDescription());
        fieldDiff(sb, "expectedFingerprint",o.getExpectedFingerprint(),  n.getExpectedFingerprint());
        fieldDiff(sb, "expectedSubject",    o.getExpectedSubject(),      n.getExpectedSubject());
        if (isAdmin && n.getTeamId() != null)
            fieldDiff(sb, "teamId",         o.getTeamId(),               n.getTeamId());
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private void fieldDiff(StringBuilder sb, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            sb.append('"').append(field).append("\":{\"from\":")
              .append(toJsonVal(oldVal)).append(",\"to\":")
              .append(toJsonVal(newVal)).append("},");
        }
    }

    private String toJsonVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        String s = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                               .replace("\n", "\\n").replace("\r", "");
        return '"' + s + '"';
    }

    /** tls_mode: null/blank → null (inherit global); only "browser"/"default" allowed. */
    private String normalizeTlsMode(String v) {
        if (v == null || v.isBlank()) return null;
        String m = v.trim().toLowerCase();
        if (!m.equals("browser") && !m.equals("default")) {
            throw new IllegalArgumentException("tls_mode must be 'browser' or 'default'");
        }
        return m;
    }

    /** Tanı: pod'a ulaşan forwarding başlıkları + remoteAddr + çözülen IP. Loglardaki
     *  client IP yanlışsa (proxy IP), gerçek IP'nin hangi başlıkta olduğunu görmek için. */
    @GetMapping("/client-ip-debug")
    public ResponseEntity<Map<String, Object>> clientIpDebug(HttpServletRequest request, HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", clientIpResolver.debugInfo(request)));
    }

    // ── Connection diagnostics ────────────────────────────────────────────────

    @PostMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> runDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = diagnosticsService.diagnose(domain, port);
        auditService.recordAction("DIAGNOSTICS_RUN", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        // Tanılama geçmişi — kim/ne zaman/nereden/sonuç
        boolean ok = data.get("combos") instanceof List<?> combos
                && combos.stream().anyMatch(c -> c instanceof Map<?, ?> m && "ok".equals(m.get("status")));
        diagnosticHistoryService.record(domain, port, "CONNECTION",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, connectionSummary(data), data);
        return ok(Map.of("data", data));
    }

    /** Derin SSL/TLS taraması — openssl s_client çeşitli parametrelerle. */
    @PostMapping("/diagnostics/openssl")
    public ResponseEntity<Map<String, Object>> runOpensslDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = opensslDiagnosticsService.probe(domain, port);
        auditService.recordAction("DIAGNOSTICS_OPENSSL", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        boolean ok = Boolean.TRUE.equals(data.get("available"));
        diagnosticHistoryService.record(domain, port, "OPENSSL",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, opensslSummary(data), data);
        return ok(Map.of("data", data));
    }

    /** Ağ derin analizi — ping/traceroute/dns/tcp/curl/ip. */
    @PostMapping("/diagnostics/network")
    public ResponseEntity<Map<String, Object>> runNetworkDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = networkDiagnosticsService.analyze(domain, port);
        auditService.recordAction("DIAGNOSTICS_NETWORK", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        Object okC = data.get("ok_count");
        Object total = data.get("total");
        boolean ok = okC instanceof Number n && n.intValue() > 0;
        diagnosticHistoryService.record(domain, port, "NETWORK",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, okC + "/" + total + " kontrol OK", data);
        return ok(Map.of("data", data));
    }

    /** HSTS analizi — Strict-Transport-Security başlığını okur, yönergeleri ayrıştırır,
     *  neyi nasıl kontrol ettiğini ve neyi bulamadığını açıklar. */
    @PostMapping("/diagnostics/hsts")
    public ResponseEntity<Map<String, Object>> runHstsDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = hstsDiagnosticsService.diagnose(domain, port);
        auditService.recordAction("DIAGNOSTICS_HSTS", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        String verdict = String.valueOf(data.get("verdict"));
        boolean ok = "ok".equals(data.get("status"));
        diagnosticHistoryService.record(domain, port, "HSTS",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "HSTS: " + verdict, data);
        return ok(Map.of("data", data));
    }

    /** Alan adı (registrar) süre bitişi tanılaması — RDAP/WHOIS zincirini adım adım koşar ve
     *  proxy/PKIX/port-43 sorunlarını görünür kılar. Başarılı sonuç eşleşen envanter satırlarına yazılır. */
    @PostMapping("/diagnostics/domain-expiry")
    public ResponseEntity<Map<String, Object>> runDomainExpiryDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        // Kullanıcı-başı hız sınırı (10/dk) — RDAP/WHOIS registry'lerini dövmemek için.
        Long uid = userIdFromSession(session);
        checkDomainDiagRate(uid != null ? "u" + uid : "ip" + clientIp(request));

        Map<String, Object> data = domainExpiryDiagnosticsService.diagnose(domain);
        String source = String.valueOf(data.get("source"));
        boolean ok = !"FAILED".equals(source) && data.get("expiry_date") != null;

        auditService.recordAction("DIAGNOSTICS_DOMAIN_EXPIRY", session, request,
                "DOMAIN", domain, "{}");
        diagnosticHistoryService.record(domain, null, "DOMAIN_EXPIRY",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "DOMAIN_EXPIRY: " + source, data);

        // Başarılı → taze süre bitişini eşleşen envanter satır(lar)ına yaz (best-effort; hata diagnostic'i bozmaz).
        if (ok) {
            data.put("persisted", persistDomainExpiryToInventory(
                    String.valueOf(data.get("registrable")),
                    (String) data.get("expiry_date"),
                    (String) data.get("registrar")));
        }
        return ok(Map.of("data", data));
    }

    /** Kurumsal SSL-inspection proxy'sinin sunduğu CA zincirini yakalar ve "Güvenilir CA paketi (PEM)"
     *  alanına yapıştırılmaya hazır PEM olarak döner. Pod içinden çalışır → CA'yı UI'dan almayı sağlar. */
    @PostMapping("/diagnostics/proxy-ca-chain")
    public ResponseEntity<Map<String, Object>> captureProxyCaChain(
            @RequestBody(required = false) Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "diagnostics.run", "execute");
        Long uid = userIdFromSession(session);
        checkDomainDiagRate(uid != null ? "u" + uid : "ip" + clientIp(request));

        String host = (body != null && body.get("host") != null && !body.get("host").toString().isBlank())
                ? body.get("host").toString().trim() : "data.iana.org";
        host = validateDiagTarget(host);
        Long portRaw = body != null ? toLong(body.get("port")) : null;
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");

        Map<String, Object> data = proxyCaExportService.capture(host, port);
        boolean ok = Boolean.TRUE.equals(data.get("ok"));
        auditService.recordAction("DIAGNOSTICS_PROXY_CA_CHAIN", session, request, "DOMAIN", host, "{\"port\":" + port + "}");
        diagnosticHistoryService.record(host, port, "PROXY_CA_CHAIN",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "PROXY_CA_CHAIN: " + (ok ? data.get("ca_count") + " CA" : data.get("error_class")), data);
        return ok(Map.of("data", data));
    }

    /** Süre bitişini registrable domain'i eşleşen aktif envanter satırlarına yazar; güncellenen satır sayısını döner.
     *  Tek kaynak {@link DomainExpiryRefreshService#persistToInventory} (zamanlı tazeleme de aynı yolu kullanır). */
    private int persistDomainExpiryToInventory(String registrable, String expiry, String registrar) {
        return domainExpiryRefreshService.persistToInventory(registrable, expiry, registrar);
    }

    /** Sliding-window hız sınırı; aşılırsa 429 TOO_MANY_REQUESTS fırlatır. */
    private void checkDomainDiagRate(String key) {
        long now = System.currentTimeMillis();
        pruneDomainDiagRate(now);
        Deque<Long> dq = domainDiagRate.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > DOMAIN_DIAG_WINDOW_MS) dq.pollFirst();
            if (dq.size() >= DOMAIN_DIAG_MAX_PER_MIN) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Çok fazla tanılama isteği — dakikada en fazla " + DOMAIN_DIAG_MAX_PER_MIN + ". Lütfen bekleyin.");
            }
            dq.addLast(now);
        }
    }

    /** Uzun uptime'da map anahtarları birikmesin: penceresi boşalan anahtarları at
     *  (AuthController.pruneRateLimitState deseni); aşırı durumda sert clear() backstop'u
     *  (CaAutoPinService cap deseni). Yalnız eşik aşıldığında çalışır — sıcak yolda maliyetsiz. */
    private void pruneDomainDiagRate(long now) {
        if (domainDiagRate.size() <= 1_000) return;
        domainDiagRate.entrySet().removeIf(e -> {
            Deque<Long> dq = e.getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && now - dq.peekFirst() > DOMAIN_DIAG_WINDOW_MS) dq.pollFirst();
                return dq.isEmpty();
            }
        });
        if (domainDiagRate.size() > 10_000) domainDiagRate.clear();
    }

    /** Domain tanılama geçmişi listesi (resultJson hariç özet). */
    @GetMapping("/diagnostics/history")
    public ResponseEntity<Map<String, Object>> diagnosticsHistory(
            @RequestParam String domain, HttpSession session) {
        domain = validateDomain(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.history", "view");
        List<Map<String, Object>> data = diagnosticHistoryService.history(domain).stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("run_type", d.getRunType());
                    m.put("executed_by", d.getExecutedBy());
                    m.put("executed_at", d.getExecutedAt());
                    m.put("source_ip", d.getSourceIp());
                    m.put("port", d.getPort());
                    m.put("success", d.getSuccess());
                    m.put("summary", d.getSummary());
                    return m;
                }).toList();
        return ok(Map.of("data", data));
    }

    /** Tek geçmiş kaydı — saklanan tam sonucu (resultJson) yeniden gösterim için. */
    @GetMapping("/diagnostics/history/{id}")
    public ResponseEntity<Map<String, Object>> diagnosticsHistoryDetail(
            @PathVariable Long id, HttpSession session) {
        com.sitemonitor.model.DiagnosticRun d = diagnosticHistoryService.get(id);
        requireAdminOrMonitoredDomain(session, d.getDomain());
        requirePerm(session, "diagnostics.history", "view");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("domain", d.getDomain());
        m.put("port", d.getPort());
        m.put("run_type", d.getRunType());
        m.put("executed_by", d.getExecutedBy());
        m.put("executed_at", d.getExecutedAt());
        m.put("source_ip", d.getSourceIp());
        m.put("success", d.getSuccess());
        m.put("summary", d.getSummary());
        m.put("result_json", d.getResultJson());
        return ok(Map.of("data", m));
    }

    /** "3/4 kombinasyon OK" tarzı kısa CONNECTION özeti. */
    private String connectionSummary(Map<String, Object> data) {
        if (!(data.get("combos") instanceof List<?> combos)) return "—";
        long ok = combos.stream().filter(c -> c instanceof Map<?, ?> m && "ok".equals(m.get("status"))).count();
        return ok + "/" + combos.size() + " kombinasyon OK";
    }

    /** "TLS1.0 açık; 2 bayrak" tarzı kısa OPENSSL özeti. */
    private String opensslSummary(Map<String, Object> data) {
        if (!Boolean.TRUE.equals(data.get("available"))) return "openssl bulunamadı";
        StringBuilder sb = new StringBuilder();
        if (data.get("protocols") instanceof List<?> protos) {
            String weak = protos.stream()
                    .filter(p -> p instanceof Map<?, ?> m
                            && Boolean.TRUE.equals(m.get("supported")) && "HIGH".equals(m.get("risk")))
                    .map(p -> String.valueOf(((Map<?, ?>) p).get("proto")))
                    .reduce((a, b) -> a + ", " + b).orElse(null);
            sb.append(weak != null ? "Zayıf protokol açık: " + weak : "Zayıf protokol yok");
        }
        if (data.get("flags") instanceof List<?> flags && !flags.isEmpty()) {
            sb.append("; ").append(flags.size()).append(" sertifika bayrağı");
        }
        return sb.toString();
    }

    /** İstek IP'si — merkezî, yapılandırılabilir resolver (proxy/LB başlıkları). */
    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @DeleteMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> deleteInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return inventoryRepo.findById(id).map(inv -> {
            requireTeamScopedAdmin(session, inv.getTeamId());
            requirePerm(session, "inventory.crud", "edit");
            inv.setDeletedAt(now());
            inv.setActive(false);
            inventoryRepo.save(inv);
            int alertsClosed = escalationService.closeAlertsOnInventoryDelete(inv.getDomain());
            auditService.recordAction("DOMAIN_SOFT_DELETE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + ",\"alertsClosed\":" + alertsClosed + "}");
            return ok(Map.of("message", "Deleted", "alertsClosed", alertsClosed));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toplu envanter işlemi — seçili domain'leri tek istekte aktif/pasif yapar ya da siler.
     * action ∈ {activate, deactivate, delete}. Yetki tekil uçlarla aynı: girişte
     * admin/team-admin şartı, ardından her kayıt için takım kapsamı (yetkisiz/yok olan
     * kayıt atlanır; tek kaydın yetkisizliği partiyi düşürmez). "delete" tekil soft-delete
     * ile birebir (deletedAt+active=false + alarm kapatma). Zaten silinmiş/silinmiş kayıtlar
     * activate/deactivate için atlanır (geri yükleme ayrı akıştır).
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PostMapping("/inventory/bulk")
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkInventoryAction(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "inventory.crud", "edit");
        String action = body.get("action") != null ? body.get("action").toString().trim().toLowerCase() : "";
        if (!Set.of("activate", "deactivate", "delete").contains(action)) {
            throw new IllegalArgumentException("action must be one of: activate, deactivate, delete");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) { Long id = toLong(o); if (id != null) ids.add(id); }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("No ids provided");

        int processed = 0, skipped = 0, alertsClosed = 0;
        String ts = now();
        for (Long id : ids) {
            CertificateInventory inv = inventoryRepo.findById(id).orElse(null);
            if (inv == null || !canManageTeamResource(session, inv.getTeamId())) { skipped++; continue; }
            boolean deleted = inv.getDeletedAt() != null;
            switch (action) {
                case "activate" -> {
                    if (deleted) { skipped++; }            // silinmiş kayıt → "geri yükle" akışı kullanılmalı
                    else { inv.setActive(true);  inv.setUpdatedAt(ts); inventoryRepo.save(inv); processed++; }
                }
                case "deactivate" -> {
                    if (deleted) { skipped++; }
                    else {
                        boolean wasActive = Boolean.TRUE.equals(inv.getActive());
                        inv.setActive(false); inv.setUpdatedAt(ts); inventoryRepo.save(inv);
                        // Pasife alınan domain artık taranmaz → açık alarmlarını sessizce kapat
                        if (wasActive) alertsClosed += escalationService.closeAlertsOnDeactivate(inv.getDomain());
                        processed++;
                    }
                }
                case "delete" -> {
                    if (deleted) { skipped++; }            // zaten silinmiş → no-op
                    else {
                        inv.setDeletedAt(ts); inv.setActive(false); inv.setUpdatedAt(ts);
                        inventoryRepo.save(inv);
                        alertsClosed += escalationService.closeAlertsOnInventoryDelete(inv.getDomain());
                        processed++;
                    }
                }
            }
        }
        String auditAction = switch (action) {
            case "activate"   -> "DOMAIN_BULK_ACTIVATE";
            case "deactivate" -> "DOMAIN_BULK_DEACTIVATE";
            default            -> "DOMAIN_BULK_DELETE";
        };
        auditService.recordAction(auditAction, session, request, "CERTIFICATE",
                processed + " domain",
                "{\"processed\":" + processed + ",\"skipped\":" + skipped + ",\"alertsClosed\":" + alertsClosed + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processed", processed);
        data.put("skipped", skipped);
        data.put("alertsClosed", alertsClosed);
        return ok(Map.of("data", data, "message", "Bulk " + action + " complete"));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @DeleteMapping("/certificates/{domain}")
    public ResponseEntity<Map<String, Object>> deleteCertificateCheck(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        if (isTeamAdmin(session)) {
            inventoryRepo.findByDomain(domain).ifPresentOrElse(
                    inv -> requireTeamScopedAdmin(session, inv.getTeamId()),
                    () -> { throw new SecurityException("Domain not found in your team's inventory"); });
        } else {
            requireAdmin(session);
        }
        requirePerm(session, "inventory.crud", "edit");
        latestCheckRepo.deleteById(domain);
        auditService.recordAction("DOMAIN_DELETE_CHECK", session, request, "CERTIFICATE", domain, null);
        return ok(Map.of("message", "Deleted"));
    }

    @PostMapping("/inventory/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return inventoryRepo.findById(id).map(inv -> {
            requireTeamScopedAdmin(session, inv.getTeamId());
            requirePerm(session, "inventory.crud", "edit");
            inv.setDeletedAt(null);
            inv.setActive(true);
            inv.setUpdatedAt(now());
            inventoryRepo.save(inv);
            auditService.recordAction("DOMAIN_RESTORE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + "}");
            return ok(Map.of("data", inv, "message", "Restored"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Kalıcı sil (purge) — yalnız SOFT-DELETE edilmiş bir envanter kaydını ve o domain'in
     * tüm kontrol verisini (latest_checks + certificate_checks) GERİ ALINAMAZ şekilde siler.
     * Yalnız GLOBAL ADMIN ({@link #requireAdmin}); takım-admin yapamaz. Alarmlar zaten
     * soft-delete sırasında kapandığı için burada ek alarm işlemi yok.
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @DeleteMapping("/inventory/{id}/permanent")
    @Transactional
    public ResponseEntity<Map<String, Object>> purgeInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "inventory.purge", "execute"); // dedike sensitive yetki (varsayılan ADMIN-only, matristen yönetilebilir)
        return inventoryRepo.findById(id).map(inv -> {
            if (inv.getDeletedAt() == null) {
                throw new IllegalArgumentException("Yalnız önce silinmiş (soft-delete) kayıtlar kalıcı silinebilir");
            }
            String domain = inv.getDomain();
            int checks = certificateCheckRepo.deleteByDomain(domain);
            latestCheckRepo.findById(domain).ifPresent(latestCheckRepo::delete);
            inventoryRepo.delete(inv);
            auditService.recordAction("DOMAIN_PURGE", session, request, "CERTIFICATE", domain,
                    "{\"teamId\":" + inv.getTeamId() + ",\"checksDeleted\":" + checks + "}");
            return ok(Map.of("message", "Purged", "checksDeleted", checks));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toplu kalıcı sil — TÜM soft-delete edilmiş envanter kayıtlarını ve ilgili kontrol
     * verisini tek istekte GERİ ALINAMAZ şekilde temizler. Yalnız GLOBAL ADMIN.
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PostMapping("/inventory/purge-deleted")
    @Transactional
    public ResponseEntity<Map<String, Object>> purgeAllDeleted(
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "inventory.purge", "execute");
        List<CertificateInventory> deleted = inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc();
        int purged = 0, checksDeleted = 0;
        for (CertificateInventory inv : deleted) {
            String domain = inv.getDomain();
            checksDeleted += certificateCheckRepo.deleteByDomain(domain);
            latestCheckRepo.findById(domain).ifPresent(latestCheckRepo::delete);
            inventoryRepo.delete(inv);
            purged++;
        }
        auditService.recordAction("DOMAIN_BULK_PURGE", session, request, "CERTIFICATE",
                purged + " domain",
                "{\"purged\":" + purged + ",\"checksDeleted\":" + checksDeleted + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("purged", purged);
        data.put("checksDeleted", checksDeleted);
        return ok(Map.of("data", data, "message", "Purge complete"));
    }

    @PostMapping("/inventory/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transferInventory(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "inventory.transfer", "execute");
        Long newTeamId = toLong(body.get("team_id"));
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        Long oldTeamId = inv.getTeamId();
        inv.setTeamId(newTeamId);
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        auditService.recordAction("DOMAIN_TRANSFER_SY", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldTeamId + ",\"to\":" + newTeamId + "}");
        return ok(Map.of("data", inv, "message", "Transferred"));
    }

    @PostMapping("/inventory/{id}/transfer-ug")
    public ResponseEntity<Map<String, Object>> transferInventoryUg(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "inventory.transfer", "execute");
        Long newUgTeamId = toLong(body.get("ug_team_id"));
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        Long oldUgTeamId = inv.getUgTeamId();
        inv.setUgTeamId(newUgTeamId);
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        auditService.recordAction("DOMAIN_TRANSFER_UG", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldUgTeamId + ",\"to\":" + newUgTeamId + "}");
        return ok(Map.of("data", inv, "message", "UG team transferred"));
    }

    // ── Alert Thresholds (ADMIN only) ─────────────────────────────────────────

    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds(HttpSession session) {
        requirePerm(session, "thresholds.read", "view");
        return ok(Map.of("data", thresholdRepo.findAll()));
    }

    @PostMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> createThreshold(
            @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "thresholds.edit", "edit");
        t.setId(null);
        AlertThreshold saved = thresholdRepo.save(t);
        auditService.recordAction("THRESHOLD_CREATE", session, "ALERT_THRESHOLD", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(Map.of("data", saved));
    }

    @PutMapping("/thresholds/{id}")
    public ResponseEntity<Map<String, Object>> updateThreshold(
            @PathVariable Long id, @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "thresholds.edit", "edit");
        AlertThreshold existing = thresholdRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Threshold not found: " + id));
        java.util.Map<String, Object> _before = AuditDiff.snapshot(existing,
                "name", "warningDays", "highDays", "criticalDays", "reAlertIntervalHours", "active");
        existing.setName(t.getName() != null ? t.getName() : existing.getName());
        existing.setWarningDays(t.getWarningDays() != null ? t.getWarningDays() : existing.getWarningDays());
        existing.setHighDays(t.getHighDays() != null ? t.getHighDays() : existing.getHighDays());
        existing.setCriticalDays(t.getCriticalDays() != null ? t.getCriticalDays() : existing.getCriticalDays());
        existing.setReAlertIntervalHours(t.getReAlertIntervalHours() != null
                ? t.getReAlertIntervalHours() : existing.getReAlertIntervalHours());
        existing.setActive(t.getActive() != null ? t.getActive() : existing.getActive());
        AlertThreshold saved = thresholdRepo.save(existing);
        auditService.recordAction("THRESHOLD_UPDATE", session, "ALERT_THRESHOLD", String.valueOf(id), saved.getName(),
                AuditDiff.diff(_before, AuditDiff.snapshot(saved,
                        "name", "warningDays", "highDays", "criticalDays", "reAlertIntervalHours", "active")));
        return ok(Map.of("data", saved));
    }

    // ── Escalation Contacts ───────────────────────────────────────────────────

    @GetMapping("/contacts")
    public ResponseEntity<Map<String, Object>> listContacts(HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        List<EscalationContact> contacts;
        if (isAdminOrAudit(session)) {
            contacts = contactRepo.findByActiveTrueOrderByRoleAsc();
        } else {
            List<Long> scope = viewScope(session);
            contacts = (scope == null || scope.isEmpty())
                    ? List.of()
                    : contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(scope);
        }
        return ok(Map.of("data", contacts));
    }

    @GetMapping("/contacts/all")
    public ResponseEntity<Map<String, Object>> listAllContacts(HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        List<EscalationContact> contacts;
        if (isAdminOrAudit(session)) {
            contacts = contactRepo.findAll();
        } else {
            List<Long> scope = viewScope(session);
            contacts = (scope == null || scope.isEmpty())
                    ? List.of()
                    : contactRepo.findByTeamIdInOrderByRoleAsc(scope);
        }
        return ok(Map.of("data", contacts));
    }

    @PostMapping("/contacts")
    public ResponseEntity<Map<String, Object>> addContact(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "contacts.crud", "edit");
        EscalationContact contact = new EscalationContact();
        applyContactFields(contact, body, session);
        contact.setId(null);
        contact.setCreatedAt(now());
        if (contact.getActive() == null) contact.setActive(true);
        if (contact.getMinAlertLevel() == null) contact.setMinAlertLevel("WARNING");
        if (isTeamAdmin(session)) {
            contact.setTeamId(teamId(session));
        }
        if (contact.getTeamId() == null) {
            userService.listTeams().stream().findFirst().ifPresent(t -> contact.setTeamId(t.getId()));
        }
        EscalationContact saved = contactRepo.save(contact);
        auditService.recordAction("CONTACT_CREATE", session, "ESCALATION_CONTACT", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(Map.of("data", saved));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> updateContact(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        requireTeamScopedAdmin(session, existing.getTeamId());
        requirePerm(session, "contacts.crud", "edit");
        String[] cf = {"name", "email", "role", "minAlertLevel", "webhookType", "active", "teamId", "userId"};
        java.util.Map<String, Object> _before = AuditDiff.snapshot(existing, cf);
        applyContactFields(existing, body, session);
        EscalationContact saved = contactRepo.save(existing);
        auditService.recordAction("CONTACT_UPDATE", session, "ESCALATION_CONTACT", String.valueOf(id), saved.getName(),
                AuditDiff.diff(_before, AuditDiff.snapshot(saved, cf)));
        return ok(Map.of("data", saved));
    }

    private void applyContactFields(EscalationContact c, Map<String, Object> body, HttpSession session) {
        Long userId = toLong(body.get("user_id"));
        if (userId != null) {
            userRepo.findById(userId).ifPresent(u -> {
                c.setUserId(userId);
                c.setName(u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getUsername());
                c.setEmail(u.getEmail());
            });
        } else {
            String name = (String) body.get("name");
            String email = (String) body.get("email");
            if (name != null) c.setName(name);
            if (email != null) c.setEmail(email);
            c.setUserId(null);
        }
        String role = (String) body.get("role");
        if (role != null) c.setRole(role);
        String minAlertLevel = (String) body.get("min_alert_level");
        c.setMinAlertLevel(minAlertLevel != null ? minAlertLevel : (c.getMinAlertLevel() != null ? c.getMinAlertLevel() : "WARNING"));
        c.setWebhookUrl((String) body.get("webhook_url"));
        c.setWebhookType((String) body.get("webhook_type"));
        Object active = body.get("active");
        c.setActive(active instanceof Boolean ? (Boolean) active : (c.getActive() != null ? c.getActive() : true));
        if (isAdmin(session)) {
            Long teamId = toLong(body.get("team_id"));
            if (teamId != null) c.setTeamId(teamId);
        }
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> deleteContact(
            @PathVariable Long id, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        requireTeamScopedAdmin(session, existing.getTeamId());
        requirePerm(session, "contacts.crud", "edit");
        contactRepo.deleteById(id);
        auditService.recordAction("CONTACT_DELETE", session, "ESCALATION_CONTACT", String.valueOf(id), existing.getName(), null);
        return ok(Map.of("message", "Deleted"));
    }

    // ── Alert Events (any authenticated user) ─────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String resolvedSince,
            @RequestParam(required = false) String resolvedUntil,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) Long teamId,
            HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        int sz = Math.max(1, Math.min(size, 200));
        Boolean resolvedEffective = resolved != null ? resolved : (onlyOpen ? Boolean.FALSE : null);
        String alertTypeEffective = (alertType != null && !alertType.isBlank()) ? alertType.trim() : null;
        // Arama: kismi + buyuk/kucuk harf duyarsiz. Joker karakterler SORGUDA degil BURADA
        // uretilir; JPQL tarafinda CONCAT kullanmak lehce farklarina acik ve okunmasi zor.
        // Kullanicinin yazdigi % ve _ KACISLANIR, aksi halde tek bir "%" tum kayitlari getirir
        // ve arama sessizce filtresiz calisir.
        String qEffective = null;
        if (q != null && !q.isBlank()) {
            String esc = q.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            qEffective = "%" + esc + "%";
        }
        String levelEffective = (level != null && !level.isBlank()) ? level.trim().toUpperCase(java.util.Locale.ROOT) : null;
        // Default sort: en yeniden en eskiye (newest → oldest) — hem açık hem kapalı için.
        Sort sort = Boolean.TRUE.equals(resolvedEffective)
                ? Sort.by(Sort.Direction.DESC, "resolvedAt").and(Sort.by(Sort.Direction.DESC, "createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        // Takım kapsamı (IDOR engeli): global viewer (admin/AUDIT) tümünü; aksi halde alarmın takımı
        // (keyword/ping: e.teamId; cert: domain→envanter SY/UG) çağıranın görüntüleme kapsamında olmalı.
        List<Long> scope = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session);
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty()) {   // kapsamsız kullanıcı → hiçbir alarm
            return ok(Map.of("data", List.of(), "total", 0L, "page", 0, "size", sz,
                    "type_counts", Map.of()));
        }
        List<Long> scopeList = scoped ? scope : List.of(-1L);   // global'de dummy (scoped=false kısa-devre)
        Page<AlertEvent> result = alertEventRepo.findFiltered(
                resolvedEffective, since, until, resolvedSince, resolvedUntil, domain, alertTypeEffective,
                qEffective, levelEffective, acknowledged, teamId,
                scoped, scopeList, PageRequest.of(Math.max(0, page), sz, sort));
        enrichAlerts(result.getContent());
        // Tip filtre pill'lerinin canlı sayıları — tip filtresinden bağımsız
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        for (Object[] row : alertEventRepo.countFilteredByType(
                resolvedEffective, since, until, resolvedSince, resolvedUntil, domain,
                qEffective, levelEffective, acknowledged, teamId, scoped, scopeList)) {
            typeCounts.put(String.valueOf(row[0]), (Long) row[1]);
        }
        // İstatistik şeridi sayaçları: seviye kırılımı + sahiplenilmemiş toplamı. Kendi
        // boyutları (level/acknowledged) BİLEREK uygulanmaz — aksi halde "Kritik" kartına
        // basınca diğer kartlar sıfırlanır ve kullanıcı seçimden geri dönemez.
        Map<String, Long> levelCounts = new LinkedHashMap<>();
        long unackedTotal = 0L;
        for (Object[] row : alertEventRepo.countFacets(
                resolvedEffective, since, until, resolvedSince, resolvedUntil, domain, alertTypeEffective,
                qEffective, teamId, scoped, scopeList)) {
            String lvl = String.valueOf(row[0]);
            long n = (Long) row[2];
            levelCounts.merge(lvl, n, Long::sum);
            if (!Boolean.TRUE.equals(row[1])) unackedTotal += n;
        }
        // "Uzun süredir açık": bu yaştan ESKİ alarmlar unutulmuş sayılır. Karşılaştırma
        // sözlükseldir; created_at sabit genişlikte (19 karakter) ISO-UTC olduğu için güvenli —
        // mevcut since/until yüklemleri de aynı deseni kullanıyor.
        String staleBefore = ISO.format(Instant.now().minus(java.time.Duration.ofHours(ALERT_STALE_HOURS)));
        long staleTotal = alertEventRepo.countStale(resolvedEffective, staleBefore, domain,
                alertTypeEffective, qEffective, teamId, scoped, scopeList);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data",         result.getContent());
        body.put("total",        result.getTotalElements());
        body.put("page",         result.getNumber());
        body.put("size",         result.getSize());
        body.put("type_counts",  typeCounts);
        body.put("level_counts", levelCounts);
        body.put("unacked_total", unackedTotal);
        // Yalnız AÇIK sekmede anlamlı: kapalı sekmede "24 saatten eski" demek olur,
        // "24 saattir açık" değil. Arayüz kartı yalnız açık sekmede gösterir.
        body.put("stale_total", staleTotal);
        body.put("stale_hours", ALERT_STALE_HOURS);
        return ok(body);
    }

    /**
     * Bulk-populates @Transient fields on AlertEvent: SY/UG team names, tier,
     * and per-event email sent/failed counts. Single round-trip per related
     * table — no N+1.
     */
    private void enrichAlerts(List<AlertEvent> events) {
        if (events.isEmpty()) return;

        Set<String> domains = new HashSet<>();
        Set<Long> alertIds  = new HashSet<>();
        for (AlertEvent ev : events) {
            if (ev.getDomain() != null) domains.add(ev.getDomain());
            if (ev.getId() != null)     alertIds.add(ev.getId());
        }

        Map<String, CertificateInventory> invByDomain = new HashMap<>();
        Set<Long> teamIds = new HashSet<>();
        if (!domains.isEmpty()) {
            for (CertificateInventory inv : inventoryRepo.findByDomainIn(domains)) {
                invByDomain.putIfAbsent(inv.getDomain(), inv);
                if (inv.getTeamId()   != null) teamIds.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) teamIds.add(inv.getUgTeamId());
            }
        }

        Map<Long, String> teamNames = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (Team t : teamRepo.findAllById(teamIds)) {
                teamNames.put(t.getId(), t.getName());
            }
        }

        Map<Long, long[]> mailCounts = new HashMap<>();
        if (!alertIds.isEmpty()) {
            for (Object[] row : notificationLogRepo.countByAlertIds(alertIds)) {
                Long alertId = ((Number) row[0]).longValue();
                long sent    = row[1] == null ? 0 : ((Number) row[1]).longValue();
                long failed  = row[2] == null ? 0 : ((Number) row[2]).longValue();
                mailCounts.put(alertId, new long[]{ sent, failed });
            }
        }

        // Tekrar sayısı: (domain, tip) → son N gündeki alarm adedi. TEK sorgu — kart başına
        // sorgu N+1 olurdu (50 kayıtlık sayfada 50 sorgu).
        Map<String, Long> repeatCounts = new HashMap<>();
        if (!domains.isEmpty()) {
            String since = ISO.format(Instant.now().minus(java.time.Duration.ofDays(ALERT_REPEAT_WINDOW_DAYS)));
            for (Object[] row : alertEventRepo.countRecentByDomainAndType(domains, since)) {
                repeatCounts.put(row[0] + " " + row[1], (Long) row[2]);
            }
        }

        for (AlertEvent ev : events) {
            ev.setRepeatCount(repeatCounts.get(ev.getDomain() + " " + ev.getAlertType()));
            CertificateInventory inv = invByDomain.get(ev.getDomain());
            if (inv != null) {
                ev.setSyTeamName(inv.getTeamId()   != null ? teamNames.get(inv.getTeamId())   : null);
                ev.setUgTeamName(inv.getUgTeamId() != null ? teamNames.get(inv.getUgTeamId()) : null);
                ev.setCertTier(inv.getTier());
            }
            long[] counts = mailCounts.getOrDefault(ev.getId(), new long[]{0, 0});
            ev.setEmailSentCount(counts[0]);
            ev.setEmailFailedCount(counts[1]);
        }
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable Long id,
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.acknowledge(id, by);
        auditService.recordAction("ALERT_ACKNOWLEDGE", session, request,
                "ALERT_EVENT", id.toString(),
                "{\"domain\":\"" + event.getDomain() + "\"}");
        return ok(Map.of("data", event, "message", "Alert acknowledged"));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(
            @PathVariable Long id,
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.resolve(id, by);
        auditService.recordAction("ALERT_RESOLVE", session, request,
                "ALERT_EVENT", id.toString(),
                "{\"domain\":\"" + event.getDomain() + "\"}");
        return ok(Map.of("data", event, "message", "Alert resolved"));
    }

    /** "Tekrar Bildir" onay pop-up'ı için alıcı önizlemesi — gönderim/yazma YAPMAZ.
     *  Perm+scope çifti re-notify ile birebir aynı (önizleyebilen = gönderebilen). */
    @GetMapping("/alerts/{id}/re-notify/preview")
    public ResponseEntity<Map<String, Object>> previewReNotifyAlert(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        List<Map<String, Object>> recipients = escalationService.previewReNotify(id).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("email", r.email());
                    m.put("name",  r.name());
                    m.put("role",  r.role());
                    m.put("kind",  r.kind());
                    return m;
                }).toList();
        return ok(Map.of("data", Map.of("alert_id", id, "recipients", recipients)));
    }

    @PostMapping("/alerts/{id}/re-notify")
    public ResponseEntity<Map<String, Object>> reNotifyAlert(
            @PathVariable Long id, HttpSession session,
            @RequestBody(required = false) Map<String, Object> body) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        // Onay pop-up'ından gelen opsiyonel hariç-tutma listesi (kullanıcının listeden çıkardıkları).
        Set<String> excludes = new LinkedHashSet<>();
        if (body != null && body.get("excludeEmails") instanceof List<?> raw) {
            for (Object o : raw) if (o != null && !o.toString().isBlank()) excludes.add(o.toString());
        }
        return ok(Map.of("data", escalationService.reNotify(id, excludes), "message", "Notification triggered"));
    }

    /** Toplu alarm işlemi (Alarm Geçmişi çoklu seçim): acknowledge | resolve | re-notify.
     *  Kapsam-dışı (IDOR) ya da hatalı id'ler atlanır/sayılır, batch durmaz. Bkz. /inventory/bulk deseni.
     *  Not: @Transactional DEĞİL — her escalationService çağrısı kendi tx'ini + yan etkisini (mail) yönetir. */
    @PostMapping("/alerts/bulk")
    public ResponseEntity<Map<String, Object>> bulkAlertAction(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        String action = body.get("action") != null ? body.get("action").toString().trim().toLowerCase() : "";
        if (!Set.of("acknowledge", "resolve", "re-notify").contains(action)) {
            throw new IllegalArgumentException("action must be one of: acknowledge, resolve, re-notify");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) { Long id = toLong(o); if (id != null) ids.add(id); }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("No ids provided");

        String by = resolveDisplayName(session);
        int processed = 0, skipped = 0, failed = 0;
        for (Long id : ids) {
            if (!isAlertInScope(session, id)) { skipped++; continue; }   // kapsam-dışı → atla (fırlatma yok)
            try {
                switch (action) {
                    case "acknowledge" -> escalationService.acknowledge(id, by);
                    case "resolve"     -> escalationService.resolve(id, by);
                    case "re-notify"   -> escalationService.reNotify(id);
                }
                processed++;
            } catch (Exception e) {
                failed++;   // bulunamadı / zaten kapalı / bildirim hatası — say ama batch'i durdurma
                log.warn("Bulk alert '{}' failed for id={}: {}", action, id, e.getMessage());
            }
        }
        String auditAction = switch (action) {
            case "acknowledge" -> "ALERT_BULK_ACKNOWLEDGE";
            case "resolve"     -> "ALERT_BULK_RESOLVE";
            default             -> "ALERT_BULK_RENOTIFY";
        };
        auditService.recordAction(auditAction, session, request, "ALERT_EVENT",
                processed + " alert",
                "{\"processed\":" + processed + ",\"skipped\":" + skipped + ",\"failed\":" + failed + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processed", processed);
        data.put("skipped", skipped);
        data.put("failed", failed);
        return ok(Map.of("data", data, "message", "Bulk " + action + " complete"));
    }

    @GetMapping("/alerts/{id}/notifications")
    public ResponseEntity<Map<String, Object>> getAlertNotifications(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        return ok(Map.of("data", notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(id)));
    }

    // ── Alarm takım kapsamı (IDOR engeli) ─────────────────────────────────────
    /** Bir alarmın ait olabileceği takım id'leri: kendi teamId'si (keyword/ping) + cert alarmında
     *  domain→envanter (SY teamId + UG ugTeamId). cert alarmlarında teamId NULL olduğundan envanter şart. */
    private Set<Long> alertTeamIds(AlertEvent ev) {
        Set<Long> ids = new HashSet<>();
        if (ev.getTeamId() != null) ids.add(ev.getTeamId());
        if (ev.getDomain() != null) {
            inventoryRepo.findByDomain(ev.getDomain()).ifPresent(inv -> {
                if (inv.getTeamId()   != null) ids.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) ids.add(inv.getUgTeamId());
            });
        }
        return ids;
    }

    /** Alarm takıma-gizli: global viewer (admin/AUDIT) tümünü; aksi halde alarmın takım(lar)ından
     *  biri çağıranın görüntüleme kapsamında olmalı (yoksa 403). Global olmayanda alarmı yükler. */
    private void requireAlertScope(HttpSession session, Long id) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> v = SessionScope.viewTeamIds(session);
        AlertEvent ev = alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + id));
        if (v != null) for (Long t : alertTeamIds(ev)) if (v.contains(t)) return;
        throw new SecurityException("Bu alarm sizin takım(lar)ınıza ait değil");
    }

    /** requireAlertScope'un fırlatmayan sürümü — toplu işlemde kapsam-dışı/eksik id'yi atlamak için. */
    private boolean isAlertInScope(HttpSession session, Long id) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v == null) return false;
        AlertEvent ev = alertEventRepo.findById(id).orElse(null);
        if (ev == null) return false;
        for (Long t : alertTeamIds(ev)) if (v.contains(t)) return true;
        return false;
    }

    // ── Teams (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/teams")
    public ResponseEntity<Map<String, Object>> listTeams(HttpSession session) {
        requirePerm(session, "teams.list", "view");
        var all = userService.listTeams();
        if (isAdminOrAudit(session)) {
            return ok(Map.of("data", all));
        }
        List<Long> scope = viewScope(session);
        var filtered = (scope == null || scope.isEmpty())
                ? List.<com.sitemonitor.model.Team>of()
                : all.stream().filter(t -> scope.contains(t.getId())).toList();
        return ok(Map.of("data", filtered));
    }

    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        // Haftalık e-posta anahtarları burada OKUNMAZ: yeni takım her zaman ikisi de kapalı doğar
        // (createTeam açıkça false yazar), açma işi takımın kendi üyelerinde.
        Team team = userService.createTeam(
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                toLong(body.get("leader_id")));
        auditService.recordAction("TEAM_CREATE", session, request,
                "TEAM", team.getId().toString(),
                "{\"name\":\"" + team.getName() + "\",\"leaderId\":" + team.getLeaderId() + "}");
        return ok(Map.of("data", team, "message", "Team created"));
    }

    @PutMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> updateTeam(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireTeamScopedAdmin(session, id);
        requirePerm(session, "teams.update", "edit");
        Team team = userService.updateTeam(id,
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                toLong(body.get("leader_id")),
                bool(body.get("weekly_reminder_enabled")),
                bool(body.get("weekly_availability_enabled")));
        auditService.recordAction("TEAM_UPDATE", session, "TEAM", id.toString(), team.getName(), AuditDiff.diff(null, body));
        return ok(Map.of("data", team));
    }

    /**
     * Haftalık e-posta anahtarları — TAKIM ÜYELERİNE açık DAR uç. Takımın kendi üyeleri (yalnız kendi
     * takımları için) Cuma hatırlatmasını ve Pazartesi erişilebilirlik raporunu açıp kapatabilir.
     * Neden ayrı uç: {@code teams.update} izni ad/e-posta/aktiflik alanlarını da açar — sıradan üyeye
     * verilemez. Burada gövdeden BAŞKA hiçbir alan okunmaz.
     * Üyelik oturumdaki {@code viewTeamIds}'ten DEĞİL, kullanıcının gerçek üyeliklerinden (app_users)
     * doğrulanır: global görüntüleyici/AUDIT tüm takımları görür ama üyesi değildir.
     */
    @PutMapping("/teams/{id}/weekly-notifications")
    public ResponseEntity<Map<String, Object>> updateTeamWeeklyNotifications(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requirePerm(session, "teams.weekly_notifications", "edit");
        if (!isAdmin(session) && !isTeamMember(session, id))
            throw new SecurityException("Bu takımın haftalık e-posta ayarlarını değiştiremezsiniz");
        Team team = userService.updateTeamWeeklyNotifications(id,
                bool(body.get("weekly_reminder_enabled")),
                bool(body.get("weekly_availability_enabled")));
        auditService.recordAction("TEAM_WEEKLY_NOTIFICATIONS", session, "TEAM", id.toString(), team.getName(),
                AuditDiff.diff(null, body));
        return ok(Map.of("data", team));
    }

    /** Gövdeden boolean okuma — Boolean değilse null ("bu alana dokunma"). */
    private static Boolean bool(Object raw) {
        return raw instanceof Boolean b ? b : null;
    }

    /** Oturumdaki kullanıcı bu takımın GERÇEK üyesi mi (birincil takım veya çoklu üyelik)? */
    private boolean isTeamMember(HttpSession session, Long teamId) {
        Object raw = session.getAttribute("userId");
        Long userId = raw instanceof Number n ? n.longValue() : null;
        if (userId == null || teamId == null) return false;
        return userRepo.findById(userId)
                .map(u -> teamId.equals(u.getTeamId())
                        || (u.getTeamIds() != null && u.getTeamIds().contains(teamId)))
                .orElse(false);
    }

    /** A user's AD photo (JPEG) for avatars; 404 when none. Visible to admins/team-admins. */
    @GetMapping("/users/{id}/photo")
    public ResponseEntity<byte[]> userPhoto(@PathVariable Long id, HttpSession session) {
        requireAdminOrTeamAdmin(session);
        return userRepo.findById(id)
                .map(u -> AuthController.photoResponse(u.getPhotoBase64()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/teams/{id}/users")
    public ResponseEntity<Map<String, Object>> listTeamUsers(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "users.list", "view");
        // Read-only visibility: admin/audit see any team; scoped roles only teams in their view scope.
        if (!isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            if (scope == null || !scope.contains(id)) {
                throw new SecurityException("Cannot view another team's members");
            }
        }
        return ok(Map.of("data", userService.listUsers().stream()
                .filter(u -> id.equals(u.getTeamId())
                        || (u.getTeamIds() != null && u.getTeamIds().contains(id))).toList()));
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> deleteTeam(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        userService.deleteTeam(id);
        auditService.recordAction("TEAM_DELETE", session, request, "TEAM", id.toString(), null);
        return ok(Map.of("message", "Team deleted"));
    }

    // ── Users (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpSession session) {
        requirePerm(session, "users.list", "view");
        var all = userService.listUsers();
        if (isAdminOrAudit(session)) {
            return ok(Map.of("data", all));
        }
        List<Long> scope = viewScope(session);
        var filtered = (scope == null || scope.isEmpty())
                ? List.<AppUser>of()
                : all.stream().filter(u -> inScope(u, scope)).toList();
        return ok(Map.of("data", filtered));
    }

    /** Admin Users ekranı — filtreli + sayfalı liste (q + systemRole/orgRole/teamId).
     *  Düz /users (dropdown/kontak kaynağı) bozulmasın diye AYRI uç. */
    @GetMapping("/users/search")
    public ResponseEntity<Map<String, Object>> searchUsers(
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String q,
            @RequestParam(required = false)    String systemRole,
            @RequestParam(required = false)    String orgRole,
            @RequestParam(required = false)    Long   teamId,
            HttpSession session) {
        requirePerm(session, "users.list", "view");
        size = Math.min(Math.max(size, 1), 200);
        // TEAM_ADMIN yalnız kendi takımını görür → client teamId yok sayılır, kendi takımına sabitlenir
        Long effTeamId = isAdminOrAudit(session) ? teamId : teamId(session);
        if (!isAdminOrAudit(session) && effTeamId == null) {
            return ok(Map.of("data", List.of(), "total", 0L, "page", 0, "total_pages", 0,
                    "active_admin_count", userRepo.countBySystemRoleAndActiveTrue("ADMIN")));
        }
        String qParam = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        String roleParam    = (systemRole == null || systemRole.isBlank()) ? null : systemRole;
        String orgRoleParam = (orgRole == null || orgRole.isBlank()) ? null : orgRole;
        Page<AppUser> p = userRepo.findFiltered(
                qParam, roleParam, orgRoleParam, effTeamId,
                PageRequest.of(page, size));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", p.getContent());
        resp.put("total", p.getTotalElements());
        resp.put("page", p.getNumber());
        resp.put("total_pages", p.getTotalPages());
        // Sayfalamadan bağımsız "son aktif admin" guard'ı için toplam aktif admin sayısı
        resp.put("active_admin_count", userRepo.countBySystemRoleAndActiveTrue("ADMIN"));
        return ok(resp);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "users.crud", "edit");
        String requestedRole = (String) body.get("system_role");
        List<Long> requestedTeams = teamIdsFromBody(body);
        if (isTeamAdmin(session)) {
            // TEAM_ADMIN can only seed USER or TEAM_ADMIN; never ADMIN/AUDIT.
            if (requestedRole != null && !TEAM_ADMIN_ASSIGNABLE_ROLES.contains(requestedRole)) {
                throw new SecurityException("Team admin cannot assign role: " + requestedRole);
            }
            // And the new user lands in the team admin's team — payload can't override.
            Long own = teamId(session);
            requestedTeams = own != null ? List.of(own) : List.of();
        }
        AppUser user = userService.createUser(
                (String) body.get("username"),
                (String) body.get("password"),
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                requestedRole,
                requestedTeams,
                (String) body.get("org_role"));
        // AD-mirrored profil alanları (ad/soyad/ünvan/telefon/departman/seviye/müdürlük/müdür sicili)
        userService.applyProfileFields(user, body);
        user = userRepo.save(user);
        auditService.recordAction("USER_CREATE", session, request,
                "USER", user.getUsername(),
                "{\"role\":\"" + user.getSystemRole() + "\",\"teamId\":" + user.getTeamId() + "}");
        return ok(Map.of("data", user, "message", "User created"));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        final String[] _uf = {"systemRole", "teamId", "teamIds", "active", "orgRole", "displayName", "email", "employeeId", "managerId"};
        java.util.Map<String, Object> _before = AuditDiff.snapshot(target, _uf);
        String requestedRole = (String) body.get("system_role");
        // null = takımlara dokunma (kısmi güncelleme); team_ids veya team_id verilirse (boş dahil) set et.
        List<Long> requestedTeams =
                (body.containsKey("team_ids") || body.containsKey("team_id")) ? teamIdsFromBody(body) : null;
        Long selfId = userIdFromSession(session);
        if (selfId != null && selfId.equals(id)) {
            if (requestedRole != null && !requestedRole.equals(target.getSystemRole())) {
                throw new SecurityException("You cannot change your own role");
            }
            Object activePayload = body.get("active");
            if (activePayload instanceof Boolean && !((Boolean) activePayload)
                    && Boolean.TRUE.equals(target.getActive())) {
                throw new SecurityException("You cannot deactivate yourself");
            }
        }
        {
            String nextRole = requestedRole != null ? requestedRole : target.getSystemRole();
            boolean currentActive = Boolean.TRUE.equals(target.getActive());
            boolean nextActive = body.get("active") instanceof Boolean
                    ? (Boolean) body.get("active") : currentActive;
            guardLastActiveAdmin(target, "ADMIN".equals(nextRole) && nextActive);
        }
        if (isTeamAdmin(session)) {
            if (requestedRole != null && !TEAM_ADMIN_ASSIGNABLE_ROLES.contains(requestedRole)) {
                throw new SecurityException("Team admin cannot assign role: " + requestedRole);
            }
            // TEAM_ADMIN kullanıcıyı kendi takımı dışına taşıyamaz / çoklu takım atayamaz.
            if (requestedTeams != null) {
                for (Long t : requestedTeams) {
                    if (!t.equals(target.getTeamId())) {
                        throw new SecurityException("Team admin cannot transfer users to another team");
                    }
                }
            }
            requestedTeams = null;   // üyeliği değiştirmesine izin verilmez
        }
        // Boş takım = takımsız; yalnız ADMIN rollü kullanıcı takımsız kalabilir.
        if (requestedTeams != null && requestedTeams.isEmpty()) {
            String effRole = requestedRole != null ? requestedRole : target.getSystemRole();
            if (!"ADMIN".equals(effRole)) {
                throw new IllegalArgumentException("Team is required");
            }
        }
        AppUser user = userService.updateUser(id,
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                requestedRole,
                requestedTeams,
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                (String) body.get("org_role"));
        // AD-mirrored profil alanları (ad/soyad/ünvan/telefon/departman/seviye/müdürlük/müdür sicili)
        userService.applyProfileFields(user, body);
        user = userRepo.save(user);
        auditService.recordAction("USER_UPDATE", session, "USER", id.toString(), user.getUsername(),
                AuditDiff.diff(_before, AuditDiff.snapshot(user, _uf)));
        return ok(Map.of("data", user));
    }

    @PostMapping("/users/{id}/auto-reset-password")
    public ResponseEntity<Map<String, Object>> autoResetPassword(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.actions", "execute");
        String adminPwd = body.get("admin_password");
        if (adminPwd == null || adminPwd.isBlank()) {
            throw new IllegalArgumentException("Admin password required");
        }
        String adminUsername = actor(session);

        String tempPwd = userService.adminAutoResetPassword(id, adminUsername, adminPwd);

        // refresh: the auto-reset call may have updated must_change_password etc.
        target = userRepo.findById(id).orElse(target);
        String emailStatus = emailNotificationService.sendPasswordResetEmail(
                target.getEmail(), target.getUsername(), target.getDisplayName(), tempPwd);

        auditService.recordAction("USER_PASSWORD_AUTO_RESET", session, request,
                "USER", id.toString(),
                "{\"email_status\":\"" + emailStatus.replace("\"", "\\\"") + "\"}");

        return ok(Map.of(
                "message", "Temporary password generated and emailed",
                "email_status", emailStatus));
    }

    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlockUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.actions", "execute");
        userService.unlockUser(id);
        auditService.recordAction("USER_UNLOCK", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User unlocked"));
    }

    /** Rol-kilidini kaldır → kullanıcının systemRole'ü tekrar AD (LDAP) yönetimine döner. */
    @PostMapping("/users/{id}/role-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserRole(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        userService.unlockRole(id);
        auditService.recordAction("USER_ROLE_UNLOCK", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User role unlocked"));
    }

    /** Org-rol kilidini kaldır → kullanıcının org_role'ü tekrar AD (LDAP) yönetimine döner. */
    @PostMapping("/users/{id}/org-role-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserOrgRole(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        userService.unlockOrgRole(id);
        auditService.recordAction("USER_ORG_ROLE_UNLOCK", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User org role unlocked"));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        Long selfId = userIdFromSession(session);
        if (selfId != null && selfId.equals(id)) {
            throw new SecurityException("You cannot delete your own account");
        }
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        guardLastActiveAdmin(target, false);
        userService.deleteUser(id);
        auditService.recordAction("USER_DELETE", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User deleted"));
    }

    // ── Certificate Notes ──────────────────────────────────────────────────────

    private static final Set<String> NOTE_CATEGORIES =
            Set.of("NOTE", "DEPLOYMENT", "INCIDENT", "RENEWAL");
    private static final int NOTE_MAX_LENGTH = 5000;
    private static final long NOTE_EDIT_WINDOW_HOURS = 24L;

    @GetMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> getNotes(
            @PathVariable String domain, HttpSession session) {
        requirePerm(session, "notes.read", "view");
        List<CertificateNote> notes;
        if (isAdminOrAudit(session)) {
            notes = noteRepo.findByDomainOrderByCreatedAtDesc(domain);
        } else {
            List<Long> scope = viewScope(session);
            notes = (scope == null || scope.isEmpty())
                    ? List.of()
                    : noteRepo.findByDomainAndTeamIdInOrderByCreatedAtDesc(domain, scope);
        }
        return ok(Map.of("data", notes));
    }

    @PostMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> addNote(
            @PathVariable String domain,
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        String category = body.getOrDefault("category", "NOTE");
        if (!NOTE_CATEGORIES.contains(category))
            throw new IllegalArgumentException("Invalid category: " + category);

        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        String createdAt = now();

        CertificateNote note = new CertificateNote();
        note.setDomain(domain);
        note.setTeamId(isAdmin(session) ? null : teamId(session));
        note.setAuthorUsername(currentUser);
        note.setAuthorName(currentName);
        note.setNote(text.trim());
        note.setCategory(category);
        note.setCreatedAt(createdAt);
        CertificateNote saved = noteRepo.save(note);

        writeRevision(saved.getId(), 0, "CREATE", text.trim(), category,
                createdAt, currentUser, currentName, null);
        auditService.recordAction("CERT_NOTE_ADD", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\",\"category\":\"" + category + "\"}");
        return ok(Map.of("data", saved, "message", "Note added"));
    }

    @PutMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> updateNote(
            @PathVariable String domain, @PathVariable Long noteId,
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            throw new NoSuchElementException("Note has been deleted");
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("NOT_AUTHOR");

        try {
            Instant created = Instant.from(ISO.parse(note.getCreatedAt()));
            if (Instant.now().minusSeconds(NOTE_EDIT_WINDOW_HOURS * 3600L).isAfter(created))
                throw new IllegalStateException("EDIT_WINDOW_EXPIRED");
        } catch (java.time.format.DateTimeParseException ignored) { /* fallback: allow */ }

        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        // Snapshot the PRIOR body+category into a revision before mutating the note.
        String editedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeq = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        writeRevision(noteId, nextSeq, "EDIT", note.getNote(), note.getCategory(),
                editedAt, currentUser, currentName, null);

        note.setNote(text.trim());
        note.setUpdatedAt(editedAt);
        note.setUpdatedBy(currentUser);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_EDIT", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note updated"));
    }

    @DeleteMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> deleteNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            return ok(Map.of("message", "Note already deleted"));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!isAdmin(session) && !Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("Only the author or an admin can delete a note");

        String deletedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqDel = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqDel = (maxSeqDel == null ? 0 : maxSeqDel) + 1;
        writeRevision(noteId, nextSeqDel, "DELETE", null, note.getCategory(),
                deletedAt, currentUser, currentName, null);

        note.setDeletedAt(deletedAt);
        note.setDeletedBy(currentUser);
        noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_DELETE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("message", "Note deleted"));
    }

    @GetMapping("/notes/{domain}/{noteId}/revisions")
    public ResponseEntity<Map<String, Object>> getNoteRevisions(
            @PathVariable String domain, @PathVariable Long noteId, HttpSession session) {
        requirePerm(session, "notes.read", "view");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);
        return ok(Map.of("data", noteRevisionRepo.findByNoteIdOrderBySequenceNoAsc(noteId)));
    }

    @PostMapping("/notes/{domain}/{noteId}/restore")
    public ResponseEntity<Map<String, Object>> restoreNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (note.getDeletedAt() == null)
            return ok(Map.of("data", note, "message", "Note was not deleted"));

        String restoredAt = now();
        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqRest = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqRest = (maxSeqRest == null ? 0 : maxSeqRest) + 1;
        writeRevision(noteId, nextSeqRest, "RESTORE", null, note.getCategory(),
                restoredAt, currentUser, currentName, null);

        note.setDeletedAt(null);
        note.setDeletedBy(null);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_RESTORE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note restored"));
    }

    private void writeRevision(Long noteId, int sequenceNo, String eventType,
                               String body, String category,
                               String editedAt, String editedBy, String editedByName,
                               String reason) {
        CertificateNoteRevision rev = new CertificateNoteRevision();
        rev.setNoteId(noteId);
        rev.setSequenceNo(sequenceNo);
        rev.setEventType(eventType);
        rev.setBody(body);
        rev.setCategory(category);
        rev.setEditedAt(editedAt);
        rev.setEditedBy(editedBy);
        rev.setEditedByName(editedByName);
        rev.setReason(reason);
        try { noteRevisionRepo.save(rev); }
        catch (Exception e) { log.warn("Failed to persist note revision: {}", e.getMessage()); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Global (unrestricted) admin — the bootstrap/local ADMIN. A scoped müdür is NOT global. */
    private boolean isAdmin(HttpSession session) {
        return SessionScope.isGlobalAdmin(session);
    }

    /** Sees all teams (read): global admin or AUDIT — both have null view scope. */
    private boolean isAdminOrAudit(HttpSession session) {
        return SessionScope.isGlobalViewer(session);
    }

    private boolean isTeamAdmin(HttpSession session) {
        return permissionService.allows(session, "system.team_admin", "execute")
            && !permissionService.allows(session, "system.global_admin", "execute");
    }

    /** Yetki kapısı kısayolu — rolün (resource, action) iznini doğrular (403 fırlatır). Takım-scope AYRI. */
    private void requirePerm(HttpSession session, String key, String action) {
        permissionService.require(session, key, action);
    }

    /** Read scope: null = all teams; else only these (müdür: subordinates'; PO: led; USER: own). */
    private List<Long> viewScope(HttpSession session) {
        return SessionScope.viewTeamIds(session);
    }

    /** Global admin, or a manager-scope (PO) that includes the resource's team. (Müdür → false.) */
    private boolean canManageTeamResource(HttpSession session, Long resourceTeamId) {
        if (isAdmin(session)) return true;                 // global admin
        return SessionScope.canManage(session, resourceTeamId);
    }

    private void requireTeamScopedAdmin(HttpSession session, Long resourceTeamId) {
        if (!canManageTeamResource(session, resourceTeamId)) {
            log.warn("Cross-team or non-admin write attempt by user={} resourceTeam={}",
                    actor(session), resourceTeamId);
            throw new SecurityException("Access denied: not allowed to modify this team's resource");
        }
    }

    private void requireAdminOrTeamAdmin(HttpSession session) {
        if (isAdmin(session)) return;
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null && !m.isEmpty()) return;             // PO with managed teams
        log.warn("Non-admin/team-admin write attempt by user={}", actor(session));
        throw new SecurityException("Admin or team-admin required");
    }

    private static final Set<String> TEAM_ADMIN_ASSIGNABLE_ROLES =
            Set.of("USER", "TEAM_ADMIN");

    private void requireAdmin(HttpSession session) {
        if (!isAdmin(session)) {
            log.warn("Unauthorized admin access attempt by user={}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    /** Tanılama (diagnostics) admin'e her domain için, diğer rollere YALNIZ envanterde
     *  kayıtlı (izlenen) domainler için açıktır — rastgele host+port probe'u (SSRF) engellenir. */
    private void requireAdminOrMonitoredDomain(HttpSession session, String domain) {
        if (isAdminOrAudit(session)) return;
        if (domain != null && inventoryRepo.findByDomain(domain.trim()).isPresent()) return;
        log.warn("Diagnostics denied (non-admin, unmonitored domain='{}') user={}", domain, actor(session));
        throw new SecurityException("Bu domain için tanılama yetkiniz yok");
    }

    private String resolveDisplayName(HttpSession session) {
        String dn = (String) session.getAttribute("displayName");
        return (dn != null && !dn.isBlank()) ? dn : (String) session.getAttribute("username");
    }

    /** Username extracted from session — used in audit log entries. */
    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private Long teamId(HttpSession session) {
        Object raw = session.getAttribute("teamId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    private Long userIdFromSession(HttpSession session) {
        Object raw = session.getAttribute("userId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    /**
     * Refuses to push the system below 1 active ADMIN. Caller passes the post-mutation
     * status of {@code target}; if it's no longer an active admin and there are not
     * enough other active admins to cover, a SecurityException is thrown.
     */
    private void guardLastActiveAdmin(AppUser target, boolean willRemainAdminAndActive) {
        if (willRemainAdminAndActive) return;
        if (!"ADMIN".equals(target.getSystemRole())) return;
        if (!Boolean.TRUE.equals(target.getActive())) return;
        long activeAdmins = userRepo.countBySystemRoleAndActiveTrue("ADMIN");
        if (activeAdmins <= 1) {
            log.warn("Refused to remove last active ADMIN (target user id={})", target.getId());
            throw new SecurityException("Cannot remove the last active ADMIN from the system");
        }
    }

    private void checkOwnership(Long resourceTeamId, HttpSession session) {
        Long userTeamId = teamId(session);
        if (!Objects.equals(resourceTeamId, userTeamId)) {
            throw new SecurityException("Access denied: resource belongs to a different team");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    /** Girdiyi önce normalize eder (URL yapıştırılabilsin: şema/path/port/userinfo soyulur —
     *  https://www.wingscard.com.tr/ → www.wingscard.com.tr), sonra host formatını doğrular.
     *  Subdomain KORUNUR (host-düzeyi diagnostics için); registrable'a indirgeme (PSL) yalnız
     *  domain-expiry akışının kendi içinde yapılır. Normalize edilmiş host döner. */
    private static String validateDomain(String domain) {
        if (domain == null || domain.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        String host = com.sitemonitor.service.PublicSuffixService.extractHost(domain);
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        if (host.length() > 253)
            throw new IllegalArgumentException("Domain name too long");
        if (!host.matches("^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$")
                && !host.matches("^[a-zA-Z0-9\\-]{1,63}$")) {
            throw new IllegalArgumentException("Invalid domain format: " + domain);
        }
        return host;
    }

    /** Tanılama hedefi doğrulaması (bağlanan uçlar için): validateDomain + SSRF (SsrfGuard). Çözülen IP
     *  cloud-metadata/loopback/link-local ise (localhost/tek-etiket dahil) reddedilir; iç/site-local host'lar
     *  allow-internal-targets (vars. açık) ile izinli kalır → iç Akbank host'ları tanılanabilir. */
    private String validateDiagTarget(String domain) {
        String host = validateDomain(domain);
        try {
            ssrfGuard.validate(host);
        } catch (SsrfGuard.BlockedException be) {
            throw new IllegalArgumentException("İzin verilmeyen tanılama hedefi: " + be.getMessage());
        }
        return host;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    /** Body'den çoklu takım listesi: önce `team_ids` (dizi), yoksa tek `team_id` → [id] (geriye-uyum).
     *  Sıra korunur (birincil = ilk). İçerik yoksa boş liste döner. */
    private List<Long> teamIdsFromBody(Map<String, Object> body) {
        List<Long> out = new ArrayList<>();
        if (body.get("team_ids") instanceof List<?> list) {
            for (Object o : list) { Long v = toLong(o); if (v != null && !out.contains(v)) out.add(v); }
            return out;
        }
        Long single = toLong(body.get("team_id"));
        if (single != null) out.add(single);
        return out;
    }

    /** Kullanıcı, verilen görünür-takım scope'undaki herhangi bir takıma üye mi (birincil veya ek). */
    private boolean inScope(AppUser u, List<Long> scope) {
        if (u.getTeamId() != null && scope.contains(u.getTeamId())) return true;
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (scope.contains(t)) return true;
        return false;
    }
}
