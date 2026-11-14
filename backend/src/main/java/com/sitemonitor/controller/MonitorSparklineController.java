package com.sitemonitor.controller;

import com.sitemonitor.service.MonitorSparklineService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * İzleme kartı mini trendi (2026-09-12): {@code GET /api/monitoring/sparklines?type=http&hours=24}.
 * Ayrı controller — MonitoringController 4k satır; yeni yüzey oraya eklenmedi.
 * Kapsam: {@code monitoring.read} + takım görünürlüğü ({@link SessionScope#canView}).
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitorSparklineController {

    private final MonitorSparklineService sparklineService;
    private final PermissionService permissionService;
    private final com.sitemonitor.service.AppSettingsService appSettings;

    /**
     * {@code ids} (2026-11-14, isteğe bağlı — Kişisel Panom "favori izlemelerin yanıt süresi"): virgüllü monitör kimlikleri;
     * verilirse yanıt görünür küme ∩ bu kimliklerle sınırlanır (kutu birkaç favori için türün TÜM filosunun trendini indirmesin).
     * Kapsamı ASLA genişletmez; parametre yoksa davranış aynen eskisi. Eski sunucu parametreyi yok sayar (tam liste döner —
     * istemci zaten kimlikle seçer).
     */
    @GetMapping("/sparklines")
    public ResponseEntity<Map<String, Object>> sparklines(
            @RequestParam String type,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String ids,
            HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (!MonitorSparklineService.supports(type)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bilinmeyen izleme türü: " + type));
        }
        if (ids != null && ids.length() > IDS_MAX_CHARS) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("ids",
                    com.sitemonitor.util.Msg.t("Çok fazla izleme kimliği gönderildi; en çok " + IDS_MAX + " kimlik istenebilir.",
                            "Too many monitor ids were sent; at most " + IDS_MAX + " ids can be requested."));
        }
        Set<Long> only = parseIds(ids);
        // Maliyet sınırı (prod kapısı 2026-09-25, O-7): global görücü tüm filoyu ortak önbellekten alır; kapsamlı
        // kullanıcı yalnız GÖRÜNÜR monitörleri sorgular ve penceresi arayüzün kullandığı 24 saatle sınırlıdır.
        boolean global = SessionScope.seesAllMonitoring(session);   // + 7/24 operatörü (2026-10-04)
        int h = Math.max(1, Math.min(global ? MonitorSparklineService.MAX_HOURS : MonitorSparklineService.SCOPED_MAX_HOURS, hours));
        Map<Long, Map<String, Object>> result;
        if (only != null && only.isEmpty()) {
            result = Map.of();   // süzgeç verildi ama geçerli kimlik yok — "hepsi"ne DÖNÜLMEZ
        } else if (only != null) {
            // İnceleme 2026-11-14: kimlik süzgeci varken türün TÜM filosu hesaplanmaz (tek favorili duvar ekranı dakikada bir
            // filoyu taratıyordu) — var olan ve görünür kimlikler ∩ istenen, daraltılmış sorguyla (monitor_id IN, ≤ 200). Kapsam
            // kuralı aynen: tümünü gören tüm VAR OLAN izlemeler, diğerleri görünür olanlar; kimlik yalnız DARALTIR.
            Set<Long> wanted = global ? new java.util.HashSet<>(sparklineService.monitorTeams(type).keySet()) : visibleMonitors(type, session);
            wanted.retainAll(only);
            result = wanted.isEmpty() ? Map.of() : sparklineService.sparklinesFor(type, h, wanted);
        } else if (global) {
            result = sparklineService.sparklinesAll(type, h);
        } else {
            // Performans 2026-11-06 (PF2 #2): kapsamlı kullanıcı da ortak (global) önbellekten okur, görünür kümeyle BELLEKTE
            // süzülür — her izlemenin sonucu yalnız kendi satırlarından hesaplanır, çıktı kendi sorgusuyla birebir. Kapsamlı tavan
            // (24 sa) korunur.
            Set<Long> visible = visibleMonitors(type, session);
            result = visible.isEmpty() ? Map.of() : MonitorSparklineService.subset(sparklineService.sparklinesAll(type, h), visible);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        result.forEach((id, v) -> data.put(String.valueOf(id), v));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("type", type);
        body.put("hours", h);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** Kullanılabilirlik / SLA (2026-09-12, #11): {@code GET /api/monitoring/sla?type=http&days=30}; hedef canlı ayardan. */
    @GetMapping("/sla")
    public ResponseEntity<Map<String, Object>> sla(
            @RequestParam String type,
            @RequestParam(defaultValue = "30") int days,
            HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (!MonitorSparklineService.supports(type)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bilinmeyen izleme türü: " + type));
        }
        // O-7: global görücü ortak önbellekten; kapsamlı kullanıcı yalnız görünür monitörler, en çok 30 gün.
        boolean global = SessionScope.seesAllMonitoring(session);   // + 7/24 operatörü (2026-10-04)
        int d = Math.max(1, Math.min(global ? MonitorSparklineService.MAX_DAYS : MonitorSparklineService.SCOPED_MAX_DAYS, days));
        Map<Long, Map<String, Object>> result;
        if (global) {
            result = sparklineService.availabilityAll(type, d);
        } else {
            // Performans 2026-11-06 (PF2 #2): ortak (global) önbellekten, görünür kümeyle bellekte süzülür; kapsamlı tavan (30 gün).
            Set<Long> visible = visibleMonitors(type, session);
            result = visible.isEmpty() ? Map.of() : MonitorSparklineService.subset(sparklineService.availabilityAll(type, d), visible);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        result.forEach((id, v) -> data.put(String.valueOf(id), v));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("type", type);
        body.put("days", d);
        body.put("target_pct", appSettings.getDouble(SLA_TARGET_KEY, 99.9));
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** Oturumun izleme okuma kapsamındaki monitörler (takımsız / envanter türevi: yüklemin kararı). */
    private Set<Long> visibleMonitors(String type, HttpSession session) {
        return sparklineService.monitorTeams(type).entrySet().stream()
                .filter(e -> SessionScope.canViewMonitoring(session, e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(java.util.HashSet::new));
    }

    /** {@code ids} süzgecinin tavanı: kimlik sayısı (favori tavanı 200) ve parametre uzunluğu (ayrıştırmadan ÖNCE denetlenir). */
    static final int IDS_MAX = 200;
    static final int IDS_MAX_CHARS = 4000;

    /**
     * {@code "3,14,15"} → kimlik kümesi; parametre yoksa {@code null} (süzgeç yok). Sayı olmayan / pozitif olmayan parça yok
     * sayılır, ilk {@value #IDS_MAX} geçerli kimlik alınır. Doğrusal tarama (regex yok).
     */
    static Set<Long> parseIds(String ids) {
        if (ids == null) return null;
        Set<Long> out = new java.util.LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i <= ids.length() && out.size() < IDS_MAX; i++) {
            if (i < ids.length() && ids.charAt(i) != ',') continue;
            String part = ids.substring(start, i).trim();
            start = i + 1;
            if (part.isEmpty() || part.length() > 18) continue;
            boolean digits = true;
            for (int k = 0; k < part.length(); k++) if (part.charAt(k) < '0' || part.charAt(k) > '9') { digits = false; break; }
            if (!digits) continue;
            long v = Long.parseLong(part);
            if (v > 0) out.add(v);
        }
        return out;
    }

    /** Filo geneli kullanılabilirlik hedefi (%) — Genel Ayarlar; takım başına hedef bilinçli ertelendi. */
    public static final String SLA_TARGET_KEY = "site.monitor.sla.target-pct";
}
