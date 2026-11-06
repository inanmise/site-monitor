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
import org.springframework.web.bind.annotation.PostMapping;
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
 * <ul>
 *   <li>{@code POST   /api/monitoring/keyword/{id}/change/baseline} — canlı ölçümle YENİ temel (MANUAL) + açık değişim alarmını
 *       elle kapatır; oturum başına tek</li>
 *   <li>{@code POST   /api/monitoring/keyword/{id}/matches} — canlı ölçüm, kural başına ilk ≤ 5 eşleşme bağlamı (maskeli);
 *       satır / alarm / temel YOK; oturum başına tek + kullanıcı başına dakikada {@value #MATCHES_PER_MINUTE} (pod belleği)</li>
 * </ul>
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
    /** Canlı ölçüm (temel / eşleşmeler) — izlemenin kontrol tanımı zamanlayıcıdan, ölçüm denetleyiciden (aynı yol). */
    private final com.sitemonitor.service.SchedulerService schedulerService;
    private final com.sitemonitor.service.KeywordCheckerService keywordChecker;
    /** Temel özeti istek parmak izini içerir (hata avı 2026-11-06 H4#3) — özel başlıklar çözülür; yoksa (elle kurulan test) null. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.KeywordHeaderSecrets keywordHeaderSecrets;
    private Clock clock = Clock.systemUTC();

    /** Canlı eşleşme ucunun kullanıcı başına dakikalık sınırı (pod belleği — yük sınırı). */
    static final int MATCHES_PER_MINUTE = 6;
    /** Oturum başına tek canlı ölçüm (temel / eşleşmeler) — istek iş parçacıkları tek oturumca tüketilmesin. */
    static final java.util.Set<String> LIVE_IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>> matchStarts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Testler için saat. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Testler için başlık sırları. */
    void setKeywordHeaderSecrets(com.sitemonitor.service.KeywordHeaderSecrets s) {
        this.keywordHeaderSecrets = s;
    }

    /** Çözülmüş özel başlıklar (yalnız temel özeti için — asla dönmez); çözülemez / bileşen yoksa null. */
    private String headersOf(KeywordMonitor m) {
        if (keywordHeaderSecrets == null) return null;
        try {
            return keywordHeaderSecrets.effectiveHeaders(m);
        } catch (RuntimeException e) {
            return null;
        }
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
        // Başka ayarla (maske / seçici / istek tanımı / algoritma) alınmış temel YOK sayılır (spec_hash) — ekranda da gösterilmez
        String spec = KeywordChangeDetector.specHashFor(m, headersOf(m));
        KeywordChangeBaseline b = baselines.find(m.getId()).filter(x -> spec.equals(x.getSpecHash())).orElse(null);
        boolean canEdit = canEdit(session, m);
        // kw son inceleme O-5: temel METNİ (≤ 64 000 karakter — kimlik bilgili sayfanın tüm görünür metni olabilir) yalnız
        // izlemeyi DÜZENLEYEBİLENE; okuyucu (VIEW paylaşımı, 7/24, AUDIT) text: null + text_hidden: true görür. Fark özeti
        // (latest.summary, ≤ 20 satır) okuyuculara kalır — alarm postası zaten taşıyor.
        Map<String, Object> baseline = KeywordChangeBaselineService.toApi(b, true);
        if (baseline != null && !canEdit) {
            baseline.put("text", null);
            baseline.put("text_hidden", true);
        }
        data.put("baseline", baseline);
        data.put("latest", latest(m.getId()));
        data.put("skipped", skipped(m.getId()));
        data.put("can_edit", canEdit);
        return ResponseEntity.ok(ok(data));
    }

    /**
     * Son kontrolde değişim ölçülmediyse nedeni (kw son inceleme O-3 / D-7): {@code {checked_at, note}} — {@code note} ∈
     * {@code KeywordCheckerService.CHANGE_SKIP_CODES}; son kontrol değişimi ölçtüyse ya da hiç kontrol yoksa {@code null}.
     */
    private Map<String, Object> skipped(Long monitorId) {
        List<KeywordResult> rows = contentQueries.latestRow(monitorId);
        if (rows == null || rows.isEmpty() || rows.get(0).getChangeNote() == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked_at", rows.get(0).getCheckedAt());
        out.put("note", rows.get(0).getChangeNote());
        return out;
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
        boolean closed = closeOpenChangeAlarm(m, session, NOTE_BASELINE_CLEARED);
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
     * olaya dokunulmaz ({@link EscalationService#closableBy} — açan biliniyorsa yalnız açan izleme kapatır). Diğer elle kapanışlar
     * gibi (kw son inceleme ŞÜPHE): çözen = oturumun GÖRÜNEN adı (yoksa kullanıcı adı) ve kapanan her alarm için
     * {@code ALERT_RESOLVE} denetimi. Kapanış hatası temel işlemini geri almaz (WARN). @return en az bir alarm kapandıysa true.
     */
    boolean closeOpenChangeAlarm(KeywordMonitor m, HttpSession session, String note) {
        boolean closed = false;
        String dn = displayName(session);
        String by = dn != null ? dn : username(session);
        try {
            Map<String, Object> owner = new HashMap<>();
            owner.put("monitor_id", m.getId());
            if (m.getTeamId() != null) owner.put("team_id", m.getTeamId());
            owner.put("standalone", true);
            for (AlertEvent e : alertEventRepo.findOpenAlerts(m.getUrl(), EscalationService.TYPE_KEYWORD_CHANGED)) {
                if (e.getId() == null || !EscalationService.closableBy(e, owner)) continue;
                escalationService.resolve(e.getId(), by, note);
                closed = true;
                auditService.recordAction("ALERT_RESOLVE", session, "ALERT_EVENT", String.valueOf(e.getId()),
                        AuditDetail.of("domain", e.getDomain() == null ? "" : AuditDetail.safeTarget(e.getDomain()),
                                "note", note, "via", "keyword_baseline"), null);
            }
        } catch (RuntimeException ex) {
            log.warn("İçerik değişimi alarmı kapatılamadı (izleme {}): {}", m.getId(), ex.toString());
        }
        return closed;
    }

    // ── Yeni temel al (canlı ölçüm) ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/change/baseline")
    public ResponseEntity<Map<String, Object>> takeBaseline(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canOperateTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinin içerik temelini değiştiremezsiniz.",
                    "You cannot change the content baseline of this team's monitor."));
        }
        if (!Boolean.TRUE.equals(m.getChangeDetect())) {
            return conflict("KEYWORD_CHANGE_DISABLED", Msg.t(
                    "Bu izlemede içerik değişimi denetimi kapalı; temel alınamaz. Önce izlemede \"İçerik değişimi\" seçeneğini açın.",
                    "Content change detection is off for this monitor, so no baseline can be taken. Turn on \"Content change\" first."),
                    null);
        }
        String slot = "kw-live:" + session.getId();
        if (!LIVE_IN_FLIGHT.add(slot)) return busy();
        try {
            var capture = new com.sitemonitor.service.keyword.KeywordCheckSpec.ChangeCapture();
            var spec = schedulerService.keywordCheckSpec(m, false).toBuilder()
                    .changeDetect(true).baseline(null).changeCapture(capture).diagnose(true).build();
            Map<String, Object> r = keywordChecker.check(spec);
            if (capture.snapshot() == null) {
                Object reason = r.get("failure_reason");
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("failure_reason", reason);
                extra.put("http_status", r.get("http_status"));
                extra.put("change_skipped", r.get("change_skipped"));   // kw son inceleme O-3 / D-7: neden ölçülmedi
                if (com.sitemonitor.service.KeywordCheckerService.CHANGE_SKIP_SELECTOR_NOT_FOUND.equals(r.get("change_skipped"))) {
                    return conflict("KEYWORD_BASELINE_FETCH_FAILED", Msg.t(
                            "Değişim seçicisi sayfada metin içeren bir öğe bulmadı; yeni temel alınamadı, mevcut temel değişmedi. "
                                    + "Seçiciyi izleme ayarlarından denetleyin.",
                            "The change selector did not match any element with text on the page, so no new baseline was taken; "
                                    + "the current one is unchanged. Check the selector in the monitor settings."), extra);
                }
                return conflict("KEYWORD_BASELINE_FETCH_FAILED", Msg.t(
                        "Sayfa okunamadığı (ya da kabul edilen bir yanıtla dönmediği) için yeni temel alınamadı; mevcut temel değişmedi. "
                                + "Kontrol geçmişindeki nedeni inceleyip yeniden deneyin.",
                        "The page could not be read (or did not return an accepted response), so no new baseline was taken; the "
                                + "current one is unchanged. Check the reason in the check history and try again."), extra);
            }
            KeywordChangeBaseline b = baselines.takeManual(m.getId(), capture.snapshot(), capture.bytes(), capture.specHash(),
                    username(session), displayName(session));
            boolean closed = closeOpenChangeAlarm(m, session, NOTE_BASELINE_TAKEN);
            auditService.recordAction("MONITOR_UPDATE", session, "KEYWORD_MONITOR", String.valueOf(m.getId()),
                    AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "action", "baseline_set",
                            "bytes", capture.bytes(), "closed_alarm", closed), null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("baseline", KeywordChangeBaselineService.toApi(b, true));
            data.put("closed_alarm", closed);
            return ResponseEntity.ok(ok(data));
        } finally {
            LIVE_IN_FLIGHT.remove(slot);
        }
    }

    // ── Canlı eşleşme bağlamları ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/matches")
    public ResponseEntity<Map<String, Object>> matches(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canExecuteTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesini çalıştıramazsınız.", "You cannot run this team's monitor."));
        }
        Long wait = matchesRate(username(session));
        if (wait != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", "RATE_LIMITED");
            body.put("error", Msg.t("Çok sık eşleşme sorgusu; " + wait + " sn sonra yeniden deneyin.",
                    "Too many match lookups; try again in " + wait + " s."));
            body.put("retry_after_seconds", wait);
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(wait)).body(body);
        }
        String slot = "kw-live:" + session.getId();
        if (!LIVE_IN_FLIGHT.add(slot)) return busy();
        try {
            var spec = schedulerService.keywordCheckSpec(m, false).toBuilder().diagnose(false).build();
            var detailed = keywordChecker.checkDetailed(spec);
            Map<String, Object> r = detailed.result();
            auditService.recordAction("MONITOR_DIAGNOSE", session, "KEYWORD_MONITOR", String.valueOf(m.getId()),
                    AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "kind", "matches"), null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fetched_at", ISO.format(clock.instant()));
            data.put("http_status", r.get("http_status"));
            data.put("final_url", r.get("final_url"));
            data.put("error", r.get("error"));
            data.put("failure_reason", r.get("failure_reason"));
            var v = detailed.verdict();
            List<Map<String, Object>> rules = v == null || v.evaluation() == null ? List.of()
                    : com.sitemonitor.service.keyword.rules.KeywordMatchContexts.forRules(spec.rules(), v.evaluation().results(),
                            v.content(), spec.matchScope(), com.sitemonitor.service.KeywordCheckerService.secretValues(spec));
            data.put("rules", rules);
            data.put("truncated", Boolean.TRUE.equals(r.get("body_truncated")));
            return ResponseEntity.ok(ok(data));
        } finally {
            LIVE_IN_FLIGHT.remove(slot);
        }
    }

    /** Kullanıcı başına kayan 1 dakika (pod belleği): sınır doluysa beklenecek saniye, değilse {@code null} (sayıldı). */
    Long matchesRate(String user) {
        String key = user == null ? "?" : user;
        long now = clock.millis();
        java.util.ArrayDeque<Long> q = matchStarts.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000L) q.pollFirst();
            if (q.size() >= MATCHES_PER_MINUTE) return Math.max(1, (60_000L - (now - q.peekFirst()) + 999) / 1000);
            q.addLast(now);
        }
        if (matchStarts.size() > 10_000) matchStarts.clear();
        return null;
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

    private static String displayName(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("displayName");
        return u == null || u.toString().isBlank() ? null : u.toString();
    }

    private static ResponseEntity<Map<String, Object>> busy() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "RATE_LIMITED");
        body.put("error", Msg.t("Bu oturumda bir canlı ölçüm zaten sürüyor — bitmesini bekleyin.",
                "A live measurement is already running in this session — wait for it to finish."));
        return ResponseEntity.status(429).header("Retry-After", "5").body(body);
    }

    private static ResponseEntity<Map<String, Object>> conflict(String code, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        if (extra != null) body.putAll(extra);
        return ResponseEntity.status(409).body(body);
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
