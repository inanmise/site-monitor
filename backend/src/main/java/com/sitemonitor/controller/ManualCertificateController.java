package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.manualcert.CertificateFileParser;
import com.sitemonitor.service.manualcert.ExtractedUpload;
import com.sitemonitor.service.manualcert.ManualCertificateAnalyzer;
import com.sitemonitor.service.manualcert.ManualCertificateEvaluationService;
import com.sitemonitor.service.manualcert.ManualCertificateHierarchy;
import com.sitemonitor.service.manualcert.ManualCertificateKeys;
import com.sitemonitor.service.manualcert.ManualCertificateService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.ConstraintViolation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elle yüklenen sertifikalar (2026-10-06, kullanıcı isteği) — {@code /api/manual-certs}.
 *
 * <p>Ağ üzerinden erişilemeyen sertifikalar (CSR → dış tedarikçi → .pem / PFX / JKS / OCP truststore …) DOSYADAN
 * yüklenir ve süreleri ağ sertifikalarıyla AYNI kurallarla izlenir. Kayıt bir envanter satırıdır
 * ({@code cert_source = MANUAL}); sürümler {@code manual_certificate_versions}'ta tutulur.
 *
 * <p><b>Kapılar</b> envanterle aynıdır: okuma {@code inventory.list/view} + takım görüş kapsamı (org geneli görünürlük
 * dahil), yazma {@code inventory.crud/edit} + {@code SessionScope.canWriteInventory}. Oluşturma, ağ kaydı eklemenin
 * kapılarından geçer ({@link AdminController#createInventoryRecord} — takım, grup + etiket, yazma kapsamı, mass
 * assignment, damga).
 *
 * <p><b>Gizlilik (2026-10-08, kullanıcı isteği: "keystore yüklemesinde özel anahtar için kesinlikle bir yükleme
 * yapmayalım"):</b> arayüz dosyayı TARAYICIDA açar ve yükleme uçlarına (analiz, oluştur, toplu, yeni sürüm) yalnız
 * {@code extracted} gönderir — açık sertifikalar (Base64 DER) + CSR PEM + sayaçlar ({@link ExtractedUpload}, sıkı
 * doğrulama). Sunucu parola ALMAZ ({@code password} alanı bağlanmaz, yok sayılır) ve anahtar deposu açmaz. Ham yol
 * ({@code file} / {@code text}, API istemcileri) yalnız anahtarsız içeriği kabul eder; PKCS#12, JKS / JCEKS / BKS ya da
 * herhangi bir özel anahtar (ZIP içinde de) 400 {@code PRIVATE_KEY_NOT_ACCEPTED}. İstek gövdesi TRACE logunda bile
 * yazılmaz ({@code RequestLoggingFilter.BODY_NEVER_LOGGED}).
 */
@Slf4j
@RestController
@RequestMapping("/api/manual-certs")
public class ManualCertificateController {

    /** Toplu oluşturmada en çok kayıt (sözleşme). */
    static final int BATCH_MAX = 20;
    /** Kullanıcı başına dakikada en çok analiz / yazma (ayrı kovalar). */
    static final int RATE_PER_MIN = 30;
    private static final long RATE_WINDOW_MS = 60_000L;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ManualCertificateAnalyzer analyzer;
    private final ManualCertificateService manualService;
    private final ManualCertificateEvaluationService evaluation;
    private final ManualCertificateVersionRepository versionRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MonitorHistoryService monitorHistory;
    private final UserService userService;
    private final AdminController adminController;
    private final CertificateService certService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    /** Org geneli envanter görünürlüğü — isteğe bağlı (yoksa yalnız kendi kapsamı). */
    @Autowired(required = false)
    private InventoryVisibility inventoryVisibility;

    /** Bean Validation — envanter yükünün alan kuralları (port/tier/anahtar). İsteğe bağlı. */
    @Autowired(required = false)
    private jakarta.validation.Validator validator;

    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    public ManualCertificateController(ManualCertificateAnalyzer analyzer, ManualCertificateService manualService,
                                       ManualCertificateEvaluationService evaluation,
                                       ManualCertificateVersionRepository versionRepo,
                                       CertificateInventoryRepository inventoryRepo,
                                       PermissionService permissionService, AuditService auditService,
                                       MonitorHistoryService monitorHistory, UserService userService,
                                       AdminController adminController, CertificateService certService,
                                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.analyzer = analyzer;
        this.manualService = manualService;
        this.evaluation = evaluation;
        this.versionRepo = versionRepo;
        this.inventoryRepo = inventoryRepo;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.monitorHistory = monitorHistory;
        this.userService = userService;
        this.adminController = adminController;
        this.certService = certService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── Analiz ───────────────────────────────────────────────────────────────

    /** Dosyayı çözümler — HİÇBİR ŞEY YAZMAZ. Kullanıcı başına dakikada {@value #RATE_PER_MIN}. */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        ResponseEntity<Map<String, Object>> limited = rateLimit("a:" + actor(session));
        if (limited != null) return limited;
        Map<String, String> errors = new LinkedHashMap<>();
        Upload up = readUpload(file, text, request, errors);
        if (up == null) return badRequest(errors, null);
        ManualCertificateAnalyzer.Analysis a;
        try {
            a = analyzeUpload(up);
        } catch (ParseFailure pf) {
            return pf.response;
        }
        // Girdi başına SSL sekmesiyle AYNI biçimli çevrim-dışı sonuç (2026-10-07): İnceleme adımı zinciri ağ sertifikasının
        // zincir görünümüyle çizer. Ağa çıkmaz, yazmaz.
        return ok(a.toJson(e -> evaluation.previewChain(
                e.suggestedKey() != null ? e.suggestedKey() : e.cn(), e.cert, e.chain)));
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────

    /** Tek kayıt oluşturur: yükleme + {@code ref} + {@code domain} (takip adı) + {@code inventory} (JSON) + {@code note}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> create(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "ref", required = false) String ref,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "inventory", required = false) String inventory,
            @RequestParam(value = "note", required = false) String note,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        ResponseEntity<Map<String, Object>> limited = rateLimit("w:" + actor(session));
        if (limited != null) return limited;
        Map<String, String> errors = new LinkedHashMap<>();
        Upload up = readUpload(file, text, request, errors);
        checkNote(note, errors);
        CertificateInventory item = parseInventory(inventory, errors);
        String key = validateKey(domain, "domain", errors);
        if (up == null) return badRequest(errors, null);
        ManualCertificateAnalyzer.Analysis a;
        try {
            a = analyzeUpload(up);
        } catch (ParseFailure pf) {
            return pf.response;
        }
        ManualCertificateAnalyzer.Entry entry = requireEntry(a, ref, "ref", errors);
        if (!errors.isEmpty()) return badRequest(errors, a);
        prepareManualItem(item, key, errors, "");
        if (!errors.isEmpty()) return badRequest(errors, a);

        if (entry.alreadyTracked() != null) return alreadyTracked(entry);
        ResponseEntity<Map<String, Object>> clash = keyClash(key);
        if (clash != null) return clash;

        Created c;
        try {
            c = tx.execute(status -> createOne(item, entry, a, note, session));
        } catch (GlobalExceptionHandler.DomainExistsException de) {
            return keyExists(key);
        } catch (IllegalArgumentException iae) {
            return badRequest(Map.of(), a, iae.getMessage());
        }
        afterCreate(List.of(c), a, session, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inventory_id", c.inventory().getId());
        data.put("domain", c.inventory().getDomain());
        data.put("version", 1);
        return ok(data);
    }

    /**
     * Toplu oluşturma (dosyada birden çok BAĞIMSIZ zincir başı — ör. ilgisiz kökleri taşıyan truststore):
     * {@code items=[{ref, domain}]} (≤ 20), ortak {@code inventory}. Hep ya da hiç. Bir zincirin ara / kök üyesi ayrı
     * kalem olamaz ({@code items[i].ref} hatası).
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createBatch(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "items", required = false) String itemsJson,
            @RequestParam(value = "inventory", required = false) String inventory,
            @RequestParam(value = "note", required = false) String note,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        ResponseEntity<Map<String, Object>> limited = rateLimit("w:" + actor(session));
        if (limited != null) return limited;
        Map<String, String> errors = new LinkedHashMap<>();
        Upload up = readUpload(file, text, request, errors);
        checkNote(note, errors);
        parseInventory(inventory, errors);   // yalnız doğrulama — her kalem kendi kopyasını ayrıştırır
        List<String[]> items = parseItems(itemsJson, errors);
        if (up == null) return badRequest(errors, null);
        if (!errors.isEmpty()) return badRequest(errors, null);
        ManualCertificateAnalyzer.Analysis a;
        try {
            a = analyzeUpload(up);
        } catch (ParseFailure pf) {
            return pf.response;
        }
        if (a.entries.isEmpty()) {
            requireEntry(a, null, "file", errors);
            return badRequest(errors, a);
        }
        List<CertificateInventory> invs = new ArrayList<>(items.size());
        List<ManualCertificateAnalyzer.Entry> entries = new ArrayList<>(items.size());
        Set<String> seenRefs = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String prefix = "items[" + i + "].";
            String[] it = items.get(i);
            ManualCertificateAnalyzer.Entry entry = a.find(it[0]);
            if (entry == null) errors.put(prefix + "ref", refError(a, it[0]));
            else if (!seenRefs.add(entry.ref)) errors.put(prefix + "ref", Msg.t("Aynı sertifika iki kez seçildi.",
                    "The same certificate was selected twice."));
            String key = validateKey(it[1], prefix + "domain", errors);
            if (key != null && !seenKeys.add(key)) errors.put(prefix + "domain", Msg.t("Takip adı listede tekrarlanıyor.",
                    "The tracking name is repeated in the list."));
            CertificateInventory item = parseInventory(inventory, errors);
            if (item != null && key != null) prepareManualItem(item, key, errors, prefix);
            invs.add(item);
            entries.add(entry);
        }
        if (!errors.isEmpty()) return badRequest(errors, a);
        for (int i = 0; i < items.size(); i++) {
            if (entries.get(i).alreadyTracked() != null) return alreadyTracked(entries.get(i));
            ResponseEntity<Map<String, Object>> clash = keyClash(invs.get(i).getDomain());
            if (clash != null) return clash;
        }
        List<Created> created;
        try {
            created = tx.execute(status -> {
                List<Created> out = new ArrayList<>(invs.size());
                for (int i = 0; i < invs.size(); i++) out.add(createOne(invs.get(i), entries.get(i), a, note, session));
                return out;
            });
        } catch (GlobalExceptionHandler.DomainExistsException de) {
            return keyExists(de.getExisting() != null ? String.valueOf(de.getExisting().get("domain")) : null);
        } catch (IllegalArgumentException iae) {
            return badRequest(Map.of(), a, iae.getMessage());
        }
        afterCreate(created, a, session, request);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Created c : created) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inventory_id", c.inventory().getId());
            m.put("domain", c.inventory().getDomain());
            list.add(m);
        }
        return ok(Map.of("created", list));
    }

    private record Created(CertificateInventory inventory, ManualCertificateVersion version) { }

    /** İşlem İÇİNDE: envanter satırı (ağ eklemesiyle aynı kapılar) + ilk sürüm. */
    private Created createOne(CertificateInventory item, ManualCertificateAnalyzer.Entry entry,
                              ManualCertificateAnalyzer.Analysis a, String note, HttpSession session) {
        CertificateInventory saved = adminController.createInventoryRecord(item, session);
        ManualCertificateVersion v = manualService.saveFirstVersion(manualService.buildVersion(saved.getId(), 1, entry,
                a.fileName(), a.format(), actor(session), displayName(session), note));
        return new Created(saved, v);
    }

    /** İşlemden SONRA: denetim, geçmiş, çevrim-dışı değerlendirme (yalnız kapanış), önbellek. */
    private void afterCreate(List<Created> created, ManualCertificateAnalyzer.Analysis a, HttpSession session,
                             HttpServletRequest request) {
        for (Created c : created) {
            CertificateInventory inv = c.inventory();
            auditService.recordAction("CERT_MANUAL_UPLOAD", session, request, "CERTIFICATE", inv.getDomain(),
                    AuditDetail.of("domain", inv.getDomain(), "inventory_id", inv.getId(), "team_id", inv.getTeamId(),
                            "version", 1, "fingerprint", c.version().getFingerprint(), "file_format", a.format()));
            adminController.recordInventoryCreateHistory(inv, session);
            try {
                evaluation.evaluateNow(inv, "manual");
            } catch (Exception e) {
                log.warn("Manuel sertifika ilk değerlendirmesi başarısız {}: {}", inv.getDomain(), e.toString());
            }
        }
        certService.evictAllCaches();
    }

    // ── Yenileme (yeni sürüm) ────────────────────────────────────────────────

    @PostMapping(value = "/{inventoryId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> renew(
            @PathVariable Long inventoryId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "ref", required = false) String ref,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "confirm", required = false, defaultValue = "false") boolean confirm,
            // "Yine de yükle" (2026-10-07): güncel sürümle AYNI sertifika yeni sürüm olarak kaydedilir (yoksa 409 SAME_CERTIFICATE)
            @RequestParam(value = "allow_same", required = false, defaultValue = "false") boolean allowSame,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || inv.getDeletedAt() != null || !canRead(session, inv)) return notFound();
        if (!SessionScope.canWriteInventory(session, inv.getTeamId())) {
            throw new SecurityException(Msg.t("Bu kaydın takımında yazma yetkiniz yok; başka bir takımın kaydını yalnız "
                            + "görüntüleyebilirsiniz. Kaydın takımından birine ya da takım yöneticinize başvurun.",
                    "You don't have write access to this record's team; another team's record can only be viewed. "
                            + "Ask someone in the record's team or your team manager."));
        }
        ResponseEntity<Map<String, Object>> limited = rateLimit("w:" + actor(session));
        if (limited != null) return limited;
        Map<String, String> errors = new LinkedHashMap<>();
        Upload up = readUpload(file, text, request, errors);
        checkNote(note, errors);
        if (up == null) return badRequest(errors, null);
        ManualCertificateAnalyzer.Analysis a;
        try {
            a = analyzeUpload(up);
        } catch (ParseFailure pf) {
            return pf.response;
        }
        ManualCertificateAnalyzer.Entry entry = requireEntry(a, ref, "ref", errors);
        if (!errors.isEmpty()) return badRequest(errors, a);

        CertificateInventory before = copyForHistory(inv);
        ManualCertificateService.RenewOutcome outcome;
        try {
            outcome = tx.execute(status -> {
                ManualCertificateService.RenewOutcome o = manualService.renew(inv, entry, a.fileName(), a.format(),
                        actor(session), displayName(session), note, confirm, allowSame);
                inv.setUpdatedAt(ISO.format(Instant.now()));
                monitorHistory.stampUpdated(inv, session);
                inventoryRepo.save(inv);
                return o;
            });
        } catch (ManualCertificateService.RenewRejected rr) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", rr.code());
            body.put("error", rr.getMessage());
            if (rr.extra() != null) rr.extra().forEach(body::putIfAbsent);
            return ResponseEntity.status(409).body(body);
        }
        int previous = outcome.previous() != null ? outcome.previous().getVersion() : 0;
        boolean same = outcome.sameCertificate();
        auditService.recordAction("CERT_MANUAL_RENEW", session, request, "CERTIFICATE", inv.getDomain(),
                same
                        ? AuditDetail.of("domain", inv.getDomain(), "inventory_id", inv.getId(), "version", outcome.created().getVersion(),
                                "previous_version", previous, "fingerprint", outcome.created().getFingerprint(),
                                "file_format", a.format(), "same_certificate", true)
                        : AuditDetail.of("domain", inv.getDomain(), "inventory_id", inv.getId(), "version", outcome.created().getVersion(),
                                "previous_version", previous, "fingerprint", outcome.created().getFingerprint(),
                                "file_format", a.format()));
        adminController.recordInventoryUpdateHistory(before, inv,
                (same ? "Aynı sertifika yeni sürüm olarak yeniden yüklendi: v" : "Yeni sertifika sürümü yüklendi: v")
                        + outcome.created().getVersion()
                        + (previous > 0 ? " (önceki v" + previous + ")" : ""), session);
        try {
            evaluation.evaluateNow(inv, "manual");
            manualService.acknowledgeRenewal(inv.getDomain(), outcome.created().getFingerprint(), actor(session));
        } catch (Exception e) {
            log.warn("Manuel sertifika yenileme değerlendirmesi başarısız {}: {}", inv.getDomain(), e.toString());
        }
        certService.evictAllCaches();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", outcome.created().getVersion());
        data.put("previous_version", previous > 0 ? previous : null);
        data.put("same_certificate", same);
        data.put("warnings", outcome.warnings().stream().map(CertificateFileParser.Warning::toJson).toList());
        return ok(data);
    }

    // ── Eski sürümü kalıcı silme (2026-10-07, kullanıcı isteği) ──────────────

    /**
     * GÜNCEL OLMAYAN bir sürümü kalıcı siler. Kapı yenilemeyle aynı ({@code inventory.crud/edit} + takımın yazma kapsamı).
     * 404: sürüm bu kaydın değil / kayıt manuel değil; 409 {@code CURRENT_VERSION}: güncel sürüm (takibi bırakmak için kayıt
     * silinir). Kalan sürüm numaraları değişmez; değerlendirme geçmişi kalır. Denetim {@code CERT_MANUAL_VERSION_DELETE}.
     */
    @DeleteMapping("/{inventoryId}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> deleteVersion(@PathVariable Long inventoryId, @PathVariable Long versionId,
                                                             HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || inv.getDeletedAt() != null || !canRead(session, inv)) return notFound();
        if (!SessionScope.canWriteInventory(session, inv.getTeamId())) {
            throw new SecurityException(Msg.t("Bu kaydın takımında yazma yetkiniz yok; başka bir takımın kaydını yalnız "
                            + "görüntüleyebilirsiniz. Kaydın takımından birine ya da takım yöneticinize başvurun.",
                    "You don't have write access to this record's team; another team's record can only be viewed. "
                            + "Ask someone in the record's team or your team manager."));
        }
        ResponseEntity<Map<String, Object>> limited = rateLimit("w:" + actor(session));
        if (limited != null) return limited;
        CertificateInventory before = copyForHistory(inv);
        ManualCertificateVersion deleted;
        try {
            deleted = tx.execute(status -> {
                ManualCertificateVersion d = manualService.deleteVersion(inv, versionId);
                inv.setUpdatedAt(ISO.format(Instant.now()));
                monitorHistory.stampUpdated(inv, session);
                inventoryRepo.save(inv);
                return d;
            });
        } catch (ManualCertificateService.VersionDeleteRejected r) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", r.code());
            body.put("error", r.getMessage());
            return ResponseEntity.status(r.status()).body(body);
        }
        auditService.recordAction("CERT_MANUAL_VERSION_DELETE", session, request, "CERTIFICATE", inv.getDomain(),
                AuditDetail.of("domain", inv.getDomain(), "inventory_id", inv.getId(), "version", deleted.getVersion(),
                        "fingerprint", deleted.getFingerprint()));
        adminController.recordInventoryUpdateHistory(before, inv,
                "Eski sertifika sürümü kalıcı olarak silindi: v" + deleted.getVersion(), session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted_version", deleted.getVersion());
        data.put("versions_count", manualService.versionCount(inv.getId()));
        return ok(data);
    }

    // ── Okuma ────────────────────────────────────────────────────────────────

    /** Görüş kapsamındaki manuel sertifikalar ({@code scope=all}: org geneli görünürlük — envanter listesiyle aynı kural). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "mine") String scope, HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        List<CertificateInventory> all = inventoryRepo.findByCertSourceAndDeletedAtIsNullOrderByDomainAsc(
                CertificateInventory.SOURCE_MANUAL);
        boolean globalViewer = SessionScope.isGlobalViewer(session);
        boolean orgWide = !globalViewer && inventoryVisibility != null && inventoryVisibility.wantsAll(session, scope);
        List<Long> view = SessionScope.viewTeamIds(session);
        List<CertificateInventory> rows = new ArrayList<>();
        for (CertificateInventory inv : all) {
            if (!inv.isManual()) continue;
            if (globalViewer || orgWide || (view != null && inv.getTeamId() != null && view.contains(inv.getTeamId()))) {
                rows.add(inv);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", manualService.listRows(rows, SessionScope.inventoryWriteTest(session), teamNames()));
        body.put("scope", orgWide || globalViewer && "all".equalsIgnoreCase(scope == null ? "" : scope.trim()) ? "all" : "mine");
        body.put("visible_to_all", inventoryVisibility != null && inventoryVisibility.enabledFor(session));
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{inventoryId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long inventoryId, HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || !canRead(session, inv)) return notFound();
        return ok(manualService.detail(inv, SessionScope.canWriteInventory(session, inv.getTeamId()), teamNames()));
    }

    /** Sürümün AÇIK zinciri (PEM) — {@code <takip-adı>-v<sürüm>.pem}. Özel anahtar hiçbir zaman saklanmaz. */
    @GetMapping("/{inventoryId}/versions/{versionId}/pem")
    public ResponseEntity<?> downloadPem(@PathVariable Long inventoryId, @PathVariable Long versionId, HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || !canRead(session, inv)) return notFound();
        ManualCertificateVersion v = versionRepo.findById(versionId).orElse(null);
        if (v == null || !inventoryId.equals(v.getInventoryId()) || v.getChainPem() == null) return notFound();
        String name = pemFileName(inv.getDomain(), v.getVersion());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/x-pem-file"))
                .body(v.getChainPem().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Sürümün sertifika HİYERARŞİSİ (2026-10-07, kullanıcı isteği: tarayıcıdaki gibi kök → ara → yaprak alt alta) —
     * saklanan açık zincirden ÇEVRİM-DIŞI kurulur ({@link ManualCertificateHierarchy}): ağ yok, yazma yok, alarm yok.
     * Kapı PEM indirme / ayrıntıyla aynı ({@code inventory.list/view} + görüş kapsamı); başka kaydın sürümü, manuel olmayan
     * kayıt ya da kapsam dışı takım 404. Saklanan zincir okunamazsa 422 {@code CHAIN_UNREADABLE}.
     */
    @GetMapping("/{inventoryId}/versions/{versionId}/chain")
    public ResponseEntity<Map<String, Object>> versionChain(@PathVariable Long inventoryId, @PathVariable Long versionId,
                                                            HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || !canRead(session, inv)) return notFound();
        ManualCertificateVersion v = versionRepo.findById(versionId).orElse(null);
        if (v == null || !inventoryId.equals(v.getInventoryId()) || v.getChainPem() == null) return notFound();
        List<java.security.cert.X509Certificate> chain = CertificateFileParser.readPemChain(v.getChainPem());
        if (chain.isEmpty()) {
            return ResponseEntity.status(422).body(Map.of("success", false, "code", "CHAIN_UNREADABLE",
                    "error", Msg.t("Bu sürümün saklanan sertifika zinciri okunamadı.",
                            "The stored certificate chain of this version couldn't be read.")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inventory_id", inv.getId());
        data.put("domain", inv.getDomain());
        data.put("version_id", v.getId());
        data.put("version", v.getVersion());
        data.put("current", Boolean.TRUE.equals(v.getCurrent()));
        data.putAll(ManualCertificateHierarchy.view(chain, Instant.now()));
        return ok(data);
    }

    /** Şimdi yeniden değerlendir — ağsız; yalnız kapanış uzlaştırması (yeni alarm açmaz). Yanıt {@code /check/{domain}} biçimi. */
    @PostMapping("/{inventoryId}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable Long inventoryId, HttpSession session,
                                                        HttpServletRequest request) {
        permissionService.require(session, "inventory.list", "view");
        CertificateInventory inv = loadManual(inventoryId);
        if (inv == null || inv.getDeletedAt() != null || !canRead(session, inv)) return notFound();
        if (!SessionScope.canOperateTeam(session, inv.getTeamId())) {
            throw new SecurityException(Msg.t("Bu kaydın takımında işlem yetkiniz yok; yalnız görüntüleyebilirsiniz.",
                    "You can't act on this record's team; you can only view it."));
        }
        Map<String, Object> result = evaluation.evaluateNow(inv, "manual");
        if (result == null) {
            return ResponseEntity.status(409).body(Map.of("success", false, "code", "MANUAL_CERT",
                    "error", Msg.t("Geçerli sürüm değerlendirilemedi.", "The current version could not be evaluated.")));
        }
        auditService.recordAction("CERT_HEALTH_REFRESH", session, request, "CERTIFICATE", inv.getDomain(),
                AuditDetail.of("domain", inv.getDomain(), "source", "MANUAL", "trigger", "evaluate"));
        Map<String, Object> data = new LinkedHashMap<>(result);
        data.put("port", inv.getPort() != null ? inv.getPort() : 443);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    // ── Yükleme / analiz yardımcıları ────────────────────────────────────────

    /**
     * Yükleme: ya tarayıcıda ayıklanmış AÇIK sertifikalar ({@code extracted} — arayüzün tek yolu) ya da ham dosya / metin
     * (API istemcileri; yalnız anahtarsız içerik). {@code invalid}: {@code extracted} sözleşmeye uymadı (400).
     */
    private record Upload(byte[] bytes, String name, boolean pasted, CertificateFileParser.Result extracted, String invalid) { }

    /**
     * {@code extracted} (dosya parçası ya da form alanı) YA DA dosya / yapıştırılan metin — biri zorunlu, ikisi birden
     * olmaz. Hatada null + {@code errors.file}. Parola alanı yoktur (gelirse bağlanmaz). {@code extracted} istekten
     * okunur (aynı adla iki farklı tipte {@code @RequestParam} bağlamak kırılgan: Spring dosya parçasını metin parametresine
     * de verir).
     */
    private Upload readUpload(MultipartFile file, String text, HttpServletRequest request, Map<String, String> errors) {
        MultipartHttpServletRequest mp = request == null ? null
                : WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        MultipartFile extractedPart = mp != null ? mp.getFile("extracted") : null;
        String extractedField = request != null ? request.getParameter("extracted") : null;
        boolean hasExtracted = (extractedPart != null && !extractedPart.isEmpty())
                || (extractedField != null && !extractedField.isBlank());
        boolean hasRaw = (file != null && !file.isEmpty()) || (text != null && !text.isBlank());
        if (hasExtracted && hasRaw) {
            errors.put("file", Msg.t("Ya ayıklanmış sertifikaları ya da dosyayı / metni gönderin; ikisi birlikte gönderilemez.",
                    "Send either the extracted certificates or the file / text, not both."));
            return null;
        }
        if (hasExtracted) {
            byte[] json;
            try {
                if (extractedPart != null && !extractedPart.isEmpty()) {
                    if (extractedPart.getSize() > ExtractedUpload.MAX_JSON_BYTES) {
                        return new Upload(null, null, false, null, Msg.t("Gönderilen sertifika bilgisi çok büyük (en çok "
                                        + (ExtractedUpload.MAX_JSON_BYTES / (1024 * 1024)) + " MB). Daha az sertifika içeren bir "
                                        + "dosya yükleyin ya da büyük bir truststore'u birkaç dosyaya bölün.",
                                "The submitted certificate data is too large (at most " + (ExtractedUpload.MAX_JSON_BYTES / (1024 * 1024))
                                        + " MB). Upload a file with fewer certificates or split a large truststore into several files."));
                    }
                    json = extractedPart.getBytes();
                } else {
                    json = extractedField.getBytes(StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                errors.put("file", UNREADABLE_UPLOAD.get());
                return null;
            }
            try {
                return new Upload(null, null, false, ExtractedUpload.parse(json, objectMapper), null);
            } catch (ExtractedUpload.Invalid inv) {
                return new Upload(null, null, false, null, inv.getMessage());
            }
        }
        try {
            if (file != null && !file.isEmpty()) return new Upload(file.getBytes(), file.getOriginalFilename(), false, null, null);
        } catch (Exception e) {
            errors.put("file", UNREADABLE_UPLOAD.get());
            return null;
        }
        if (text != null && !text.isBlank()) return new Upload(text.getBytes(StandardCharsets.UTF_8), null, true, null, null);
        errors.put("file", Msg.t("Bir sertifika dosyası seçin ya da sertifika metnini yapıştırın.",
                "Choose a certificate file or paste the certificate text."));
        return null;
    }

    /** Yükleme gövdesi sunucuda okunamadı (aktarım yarıda kesildi …) — ne oldu + ne yapılmalı (2026-10-08). */
    private static final java.util.function.Supplier<String> UNREADABLE_UPLOAD = () -> Msg.t(
            "Yüklenen veri sunucuda okunamadı; aktarım yarıda kesilmiş olabilir. Dosyayı yeniden seçip tekrar deneyin.",
            "The uploaded data couldn't be read on the server; the transfer may have been interrupted. Choose the file again and retry.");

    /** Ayrıştırma hatası yanıtı (süre aşımı / meşgul) — çağıran aynen döner. */
    private static final class ParseFailure extends RuntimeException {
        final transient ResponseEntity<Map<String, Object>> response;
        ParseFailure(ResponseEntity<Map<String, Object>> response) {
            super(null, null, false, false);
            this.response = response;
        }
    }

    /**
     * Analiz: ayıklanmış yükleme doğrudan; ham yükleme zaman kutulu ayrıştırmadan geçer. Ham yükleme özel anahtar /
     * anahtar deposu taşıyorsa 400 {@code PRIVATE_KEY_NOT_ACCEPTED} (sunucu açmaz); geçersiz {@code extracted} 400
     * {@code EXTRACTED_INVALID}. İki yanıt da {@code errors.file} taşır (arayüz alanın altında gösterir).
     */
    private ManualCertificateAnalyzer.Analysis analyzeUpload(Upload up) {
        if (up.invalid() != null) throw new ParseFailure(uploadRejected("EXTRACTED_INVALID", up.invalid()));
        if (up.extracted() != null) return analyzer.analyzeExtracted(up.extracted());
        try {
            return analyzer.analyze(up.bytes(), up.name(), up.pasted());
        } catch (ManualCertificateAnalyzer.PrivateMaterialRejected pm) {
            log.info("Manuel sertifika ham yüklemesi reddedildi (gizli malzeme: {})", pm.kind());
            throw new ParseFailure(uploadRejected("PRIVATE_KEY_NOT_ACCEPTED", Msg.t(
                    "Özel anahtar ya da anahtar deposu (PFX/P12, JKS/JCEKS/BKS) sunucuya yüklenemez. Dosyayı Site Monitor "
                            + "arayüzünden seçin — sertifikalar tarayıcınızda ayıklanır, özel anahtar ve parola tarayıcıdan "
                            + "çıkmaz — ya da yalnız açık sertifikaları (PEM / DER / P7B) gönderin.",
                    "Private keys and keystores (PFX/P12, JKS/JCEKS/BKS) can't be uploaded to the server. Choose the file in "
                            + "the Site Monitor interface — the certificates are extracted in your browser and the private key "
                            + "and password never leave it — or send only the public certificates (PEM / DER / P7B).")));
        } catch (ManualCertificateAnalyzer.TimeoutExceededException te) {
            throw new ParseFailure(ResponseEntity.status(422).body(Map.of("success", false, "code", "PARSE_TIMEOUT",
                    "error", Msg.t("Dosya " + ManualCertificateAnalyzer.PARSE_TIMEOUT_SECONDS + " saniyede çözümlenemedi; dosyada çok "
                                    + "sayıda sertifika olabilir ya da sunucu yoğun. Bir dakika sonra yeniden deneyin ya da yalnız "
                                    + "izlemek istediğiniz sertifikaları içeren daha küçük bir dosya yükleyin.",
                            "The file couldn't be analysed within " + ManualCertificateAnalyzer.PARSE_TIMEOUT_SECONDS + " seconds; it may "
                                    + "hold a very large number of certificates or the server is busy. Try again in a minute, or upload "
                                    + "a smaller file with only the certificates you want to track."))));
        } catch (ManualCertificateAnalyzer.BusyException be) {
            throw new ParseFailure(ResponseEntity.status(429).body(Map.of("success", false, "code", "BUSY",
                    "error", Msg.t("Sertifika çözümleyicisi şu anda başka dosyaları işliyor; birkaç saniye bekleyip yeniden deneyin.",
                            "The certificate analyser is busy with other files; wait a few seconds and try again."))));
        }
    }

    /** 400 — yükleme kabul edilmedi ({@code code} + {@code error} + {@code errors.file}). */
    private static ResponseEntity<Map<String, Object>> uploadRejected(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        body.put("errors", Map.of("file", message));
        return ResponseEntity.badRequest().body(body);
    }

    /** Seçilen girdi. Yoksa alan hatası ({@code file} / {@code ref}). */
    private ManualCertificateAnalyzer.Entry requireEntry(ManualCertificateAnalyzer.Analysis a, String ref, String field,
                                                         Map<String, String> errors) {
        if (a.entries.isEmpty()) {
            errors.put("file", Msg.t("Dosyada izlenebilir bir sertifika yok (yalnız CSR, özel anahtar ya da sertifika olmayan "
                            + "içerik var). CA'nın gönderdiği sertifika dosyasını ya da zinciri tamamlanmış PFX/JKS'yi yükleyin.",
                    "The file contains no certificate that can be tracked (only a CSR, a private key or non-certificate content). "
                            + "Upload the certificate file the CA sent, or a PFX/JKS with the complete chain."));
            return null;
        }
        ManualCertificateAnalyzer.Entry e = a.find(ref);
        if (e == null) {
            errors.put(field, ref == null || ref.isBlank()
                    ? Msg.t("İzlenecek sertifikayı seçin.", "Choose the certificate to track.")
                    : refError(a, ref));
        }
        return e;
    }

    /**
     * Seçilen {@code ref} girdi değil: bir zincir başına katlanmış ara / kök sertifikaysa (2026-10-07: dosyadaki zincir TEK
     * kayıt olarak izlenir) bunu söyler ve başı adlandırır; yoksa "dosyada bulunamadı".
     */
    private static String refError(ManualCertificateAnalyzer.Analysis a, String ref) {
        ManualCertificateAnalyzer.Entry head = a.headOf(ref);
        if (head != null) {
            String name = head.cn() != null ? head.cn() : head.cert.getSubjectX500Principal().getName();
            return Msg.t("Bu sertifika dosyadaki “" + name + "” sertifikasının zincirinde (ara / kök sertifika); ayrı "
                            + "takip edilmez. Zincirin başındaki sertifikayı seçin — zincir onunla birlikte izlenir.",
                    "This certificate is part of the chain of “" + name + "” in the file (intermediate / root); it isn't "
                            + "tracked on its own. Choose the certificate at the head of the chain — the chain is tracked with it.");
        }
        return Msg.t("Seçilen sertifika dosyada bulunamadı; dosya analizden sonra değişmiş olabilir. Dosyayı yeniden analiz "
                        + "edip sertifikayı listeden seçin.",
                "The selected certificate wasn't found in the file; the file may have changed after it was analysed. Analyse "
                        + "the file again and pick the certificate from the list.");
    }

    private static String validateKey(String raw, String field, Map<String, String> errors) {
        try {
            return ManualCertificateKeys.validate(raw);
        } catch (IllegalArgumentException e) {
            errors.put(field, e.getMessage());
            return null;
        }
    }

    private static void checkNote(String note, Map<String, String> errors) {
        if (note != null && note.strip().length() > ManualCertificateService.NOTE_MAX) {
            errors.put("note", Msg.t("Not en çok " + ManualCertificateService.NOTE_MAX + " karakter olabilir.",
                    "The note can be at most " + ManualCertificateService.NOTE_MAX + " characters."));
        }
    }

    /** {@code inventory} JSON'u → envanter nesnesi (ağ eklemesiyle AYNI snake_case yük). Hatada null + alan hatası. */
    private CertificateInventory parseInventory(String json, Map<String, String> errors) {
        if (json == null || json.isBlank()) {
            errors.putIfAbsent("team_id", Msg.t("Takım seçimi zorunludur.", "A team must be selected."));
            return null;
        }
        try {
            CertificateInventory item = objectMapper.readValue(json, CertificateInventory.class);
            if (item.getTeamId() == null) errors.putIfAbsent("team_id", Msg.t("Takım seçimi zorunludur.", "A team must be selected."));
            if (item.getGroupName() == null || item.getGroupName().isBlank())
                errors.putIfAbsent("group_name", Msg.t("Grup seçimi zorunludur.", "A group must be selected."));
            if (item.getTags() == null || item.getTags().isBlank())
                errors.putIfAbsent("tags", Msg.t("En az bir etiket zorunludur.", "At least one tag is required."));
            return item;
        } catch (Exception e) {
            errors.putIfAbsent("inventory", Msg.t("Envanter bilgileri okunamadı (gönderilen alanlar beklenen biçimde değil). "
                            + "Sayfayı yenileyip (Ctrl+F5) yeniden deneyin.",
                    "The inventory details couldn't be read (the fields sent aren't in the expected format). Reload the page "
                            + "(Ctrl+F5) and try again."));
            return null;
        }
    }

    /** {@code items} JSON dizisi → [ref, domain] çiftleri (≤ {@value #BATCH_MAX}). */
    private List<String[]> parseItems(String json, Map<String, String> errors) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            errors.put("items", Msg.t("En az bir sertifika seçin.", "Choose at least one certificate."));
            return out;
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                errors.put("items", Msg.t("En az bir sertifika seçin.", "Choose at least one certificate."));
                return out;
            }
            if (arr.size() > BATCH_MAX) {
                errors.put("items", Msg.t("Tek seferde en çok " + BATCH_MAX + " sertifika eklenebilir.",
                        "At most " + BATCH_MAX + " certificates can be added at once."));
                return out;
            }
            for (JsonNode n : arr) {
                String ref = n.hasNonNull("ref") ? n.get("ref").asString() : null;
                String domain = n.hasNonNull("domain") ? n.get("domain").asString() : null;
                out.add(new String[] { ref, domain });
            }
        } catch (Exception e) {
            errors.put("items", Msg.t("Seçim listesi okunamadı (gönderilen liste beklenen biçimde değil). Sayfayı yenileyip "
                            + "(Ctrl+F5) sertifikaları yeniden seçin.",
                    "The selection list couldn't be read (the list sent isn't in the expected format). Reload the page (Ctrl+F5) "
                            + "and select the certificates again."));
        }
        return out;
    }

    /**
     * Manuel kaydın sunucu tarafı alanları: kaynak, anahtar, ağ alanları boş; ardından varlığın alan kuralları
     * (Bean Validation — port/tier/anahtar biçimi). Hata → {@code errors[prefix + alan]}.
     */
    private void prepareManualItem(CertificateInventory item, String key, Map<String, String> errors, String prefix) {
        if (item == null || key == null) return;
        item.setCertSource(CertificateInventory.SOURCE_MANUAL);
        item.setDomain(key);
        AdminController.neutralizeNetworkFields(item);
        if (validator == null) return;
        for (ConstraintViolation<CertificateInventory> v : validator.validate(item)) {
            String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
            errors.putIfAbsent(prefix + (path.isEmpty() ? "inventory" : snake(path)), v.getMessage());
        }
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private ResponseEntity<Map<String, Object>> keyClash(String key) {
        if (key == null) return null;
        // Silme KALICI (2026-10-07): silinen kayıt adı TUTMAZ — eski sürümden kalmış çöp satırı çakışma sayılmaz (kayıt
        // anında AdminController.createInventoryRecord onu kalıcı siler).
        return inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(key)
                .filter(i -> i.getDeletedAt() == null).isPresent() ? keyExists(key) : null;
    }

    private static ResponseEntity<Map<String, Object>> keyExists(String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "KEY_EXISTS");
        body.put("field", "domain");
        body.put("domain", key);
        body.put("error", Msg.t("Bu takip adı envanterde zaten kullanılıyor (ağ üzerinden izlenen bir alan adı ya da başka bir "
                        + "manuel kayıt). Sonuna ortamı ya da amacı ekleyerek farklı bir ad seçin; aynı sertifikanın yenisini "
                        + "yüklüyorsanız mevcut kaydı yenileyin.",
                "This tracking name is already used in the inventory (a domain monitored over the network or another manual "
                        + "record). Choose a different name by adding the environment or purpose; if you are uploading the "
                        + "renewal of the same certificate, renew the existing record."));
        return ResponseEntity.status(409).body(body);
    }

    private static ResponseEntity<Map<String, Object>> alreadyTracked(ManualCertificateAnalyzer.Entry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "ALREADY_TRACKED");
        body.put("inventory_id", entry.alreadyTracked().get("inventory_id"));
        body.put("domain", entry.alreadyTracked().get("domain"));
        Object tracked = entry.alreadyTracked().get("domain");
        body.put("error", Msg.t("Bu sertifika zaten “" + tracked + "” kaydıyla takip ediliyor; bir sertifika iki ayrı kayıtla "
                        + "takip edilemez. O kaydı açın; yenilenmiş sertifikayı o kaydın yeni sürümü olarak yükleyin.",
                "This certificate is already tracked as “" + tracked + "”; one certificate can't be tracked by two records. "
                        + "Open that record and upload the renewed certificate as its new version."));
        return ResponseEntity.status(409).body(body);
    }

    // ── Kapsam / okuma ───────────────────────────────────────────────────────

    private CertificateInventory loadManual(Long id) {
        if (id == null) return null;
        return inventoryRepo.findById(id).filter(CertificateInventory::isManual).orElse(null);
    }

    /**
     * Tek kayıt okuma — {@code /admin/inventory/by-domain} kuralı: global görüntüleyici; kendi görüş kapsamı (SY takımı);
     * ya da org geneli okuma (silinmemiş kayıt).
     */
    private boolean canRead(HttpSession session, CertificateInventory inv) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null && inv.getTeamId() != null && view.contains(inv.getTeamId())) return true;
        return inventoryVisibility != null && inventoryVisibility.readableOrgWide(session, inv);
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        for (Team t : userService.listTeams()) if (t.getId() != null) m.put(t.getId(), t.getName());
        return m;
    }

    /** Geçmiş farkı için alanların kopyası (aynı nesne güncelleneceği için). */
    private static CertificateInventory copyForHistory(CertificateInventory inv) {
        CertificateInventory c = new CertificateInventory();
        org.springframework.beans.BeanUtils.copyProperties(inv, c);
        return c;
    }

    static String pemFileName(String domain, Integer version) {
        String base = domain == null ? "sertifika" : domain.replace("*", "wildcard").replaceAll("[^a-z0-9._-]", "_");
        return base + "-v" + (version == null ? 0 : version) + ".pem";
    }

    // ── Hız sınırı ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> rateLimit(String key) {
        long now = System.currentTimeMillis();
        if (rate.size() > 1_000) {
            rate.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    while (!e.getValue().isEmpty() && now - e.getValue().peekFirst() > RATE_WINDOW_MS) e.getValue().pollFirst();
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Long> dq = rate.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > RATE_WINDOW_MS) dq.pollFirst();
            if (dq.size() >= RATE_PER_MIN) {
                return ResponseEntity.status(429).body(Map.of("success", false, "code", "RATE_LIMITED",
                        "error", Msg.t("Çok sık istek: kullanıcı başına dakikada en çok " + RATE_PER_MIN + " analiz / kayıt "
                                        + "isteği. Bir dakika bekleyip yeniden deneyin.",
                                "Too many requests: at most " + RATE_PER_MIN + " analyse / save requests per user per minute. "
                                        + "Wait a minute and try again.")));
            }
            dq.addLast(now);
        }
        return null;
    }

    // ── Yanıt yardımcıları ───────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false,
                "error", Msg.t("Manuel sertifika kaydı bulunamadı; silinmiş ya da görme yetkiniz dışında olabilir. Listeyi "
                                + "yenileyip yeniden deneyin.",
                        "Manual certificate record not found; it may have been deleted or is outside what you can see. "
                                + "Refresh the list and try again.")));
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, String> errors, ManualCertificateAnalyzer.Analysis a) {
        return badRequest(errors, a, null);
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, String> errors, ManualCertificateAnalyzer.Analysis a,
                                                           String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message != null ? message : Msg.t("Gönderilen bilgilerin bazıları geçersiz; her sorun ilgili alanın "
                        + "altında açıklanıyor. Düzeltip yeniden deneyin.",
                "Some of the submitted details are invalid; each problem is explained next to its field. Fix them and try again."));
        body.put("errors", errors);
        if (a != null) body.put("warnings", a.warnings.stream().map(CertificateFileParser.Warning::toJson).toList());
        return ResponseEntity.badRequest().body(body);
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    private static String displayName(HttpSession session) {
        Object d = session != null ? session.getAttribute("displayName") : null;
        return d != null && !d.toString().isBlank() ? d.toString() : null;
    }

    private static String now() {
        return ISO.format(Instant.now());
    }
}
