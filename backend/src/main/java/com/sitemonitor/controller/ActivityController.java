package com.sitemonitor.controller;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.activity.ActivityExportService;
import com.sitemonitor.service.activity.ActivityFilter;
import com.sitemonitor.service.activity.ActivityQueryService;
import com.sitemonitor.service.activity.ActivityRelatedService;
import com.sitemonitor.service.activity.ActivityTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
    // ── be2 (2026-11-05, aktivite zenginleştirmesi D + E + F) ──
    private final ActivityQueryService queries;
    private final ActivityTimelineService timelines;
    private final ActivityExportService exports;
    private final ActivityRelatedService relatedSvc;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MINI_HISTORY = 6;

    /**
     * Sayfalı akış. 2026-11-05 (aktivite zenginleştirmesi E) EKLEMELİ süzgeçler: {@code status} CSV, serbest ISO
     * {@code from} / {@code to}, {@code monitor=TÜR:kimlik}, {@code teamId} (kapsamla kesişir), {@code actor}, {@code group}
     * CSV, {@code reason} CSV — kural {@link ActivityFilter}. Eski parametreler birebir; hatalı değer 400 {@code VALIDATION_FAILED}.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String type,      // CSV: "CERT,HTTP,PORT"
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String status,    // CSV: "ERROR,TIMEOUT" (tek değer de geçerli)
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String monitor,   // "HTTP:42"
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String group,     // CSV: check,alarm,notification,change,other
            @RequestParam(required = false) String reason,    // CSV: hata nedeni kodu ya da class:<ERROR_CLASS>
            HttpSession session) {

        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, Math.min(page, MAX_PAGE));
        Scope scope = scope(session);
        ActivityFilter f = ActivityFilter.parse(new ActivityFilter.Params(type, monitorId, monitor, status, from, to,
                teamId, actor, group, reason, q), scope.scoped, scope.list);
        if (scope.empty) return ResponseEntity.ok(envelope(List.of(), 0L, 0, sz));

        ActivityQueryService.PageResult result = queries.list(f, pg, sz);
        List<Map<String, Object>> data = result.rows().stream().map(ActivityController::listDto).toList();
        return ResponseEntity.ok(envelope(data, result.total(), pg, sz));
    }

    /** Sayfa numarası tavanı (OFFSET taşmasın). */
    static final int MAX_PAGE = 1_000_000;

    /**
     * Özet şerit. 2026-10-11 (aktivite sayfası yeniden tasarımı) EKLEMELİ alanlar: {@code type_counts} (tür çipleri —
     * tür süzgeci hariç, {@code status} dahil) ve {@code series} (zaman çizelgesi: ≤ 48 saatlik pencerede saatlik, daha
     * uzunda İstanbul günü; aralık yoksa son 30 gün; sıfır dolgulu). 2026-11-05: listeyle AYNI yeni süzgeçler +
     * {@code group_counts} (olay grubu çipleri — grup süzgeci hariç, tür dahil; beş grup sıfır dolgulu) + {@code timeout}
     * (yalnız TIMEOUT sayısı; {@code error} eskisi gibi ERROR + TIMEOUT). Eski alanlar aynı; sorgu sayısı 3.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String monitor,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String reason,
            HttpSession session) {

        Scope scope = scope(session);
        ActivityFilter f = ActivityFilter.parse(new ActivityFilter.Params(type, monitorId, monitor, status, from, to,
                teamId, actor, group, reason, q), scope.scoped, scope.list);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        Map<String, Long> counts = Map.of();
        String last = null;
        Map<String, Long> typeCounts = new TreeMap<>();
        Map<String, Long> groupCounts = new LinkedHashMap<>();
        for (String g : ActivityFilter.GROUPS) groupCounts.put(g, 0L);
        Instant seriesTo = f.to() == null ? Instant.now() : ActivityFilter.instantOf(f.to());
        Instant seriesFrom = f.from() == null ? seriesTo.minus(SERIES_DEFAULT_DAYS, ChronoUnit.DAYS) : ActivityFilter.instantOf(f.from());
        if (seriesFrom.isBefore(seriesTo.minus(SERIES_MAX_DAYS, ChronoUnit.DAYS))) seriesFrom = seriesTo.minus(SERIES_MAX_DAYS, ChronoUnit.DAYS);
        List<Object[]> hourRows = List.of();
        if (!scope.empty) {
            ActivityQueryService.SummaryData d = queries.summary(f, ISO.format(seriesFrom));
            counts = d.statusCounts();
            last = d.lastActivity();
            typeCounts = d.typeCounts();
            groupCounts = d.groupCounts();
            hourRows = d.hourRows();
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        body.put("total", total);
        body.put("success_count", counts.getOrDefault("SUCCESS", 0L));
        body.put("warning", counts.getOrDefault("WARNING", 0L));
        body.put("error", counts.getOrDefault("ERROR", 0L) + counts.getOrDefault("TIMEOUT", 0L));
        body.put("timeout", counts.getOrDefault("TIMEOUT", 0L));
        body.put("unknown", counts.getOrDefault("UNKNOWN", 0L));
        body.put("last_activity", last);
        body.put("type_counts", typeCounts);
        body.put("group_counts", groupCounts);
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

    // ── be2 (2026-11-05, aktivite zenginleştirmesi D + E + F): zaman çizelgesi, tam dışa aktarım, kök neden ipuçları ──

    /**
     * İzleme zaman çizelgesi (D): {@code ?monitor=TÜR:kimlik&from=&to=} (varsayılan son 7 gün, en çok 90). Kapı = izlemenin
     * takımı ({@code canViewMonitoring}); görülemeyen → 404. Ayrıntı: {@link ActivityTimelineService}.
     */
    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(
            @RequestParam(required = false) String monitor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpSession session) {
        Scope scope = scope(session);
        Map<String, Object> data = timelines.timeline(monitor, from, to, scope.scoped, scope.list,
                team -> SessionScope.canViewMonitoring(session, team), Instant.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    /**
     * Tam dışa aktarım (E): {@code ?format=csv|json&<liste süzgeçleri>} — listeyle AYNI süzgeç ve kapsam, tüm sayfalar
     * (CSV ≤ 50 000, JSON ≤ 20 000 satır). Sınırlar ve biçim: {@link ActivityExportService}.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long monitorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String monitor,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String reason,
            HttpSession session, HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        Scope scope = scope(session);
        ActivityFilter f = ActivityFilter.parse(new ActivityFilter.Params(type, monitorId, monitor, status, from, to,
                teamId, actor, group, reason, q), scope.scoped, scope.list);
        return exports.export(f, format, scope.empty, session, request, response);
    }

    /**
     * "Aynı anda ne oldu?" (F): {@code ?window=120} (sn, 30–900). Kapı = {@code GET /{id}} ile aynı (404). Pencere yalnız
     * görünen kapsamdan; öğeler listeyle aynı satır biçiminde. Ayrıntı: {@link ActivityRelatedService}.
     */
    @GetMapping("/{id}/related")
    public ResponseEntity<Map<String, Object>> related(@PathVariable Long id,
                                                       @RequestParam(required = false) Integer window,
                                                       HttpSession session) {
        Scope scope = scope(session);
        ActivityRelatedService.Result r = relatedSvc.related(id, window, scope.scoped, scope.list,
                team -> SessionScope.canViewMonitoring(session, team));
        Map<String, Object> data = new LinkedHashMap<>(r.body());
        data.put("items", r.items().stream().map(ActivityController::listDto).toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
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

    // ── be1: ayrıntı sayfası (2026-11-05, aktivite zenginleştirmesi A–C) ─────────────────────────────────────────────

    /** Ayrıntı servisi — alan enjeksiyonu + isteğe bağlı (dar test bağlamında yok → uç 503). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.activity.ActivityDetailService detailService;

    /**
     * Aktivite ayrıntı sayfası: satır + bağlantı alanları, kontrol satırı (tam / yaklaşık) + önceki / sonraki, ± 24 sa seri,
     * alarm, değişiklik bağı, derin bağlantılar. Kapı {@code GET /{id}} ile AYNI (görüş kapsamı dışı → 404); {@code /{id}}
     * yanıtı değişmez.
     */
    @GetMapping("/{id}/detail")
    public ResponseEntity<Map<String, Object>> fullDetail(@PathVariable Long id, HttpSession session) {
        Optional<ActivityLog> found = repo.findById(id);
        if (found.isEmpty() || !SessionScope.canViewMonitoring(session, found.get().getTeamId())) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "not_found"));
        }
        if (detailService == null) {
            return ResponseEntity.status(503).body(Map.of("success", false, "error", "unavailable"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(detailService.detail(found.get(), session));
        body.put("timestamp", now());
        return ResponseEntity.ok(body);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Takım kapsamı — AdminController.listAlerts ile aynı desen. */
    private record Scope(boolean scoped, List<Long> list, boolean empty) {}

    private static Scope scope(HttpSession session) {
        // İzleme etkinlik akışı (2026-10-04): 7/24 operatörü tüm takımlarınkini okur.
        List<Long> view = SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIdsWithGrants(session);   // + erişim paylaşımı (2026-10-12)
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

    /**
     * Liste ve kök neden satırı (2026-11-05): {@link #toDto} + bağlantı alanları ({@code check_id, http_status,
     * failure_reason, failure_detail, alert_event_id, storm_id, change_seq, request_id}). {@code GET /{id}} yanıtı
     * birebir kalsın diye {@code toDto}'ya eklenmez.
     */
    private static Map<String, Object> listDto(ActivityLog a) {
        Map<String, Object> m = toDto(a);
        com.sitemonitor.service.activity.ActivityDetailService.putRefs(m, a);
        return m;
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

    private static String now() { return ISO.format(Instant.now()); }
}
