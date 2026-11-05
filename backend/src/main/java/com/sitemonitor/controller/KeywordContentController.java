package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.KeywordChangeBaseline;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.KeywordContentQueryRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.keyword.content.ContentMasker;
import com.sitemonitor.service.keyword.content.KeywordChangeBaselineService;
import com.sitemonitor.service.keyword.content.KeywordChangeDetector;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anahtar kelime İÇERİK uçları (kw sözleşmesi §10.3, 2026-11-05) — çoklu kural / boyut / içerik değişimi özelliklerinin okuma
 * ve temel yönetimi.
 * <ul>
 *   <li>{@code GET    /api/monitoring/keyword/{id}/change} — değişim ayarı, temel (maskeli metniyle) ve son değerlendirme</li>
 *   <li>{@code DELETE /api/monitoring/keyword/{id}/change/baseline} — temeli siler + bu izlemenin açık KEYWORD_CHANGED alarmını
 *       elle kapatır (MANUAL_RESOLVE postası — iz kalır)</li>
 *   <li>{@code GET    /api/monitoring/keyword/{id}/content-series?days=1..90&from=&to=} — eşleşme sayısı (kural #1) / boyut /
 *       değişim kovaları; ham satır tavanı {@value #SERIES_RAW_CAP}; sabit sorgu sayısı</li>
 * </ul>
 * Canlı ölçüm isteyen uçlar ({@code POST …/change/baseline}, {@code POST …/matches}) Aşama 2'de (denetleyici bağlanınca).
 *
 * <p><b>Yetki</b>: okuma = {@code monitoring.read/view} + {@link SessionScope#canViewMonitoring} (takım kapsamı, 7/24, VIEW
 * paylaşımı); temel yönetimi = {@code monitoring.crud/edit} + {@link SessionScope#canOperateTeam} (EDIT paylaşımı dahil).
 * Göremediği izleme 404 (varlığı sızmaz). Denetim {@code MONITOR_UPDATE} (detay {@code action=baseline_clear}).
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/keyword")
@RequiredArgsConstructor
public class KeywordContentController {

    /** Seri ucu ham satır tavanı (heap koruması — {@code MonitoringController.SERIES_RAW_CAP} ile aynı). */
    static final int SERIES_RAW_CAP = 50_000;
    static final int MAX_DAYS = 90;
    /** Elle kapanış notları (sözleşme §7.2) — çözüm postasında görünür. */
    static final String NOTE_BASELINE_CLEARED = "İçerik temeli sıfırlandı";
    static final String NOTE_BASELINE_TAKEN = "Yeni içerik temeli alındı";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LDT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final KeywordMonitorRepository keywordMonitorRepo;
    private final KeywordContentQueryRepository contentQueries;
    private final KeywordChangeBaselineService baselines;
    private final AlertEventRepository alertEventRepo;
    private final EscalationService escalationService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private Clock clock = Clock.systemUTC();

    /** Testler için saat. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── Değişim görünümü ─────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/change")
    public ResponseEntity<Map<String, Object>> change(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", Boolean.TRUE.equals(m.getChangeDetect()));
        data.put("mask", ContentMasker.toList(m.getChangeMask()));
        data.put("selector", m.getChangeSelector());
        // Başka ayarla (maske / seçici / algoritma) alınmış temel YOK sayılır (spec_hash) — ekranda da gösterilmez
        String spec = KeywordChangeDetector.specHash(m.getChangeMask(), m.getChangeSelector());
        KeywordChangeBaseline b = baselines.find(m.getId()).filter(x -> spec.equals(x.getSpecHash())).orElse(null);
        data.put("baseline", KeywordChangeBaselineService.toApi(b, true));
        data.put("latest", latest(m.getId()));
        data.put("can_edit", canEdit(session, m));
        return ResponseEntity.ok(ok(data));
    }

    private Map<String, Object> latest(Long monitorId) {
        List<KeywordResult> rows = contentQueries.latestChangeRow(monitorId);
        if (rows == null || rows.isEmpty()) return null;
        KeywordResult r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked_at", r.getCheckedAt());
        out.put("changed", r.getContentChanged());
        out.put("summary", r.getChangeSummary());
        return out;
    }

    // ── Temeli sıfırla ───────────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}/change/baseline")
    public ResponseEntity<Map<String, Object>> clearBaseline(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canOperateTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinin içerik temelini değiştiremezsiniz.",
                    "You cannot change the content baseline of this team's monitor."));
        }
        boolean deleted = baselines.delete(m.getId());
        boolean closed = closeOpenChangeAlarm(m, username(session), NOTE_BASELINE_CLEARED);
        auditService.recordAction("MONITOR_UPDATE", session, "KEYWORD_MONITOR", String.valueOf(m.getId()),
                AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "action", "baseline_clear",
                        "deleted", deleted, "closed_alarm", closed), null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("closed_alarm", closed);
        return ResponseEntity.ok(ok(data));
    }

    /**
     * Bu izlemenin açık KEYWORD_CHANGED alarmını elle kapatır (sözleşme §7.2). Aynı URL'yi izleyen BAŞKA bir izlemenin açtığı
     * olaya dokunulmaz ({@link EscalationService#closableBy} — açan biliniyorsa yalnız açan izleme kapatır). Kapanış hatası
     * temel işlemini geri almaz (WARN). @return en az bir alarm kapandıysa true.
     */
    boolean closeOpenChangeAlarm(KeywordMonitor m, String by, String note) {
        boolean closed = false;
        try {
            Map<String, Object> owner = new HashMap<>();
            owner.put("monitor_id", m.getId());
            if (m.getTeamId() != null) owner.put("team_id", m.getTeamId());
            owner.put("standalone", true);
            for (AlertEvent e : alertEventRepo.findOpenAlerts(m.getUrl(), EscalationService.TYPE_KEYWORD_CHANGED)) {
                if (e.getId() == null || !EscalationService.closableBy(e, owner)) continue;
                escalationService.resolve(e.getId(), by, note);
                closed = true;
            }
        } catch (RuntimeException ex) {
            log.warn("İçerik değişimi alarmı kapatılamadı (izleme {}): {}", m.getId(), ex.toString());
        }
        return closed;
    }

    // ── İçerik serisi ────────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/content-series")
    public ResponseEntity<Map<String, Object>> contentSeries(@PathVariable Long id,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to,
                                                             @RequestParam(defaultValue = "30") int days,
                                                             HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        Instant now = clock.instant();
        String toIso = to == null || to.isBlank() ? ISO.format(now) : parseBound(to, true, "to");
        String fromIso = from == null || from.isBlank()
                ? ISO.format(now.minus(Duration.ofDays(Math.max(1, Math.min(days, MAX_DAYS)))))
                : parseBound(from, false, "from");
        if (fromIso.compareTo(toIso) > 0) {
            throw new FieldValidationException("from", Msg.t("Başlangıç bitişten sonra olamaz.", "The start cannot be after the end."));
        }
        List<Object[]> rows = contentQueries.contentSeriesRaw(m.getId(), fromIso, toIso, SERIES_RAW_CAP + 1);
        boolean capped = rows.size() > SERIES_RAW_CAP;
        if (capped) rows = rows.subList(0, SERIES_RAW_CAP);
        return ResponseEntity.ok(ok(buildSeries(rows, fromIso, toIso, capped)));
    }

    /** Ham satırlar → kovalar ({@code [checked_at, ok, occurrences, body_bytes, content_changed]}). Saf. */
    static Map<String, Object> buildSeries(List<Object[]> rows, String from, String to, boolean capped) {
        int keyLen = bucketKeyLen(from, to);
        TreeMap<String, long[]> agg = new TreeMap<>();   // [checks, failed, occN, occSum, occMin, occMax, bytesN, bytesSum, changed]
        int total = 0;
        for (Object[] r : rows) {
            if (r == null || !(r[0] instanceof String ts) || ts.length() < keyLen) continue;
            total++;
            long[] a = agg.computeIfAbsent(ts.substring(0, keyLen),
                    k -> new long[]{0, 0, 0, 0, Long.MAX_VALUE, Long.MIN_VALUE, 0, 0, 0});
            a[0]++;
            if (!Boolean.TRUE.equals(r[1])) a[1]++;
            if (r[2] instanceof Number occ) {
                long o = occ.longValue();
                a[2]++; a[3] += o;
                a[4] = Math.min(a[4], o);
                a[5] = Math.max(a[5], o);
            }
            if (r[3] instanceof Number b) { a[6]++; a[7] += b.longValue(); }
            if (Boolean.TRUE.equals(r[4])) a[8]++;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ts", bucketIso(e.getKey(), keyLen));
            p.put("checks", a[0]);
            p.put("failed", a[1]);
            p.put("occ_min", a[2] > 0 ? a[4] : null);
            p.put("occ_avg", a[2] > 0 ? Math.round((double) a[3] / a[2] * 10) / 10.0 : null);
            p.put("occ_max", a[2] > 0 ? a[5] : null);
            p.put("bytes_avg", a[6] > 0 ? Math.round((double) a[7] / a[6]) : null);
            p.put("changed", a[8]);
            points.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("bucket_seconds", bucketSeconds(keyLen));
        out.put("points", points);
        out.put("total", total);
        out.put("capped", capped);
        return out;
    }

    /** Aralık genişliğine göre kova anahtarı uzunluğu: ≤ 6 sa dakika (16), ≤ 48 sa 10 dk (15), ≤ 31 g saat (13), üstü gün (10). */
    static int bucketKeyLen(String from, String to) {
        try {
            long hours = Duration.between(LocalDateTime.parse(from, LDT), LocalDateTime.parse(to, LDT)).toHours();
            if (hours <= 6) return 16;
            if (hours <= 48) return 15;
            if (hours <= 31 * 24) return 13;
            return 10;
        } catch (Exception e) {
            return 13;
        }
    }

    static long bucketSeconds(int keyLen) {
        return switch (keyLen) {
            case 16 -> 60;
            case 15 -> 600;
            case 13 -> 3600;
            default -> 86_400;
        };
    }

    static String bucketIso(String key, int keyLen) {
        return switch (keyLen) {
            case 16 -> key + ":00";
            case 15 -> key + "0:00";
            case 13 -> key + ":00:00";
            default -> key + "T00:00:00";
        };
    }

    /** {@code yyyy-MM-dd} ya da {@code yyyy-MM-ddTHH:mm[:ss]} → UTC ISO; bitişte yalnız tarih günün sonu. Geçersiz → 400. */
    static String parseBound(String raw, boolean end, String field) {
        String s = raw.trim();
        try {
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                return LDT.format(end ? d.atTime(23, 59, 59) : d.atStartOfDay());
            }
            String t = s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
            if (t.length() == 16) t = t + ":00";
            if (t.length() > 19) t = t.substring(0, 19);
            return LDT.format(LocalDateTime.parse(t, LDT));
        } catch (Exception e) {
            throw new FieldValidationException(field, Msg.t("Tarih biçimi geçersiz (ör. 2026-11-05 ya da 2026-11-05T10:00:00).",
                    "Invalid date format (e.g. 2026-11-05 or 2026-11-05T10:00:00)."));
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────

    private boolean canEdit(HttpSession session, KeywordMonitor m) {
        return permissionService.allows(session, "monitoring.crud", "edit") && SessionScope.canOperateTeam(session, m.getTeamId());
    }

    private static String username(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? null : u.toString();
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(clock.instant()));
        return body;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "NOT_FOUND");
        body.put("error", Msg.t("Anahtar kelime izlemesi bulunamadı; silinmiş olabilir. Listeyi yenileyin.",
                "Keyword monitor not found; it may have been deleted. Refresh the list."));
        return ResponseEntity.status(404).body(body);
    }
}
