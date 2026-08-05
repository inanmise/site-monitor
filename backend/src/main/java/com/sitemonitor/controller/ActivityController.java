package com.sitemonitor.controller;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Birleşik aktivite akışı (Kayıtlar → Aktivite) — TÜM izleme türlerinin kontrol + yaşam döngüsü
 * aktivitelerini tek sayfalı/filtreli akışta döner.
 *
 * <b>Takım-izolasyonu SUNUCUDA zorlanır:</b> scope oturumdaki {@link SessionScope#viewTeamIds}'ten
 * türetilir — istemciden gelen HİÇBİR teamId/userId'ye güvenilmez (IDOR engeli). Global görücü
 * (admin/AUDIT, viewTeamIds==null) tümünü görür. Detay ucu da sahiplik kontrolü yapar (yetkisiz → 404).
 */
@Slf4j
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityLogRepository repo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MINI_HISTORY = 6;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String type,      // CSV: "CERT,HTTP,PORT"
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            HttpSession session) {

        int sz = Math.max(1, Math.min(size, 200));
        Scope scope = scope(session);
        if (scope.empty) return ResponseEntity.ok(envelope(List.of(), 0L, 0, sz));

        List<String> types = csvUpper(type);
        Pageable pageable = PageRequest.of(Math.max(0, page), sz);
        Page<ActivityLog> result = repo.findFiltered(scope.scoped, scope.list,
                !types.isEmpty(), types.isEmpty() ? List.of("") : types,
                monitorId, nil(status) == null ? null : status.trim().toUpperCase(),
                nil(from), nil(to), like(q), pageable);

        List<Map<String, Object>> data = result.getContent().stream().map(ActivityController::toDto).toList();
        return ResponseEntity.ok(envelope(data, result.getTotalElements(), result.getNumber(), sz));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            HttpSession session) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        Scope scope = scope(session);
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        String last = null;
        if (!scope.empty) {
            List<String> types = csvUpper(type);
            boolean tf = !types.isEmpty();
            List<String> tlist = tf ? types : List.of("");
            for (Object[] row : repo.countByStatusFiltered(scope.scoped, scope.list, tf, tlist,
                    monitorId, nil(from), nil(to), like(q))) {
                String st = String.valueOf(row[0]);
                long c = ((Number) row[1]).longValue();
                counts.put(st, c);
                total += c;
            }
            last = repo.maxActivityTimeFiltered(scope.scoped, scope.list, tf, tlist,
                    monitorId, nil(from), nil(to), like(q));
        }
        body.put("total", total);
        body.put("success_count", counts.getOrDefault("SUCCESS", 0L));
        body.put("warning", counts.getOrDefault("WARNING", 0L));
        body.put("error", counts.getOrDefault("ERROR", 0L) + counts.getOrDefault("TIMEOUT", 0L));
        body.put("unknown", counts.getOrDefault("UNKNOWN", 0L));
        body.put("last_activity", last);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id, HttpSession session) {
        Optional<ActivityLog> found = repo.findById(id);
        // Sahiplik/izolasyon: bulunamazsa VEYA kaydın takımı görüntüleme kapsamında değilse → 404
        // (varlık sızıntısını önlemek için 403 yerine 404; başka takımın kaydı "yok" gibi davranır).
        if (found.isEmpty() || !SessionScope.canView(session, found.get().getTeamId())) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "not_found"));
        }
        ActivityLog a = found.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", toDto(a));
        // Aynı monitörün son birkaç kontrolü (mini-geçmiş) — yalnız monitorId varsa.
        List<Map<String, Object>> recent = List.of();
        if (a.getMonitorId() != null) {
            recent = repo.findRecentForMonitor(a.getMonitorType(), a.getMonitorId(), PageRequest.of(0, MINI_HISTORY))
                    .stream().map(ActivityController::toDto).toList();
        }
        body.put("recent", recent);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Takım kapsamı — AdminController.listAlerts ile aynı desen. */
    private record Scope(boolean scoped, List<Long> list, boolean empty) {}

    private static Scope scope(HttpSession session) {
        List<Long> view = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session);
        boolean scoped = view != null;
        if (scoped && view.isEmpty()) return new Scope(true, List.of(-1L), true);   // kapsamsız → hiç kayıt
        return new Scope(scoped, scoped ? view : List.of(-1L), false);
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> data, long total, int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        body.put("total_pages", (int) Math.ceil(total / (double) size));
        body.put("timestamp", now());
        return body;
    }

    private static Map<String, Object> toDto(ActivityLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("activity_time", a.getActivityTime());
        m.put("monitor_type", a.getMonitorType());
        m.put("monitor_id", a.getMonitorId());
        m.put("monitor_name", a.getMonitorName());
        m.put("target", a.getTarget());
        m.put("action", a.getAction());
        m.put("result_status", a.getResultStatus());
        m.put("result_summary", a.getResultSummary());
        m.put("result_detail", a.getResultDetail());
        m.put("error_message", a.getErrorMessage());
        m.put("error_class", a.getErrorClass());
        m.put("actor", a.getActor());
        m.put("team_id", a.getTeamId());
        m.put("response_ms", a.getResponseMs());
        m.put("days_remaining", a.getDaysRemaining());
        return m;
    }

    private static List<String> csvUpper(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String nil(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    /** Serbest metin → '%aranan%' (lower) veya null. */
    private static String like(String q) {
        String v = nil(q);
        return v == null ? null : "%" + v.toLowerCase() + "%";
    }

    private static String now() { return ISO.format(Instant.now()); }
}
