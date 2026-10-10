package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kripto envanteri / kuantum sonrası hazırlık (2026-10-10): {@code GET /api/crypto-inventory[?fresh=1]} ve dışa aktarım
 * denetim izi {@code POST /api/crypto-inventory/export-audit}.
 *
 * <p><b>İzin ve kapsam Zayıf Algoritma raporuyla BİREBİR</b> (ekran o sayfanın bir sekmesidir, veri aynı sertifika
 * algoritması verisidir): {@code weak_algo.read/view} + {@link SessionScope#viewTeamIds} — global admin / AUDIT tüm
 * takımları, kapsamlı müdür ve takım kullanıcısı yalnız kendi görüş takımlarını görür. 7/24 operatörü bu raporda
 * genişlemez: {@code weak_algo.read} {@code NOC_OPERATOR_GRANTS}'ta değildir ve bu bir uyum/raporlama yüzeyidir, izleme
 * okuma yolu değil. Yeni izin anahtarı açılmadı.
 *
 * <p>Dışa aktarım dosyası istemcide üretilir (XLSX/PDF/CSV, veri zaten bu uçtan gelir); düzenleyici iz için istemci
 * indirmeden sonra {@code export-audit}'i çağırır → {@code CRYPTO_INVENTORY_EXPORT} denetim kaydı (biçim + satır sayısı +
 * süzgeç var mı). Kayıt başarısız olsa da indirme engellenmez.
 */
@RestController
@RequestMapping("/api/crypto-inventory")
@RequiredArgsConstructor
public class CryptoInventoryController {

    static final Set<String> FORMATS = Set.of("xlsx", "pdf", "csv");
    static final int MAX_ROWS = 1_000_000;

    private final CryptoInventoryService cryptoInventoryService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam(value = "fresh", required = false) String fresh,
                                                   HttpSession session) {
        permissionService.require(session, "weak_algo.read", "view");
        Map<String, Object> data = cryptoInventoryService.build(SessionScope.viewTeamIds(session),
                MonitoringOverviewController.isFresh(fresh));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PostMapping("/export-audit")
    public ResponseEntity<Map<String, Object>> exportAudit(@RequestBody(required = false) Map<String, Object> body,
                                                           HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.read", "view");
        Map<String, Object> b = body == null ? Map.of() : body;
        String format = b.get("format") instanceof String s ? s.trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (!FORMATS.contains(format)) {
            throw new GlobalExceptionHandler.FieldValidationException("format", Msg.t(
                    "Dışa aktarım biçimi tanınmadı. Geçerli biçimler: xlsx, pdf, csv — sayfayı yenileyip yeniden deneyin.",
                    "The export format wasn’t recognised. Valid formats are xlsx, pdf and csv — reload the page and try again."));
        }
        int rows = b.get("rows") instanceof Number n ? (int) Math.max(0, Math.min(MAX_ROWS, n.longValue())) : 0;
        boolean filtered = Boolean.TRUE.equals(b.get("filtered"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("format", format);
        detail.put("rows", rows);
        detail.put("filtered", filtered);
        detail.put("scope", SessionScope.viewTeamIds(session) == null ? "ALL" : "TEAM");
        auditService.recordAction("CRYPTO_INVENTORY_EXPORT", session, request, "CRYPTO_INVENTORY", "export",
                "{\"format\":\"" + format + "\",\"rows\":" + rows + ",\"filtered\":" + filtered
                        + ",\"scope\":\"" + detail.get("scope") + "\"}");
        return ResponseEntity.ok(Map.of("success", true, "data", detail));
    }
}
