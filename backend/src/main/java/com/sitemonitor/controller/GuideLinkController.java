package com.sitemonitor.controller;

import com.sitemonitor.model.GuideLink;
import com.sitemonitor.repository.GuideLinkRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/guide-links")
@RequiredArgsConstructor
public class GuideLinkController {

    private final GuideLinkRepository repo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpSession session) {
        requireAuth(session);
        List<GuideLink> items = repo.findAllByOrderByCategoryAscSortOrderAscIdAsc();
        return ok(Map.of("data", items));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody GuideLink body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "guide_links.crud", "edit");
        validate(body);
        validateUrl(body.getUrl(), null);
        body.setId(null);
        Instant now = Instant.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        if (body.getSortOrder() == null) body.setSortOrder(0);
        GuideLink saved = repo.save(body);
        log.info("Guide link created id={} category={} title={} by={}",
                saved.getId(), saved.getCategory(), saved.getTitle(), actor(session));
        auditService.recordAction("GUIDE_LINK_CREATE", session, "GUIDE_LINK", String.valueOf(saved.getId()), saved.getTitle(), null);
        return ok(Map.of("data", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id, @RequestBody GuideLink body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "guide_links.crud", "edit");
        validate(body);
        GuideLink existing = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Guide link not found: " + id));
        validateUrl(body.getUrl(), existing.getUrl());
        String[] gf = {"category", "title", "url", "description", "sortOrder"};
        java.util.Map<String, Object> _before = AuditDiff.snapshot(existing, gf);
        existing.setCategory(body.getCategory());
        existing.setTitle(body.getTitle());
        existing.setUrl(body.getUrl());
        existing.setDescription(body.getDescription());
        if (body.getSortOrder() != null) existing.setSortOrder(body.getSortOrder());
        existing.setUpdatedAt(Instant.now());
        GuideLink saved = repo.save(existing);
        log.info("Guide link updated id={} by={}", saved.getId(), actor(session));
        auditService.recordAction("GUIDE_LINK_UPDATE", session, "GUIDE_LINK", String.valueOf(saved.getId()), saved.getTitle(),
                AuditDiff.diff(_before, AuditDiff.snapshot(saved, gf)));
        return ok(Map.of("data", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "guide_links.crud", "edit");
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Guide link not found: " + id);
        }
        // Silmeden ONCE anlik goruntu: baglantinin basligi/URL'i gittikten sonra hicbir yerde yok.
        // Alan listesi guncelleme dalindakiyle AYNI (gf) — iki liste ayrisirsa karsilastirma bozulur.
        String before = repo.findById(id)
                .map(g -> AuditDiff.snapshotJson(AuditDiff.snapshot(g,
                        "category", "title", "url", "description", "sortOrder")))
                .orElse(null);
        repo.deleteById(id);
        log.info("Guide link deleted id={} by={}", id, actor(session));
        auditService.recordAction("GUIDE_LINK_DELETE", session, "GUIDE_LINK", String.valueOf(id),
                before != null ? before : com.sitemonitor.service.AuditDetail.of("existed", false), null);
        return ok(Map.of("message", "Deleted"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void validate(GuideLink body) {
        if (body == null) throw new IllegalArgumentException("Body required");
        if (isBlank(body.getCategory())) throw new IllegalArgumentException("Category required");
        if (isBlank(body.getTitle()))    throw new IllegalArgumentException("Title required");
        if (isBlank(body.getUrl()))      throw new IllegalArgumentException("URL required");
        if (body.getCategory().length() > 100) throw new IllegalArgumentException("Category too long");
        if (body.getTitle().length()    > 200) throw new IllegalArgumentException("Title too long");
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    // ── Adres şeması (2026-10-08, güvenlik denetimi) ─────────────────────────
    // Adres her şemayı kabul ediyordu: `javascript:` / `data:` / `vbscript:` saklanıp rehber kartında bağlantı olarak
    // çizilebiliyordu (ön yüz guideHref beyaz listesi ikinci savunma). Kural, formun (renewal/guideSteps.js
    // validateLinkForm + normalizeUrl) bugün kabul ettikleriyle AYNI: http/https, mailto:, file: (ağ klasörü), UNC yolu
    // (\\sunucu\paylaşım), şemasız adres (ön yüz https:// ekler) ve `ana-bilgisayar:port/yol`. Başka şema → 400.

    private static final java.util.Set<String> ALLOWED_SCHEMES = java.util.Set.of("http", "https", "mailto", "file");
    /** Hiçbir koşulda kabul edilmez (port muafiyeti dahil): betik/veri yürüten şemalar. */
    private static final java.util.Set<String> DANGEROUS_SCHEMES =
            java.util.Set.of("javascript", "vbscript", "data", "blob", "livescript", "mocha", "jar", "view-source");
    private static final java.util.regex.Pattern SCHEME = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$", java.util.regex.Pattern.DOTALL);
    /** `ana-bilgisayar:8443/yol` — şema sanılan önek aslında ana bilgisayar, devamı port. */
    private static final java.util.regex.Pattern PORT_REST = java.util.regex.Pattern.compile("^\\d{1,5}(?:[/?#].*)?$", java.util.regex.Pattern.DOTALL);

    /**
     * Yeni adresin şeması izinli mi; değilse 400 VALIDATION_FAILED ({@code fields.url}). Saklı değerle AYNI gelen eski
     * adres muaf — eski bağlantının başka bir alanı düzenlenebilir kalır.
     */
    static void validateUrl(String url, String stored) {
        if (url == null) return;   // boşluk/zorunluluk validate()'te
        String s = url.trim();
        if (stored != null && s.equals(stored.trim())) return;
        if (!urlAllowed(s)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("url", com.sitemonitor.util.Msg.t(
                    "Adres kabul edilmedi: yalnız http:// ya da https:// ile başlayan web adresleri, mailto: e-posta bağlantıları ve ağ klasörü yolları (\\\\sunucu\\paylaşım ya da file:) eklenebilir.",
                    "The address wasn’t accepted: only web addresses starting with http:// or https://, mailto: e-mail links and network folder paths (\\\\server\\share or file:) can be added."));
        }
    }

    static boolean urlAllowed(String s) {
        if (s == null || s.isEmpty()) return false;
        // Tarayıcı URL ayrıştırması sekme/satır sonunu SİLER ("java\tscript:" → "javascript:") — kontrol karakteri yok.
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return false;
        if (s.startsWith("\\\\") || s.startsWith("//")) return true;   // UNC yolu / şemasız (//host)
        java.util.regex.Matcher m = SCHEME.matcher(s);
        if (!m.matches()) return true;                                   // şemasız (ön yüz https:// ekler)
        String scheme = m.group(1).toLowerCase(java.util.Locale.ROOT);
        if (ALLOWED_SCHEMES.contains(scheme)) return true;
        if (DANGEROUS_SCHEMES.contains(scheme)) return false;
        return PORT_REST.matcher(m.group(2)).matches();                  // ana-bilgisayar:port[/yol]
    }

    private void requireAuth(HttpSession session) {
        if (session.getAttribute("username") == null) {
            throw new SecurityException("Authentication required");
        }
    }

    private void requireAdmin(HttpSession session) {
        requireAuth(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            log.warn("Unauthorized guide-link admin attempt by user={}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
