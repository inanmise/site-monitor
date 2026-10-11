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
import java.time.temporal.ChronoUnit;
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

    /**
     * Özet şerit. 2026-10-11 (aktivite sayfası yeniden tasarımı) EKLEMELİ alanlar: {@code type_counts} (tür çipleri —
     * tür süzgeci hariç, {@code status} dahil) ve {@code series} (zaman çizelgesi: ≤ 48 saatlik pencerede saatlik, daha
     * uzunda İstanbul günü; aralık yoksa son 30 gün; sıfır dolgulu). Eski alanlar aynı.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String status,
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
        Map<String, Long> typeCounts = new TreeMap<>();
        Instant seriesTo = parseIso(nil(to), Instant.now());
        Instant seriesFrom = parseIso(nil(from), seriesTo.minus(SERIES_DEFAULT_DAYS, ChronoUnit.DAYS));
        if (seriesFrom.isBefore(seriesTo.minus(SERIES_MAX_DAYS, ChronoUnit.DAYS))) seriesFrom = seriesTo.minus(SERIES_MAX_DAYS, ChronoUnit.DAYS);
        List<Object[]> hourRows = List.of();
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
            String st = nil(status) == null ? null : status.trim().toUpperCase();
            for (Object[] row : repo.countByTypeFiltered(scope.scoped, scope.list, monitorId, st, nil(from), nil(to), like(q))) {
                if (row[0] != null) typeCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
            hourRows = repo.countByHourAndStatus(scope.scoped, scope.list, tf, tlist, monitorId,
                    ISO.format(seriesFrom), nil(to), like(q));
        }
        body.put("total", total);
        body.put("success_count", counts.getOrDefault("SUCCESS", 0L));
        body.put("warning", counts.getOrDefault("WARNING", 0L));
        body.put("error", counts.getOrDefault("ERROR", 0L) + counts.getOrDefault("TIMEOUT", 0L));
        body.put("unknown", counts.getOrDefault("UNKNOWN", 0L));
        body.put("last_activity", last);
        body.put("type_counts", typeCounts);
        body.put("series", series(hourRows, seriesFrom, seriesTo));
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    /** Seri penceresi: aralık verilmezse son 30 gün; en uzun 31 gün (sorgu sınırı). */
    static final int SERIES_DEFAULT_DAYS = 30;
    static final int SERIES_MAX_DAYS = 31;
    private static final java.time.ZoneId TZ = java.time.ZoneId.of("Europe/Istanbul");

    /**
     * Saat × durum satırlarını çizelgeye çevirir: pencere ≤ 48 saat → saatlik noktalar ({@code t} = UTC saat başı,
     * "Z" ekli), daha uzun → İstanbul günü ({@code t} = yyyy-MM-dd; UTC gün sınırı 3 saat kaydırırdı). Boş dilimler sıfırla
     * doldurulur; TIMEOUT hata sayılır (KPI ile aynı).
     */
    static Map<String, Object> series(List<Object[]> hourRows, Instant from, Instant to) {
        boolean hourly = !to.isAfter(from.plus(48, ChronoUnit.HOURS));
        Map<String, long[]> buckets = new LinkedHashMap<>();   // anahtar → [success, warning, error, unknown]
        if (hourly) {
            Instant h = from.truncatedTo(ChronoUnit.HOURS);
            for (int i = 0; i < 49 && !h.isAfter(to); i++, h = h.plus(1, ChronoUnit.HOURS)) buckets.put(hourKey(h), new long[4]);
        } else {
            java.time.LocalDate d = from.atZone(TZ).toLocalDate();
            java.time.LocalDate end = to.atZone(TZ).toLocalDate();
            for (int i = 0; i <= SERIES_MAX_DAYS + 1 && !d.isAfter(end); i++, d = d.plusDays(1)) buckets.put(d.toString(), new long[4]);
        }
        for (Object[] r : hourRows) {
            if (r == null || r[0] == null) continue;
            String hr = String.valueOf(r[0]);
            Instant at;
            try {
                at = java.time.LocalDateTime.parse(hr + ":00:00").toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                continue;
            }
            String key = hourly ? hourKey(at) : at.atZone(TZ).toLocalDate().toString();
            long[] b = buckets.get(key);
            if (b == null) continue;   // pencere dışı (to'dan sonra yazılan satır)
            long c = r[2] == null ? 0 : ((Number) r[2]).longValue();
            switch (String.valueOf(r[1]).toUpperCase()) {
                case "SUCCESS" -> b[0] += c;
                case "WARNING" -> b[1] += c;
                case "ERROR", "TIMEOUT" -> b[2] += c;
                default -> b[3] += c;
            }
        }
        List<Map<String, Object>> points = new ArrayList<>(buckets.size());
        for (Map.Entry<String, long[]> e : buckets.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", e.getKey());
            p.put("success", e.getValue()[0]);
            p.put("warning", e.getValue()[1]);
            p.put("error", e.getValue()[2]);
            p.put("unknown", e.getValue()[3]);
            points.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bucket", hourly ? "hour" : "day");
        out.put("points", points);
        return out;
    }

    private static String hourKey(Instant h) {
        return ISO.format(h.truncatedTo(ChronoUnit.HOURS)) + "Z";
    }

    private static Instant parseIso(String v, Instant fallback) {
        if (v == null) return fallback;
        try {
            return java.time.LocalDateTime.parse(v.length() > 19 ? v.substring(0, 19) : v).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return fallback;
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id, HttpSession session) {
        Optional<ActivityLog> found = repo.findById(id);
        // Sahiplik/izolasyon: bulunamazsa VEYA kaydın takımı görüntüleme kapsamında değilse → 404
        // (varlık sızıntısını önlemek için 403 yerine 404; başka takımın kaydı "yok" gibi davranır).
        if (found.isEmpty() || !SessionScope.canViewMonitoring(session, found.get().getTeamId())) {   // + 7/24 operatörü (2026-10-04)
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
        // İzleme etkinlik akışı (2026-10-04): 7/24 operatörü tüm takımlarınkini okur.
        List<Long> view = SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIds(session);
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
