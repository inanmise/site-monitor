package com.sitemonitor.service;

import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.MonitorSchedule;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * "Sizin için — bugün" panelinin İZLEME kartları (2026-09-19; zayıf-algoritma istisnası kartının
 * yerine, kullanıcı seçimi): kararsız (flapping) · yavaşlayan · sessiz/bayat · alan adı kaydı dolan.
 * Dokuz izleme türünü activity_log (tek kaynak) + izleme tabloları üstünden okur.
 *
 * <p>Hesap TÜM takımlar için bir kez yapılır ve 60 sn önbelleğe alınır ({@code today-monitors});
 * takım görünürlüğü {@link TodayPanelService} tarafında satır bazında süzülür — panel her kullanıcıda
 * 2 dk'da bir yoklandığından 100 kullanıcı × 4 tarama yerine dakikada 1 tarama.
 * Her kart kendi try/catch'inde: biri düşerse diğerleri çizilir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodayMonitorInsightsService {

    /** Kararsız: son 24 saatte UP↔DOWN geçiş sayısı eşiği. */
    static final int FLAP_WINDOW_HOURS = 24, FLAP_MIN_TRANSITIONS = 3;
    /** Yavaşlayan: son 24 saat ortalaması, önceki 6 günün tabanına göre ≥1,5× VE ≥100 ms (10→16 ms gürültüsü elensin); iki tarafta da ≥3 örnek. */
    static final double SLOW_RATIO = 1.5;
    static final long SLOW_MIN_DELTA_MS = 100;
    static final int SLOW_MIN_SAMPLES = 3, SLOW_BASELINE_DAYS = 7;
    /** Bayat: son kontrol 2×aralığı (taban 5 dk) aşmış aktif izleme; 7 gün geriye bakılır (idx_act_time), daha eskisi "7+ gündür yok". */
    static final int STALE_FACTOR = 2, STALE_FLOOR_SEC = 300, STALE_LOOKBACK_DAYS = 7;
    /** Alan adı kaydı: 30 gün altı (ve dolmuş). */
    static final int DOMAIN_DAYS = 30;
    /** Yavaşlama için whois gecikmesi anlamsız → DOMAIN dışarıda. */
    private static final Set<String> SLOW_TYPES = Set.of("HTTP", "PORT", "PING", "DNS", "KEYWORD", "PAGE", "PAGESPEED", "SCRIPTED");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ActivityLogRepository activityRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final DomainCheckRepository domainCheckRepo;
    private final HttpMonitorRepository httpRepo;
    private final PortMonitorRepository portRepo;
    private final PingMonitorRepository pingRepo;
    private final DnsMonitorRepository dnsRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PageMonitorRepository pageRepo;
    private final PageSpeedMonitorRepository pageSpeedRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final DomainMonitorRepository domainRepo;

    /** Tüm takımlar için hesaplanmış dört liste + duraklatılmış izleme sayısı. Satırlar {@code team_id} ve {@code domain} taşır (süzme için). */
    public record Snapshot(List<Map<String, Object>> flapping, List<Map<String, Object>> slow,
                           List<Map<String, Object>> stale, int paused, List<Map<String, Object>> domains,
                           int domainsExpired) { }

    @Cacheable(value = "today-monitors", sync = true)
    public Snapshot snapshot() {
        return snapshot(Instant.now());
    }

    /** Test edilebilir çekirdek — {@code now} enjekte edilir (kayan pencere, sabit fixture tarihi YOK). */
    public Snapshot snapshot(Instant now) {
        Map<String, MonitorSchedule> monitors = loadMonitors();
        List<Map<String, Object>> flapping = safe(() -> flapping(now, monitors));
        List<Map<String, Object>> slow = safe(() -> slow(now, monitors));
        int[] paused = {0};
        List<Map<String, Object>> stale = safe(() -> stale(now, monitors, activeInventoryDomains(), paused));
        int[] expired = {0};
        List<Map<String, Object>> domains = safe(() -> domains(monitors, expired));
        return new Snapshot(flapping, slow, stale, paused[0], domains, expired[0]);
    }

    private static List<Map<String, Object>> safe(Supplier<List<Map<String, Object>>> s) {
        try { return s.get(); }
        catch (Exception e) { log.debug("today/monitors kartı atlandı: {}", e.toString()); return List.of(); }
    }

    static String key(String type, Long id) { return type + ":" + id; }

    private Map<String, MonitorSchedule> loadMonitors() {
        Map<String, MonitorSchedule> out = new HashMap<>();
        List<Supplier<List<? extends MonitorSchedule>>> sources = List.of(
                httpRepo::findAll, portRepo::findAll, pingRepo::findAll, dnsRepo::findAll, keywordRepo::findAll,
                pageRepo::findAll, pageSpeedRepo::findAll, scriptedRepo::findAll, domainRepo::findAll);
        for (Supplier<List<? extends MonitorSchedule>> src : sources) {
            try { for (MonitorSchedule m : src.get()) if (m.getId() != null) out.put(key(m.scheduleType(), m.getId()), m); }
            catch (Exception e) { log.debug("today/monitors izleme listesi okunamadı: {}", e.toString()); }
        }
        return out;
    }

    /** Aktif envanter alanları — envanter-türevi DNS/Port izlemesi bunların dışındaysa süpürme onu atlar (öksüz), bayat sayılmaz. */
    private Set<String> activeInventoryDomains() {
        Set<String> out = new HashSet<>();
        try { inventoryRepo.findByActiveTrueOrderByDomainAsc().forEach(i -> { if (i.getDomain() != null) out.add(i.getDomain().toLowerCase()); }); }
        catch (Exception e) { log.debug("today/monitors envanter okunamadı: {}", e.toString()); }
        return out;
    }

    /** Ortak satır başı: tür, id, ad, hedef, alan (görünürlük), takım. */
    private static Map<String, Object> row(MonitorSchedule m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", m.scheduleType()); r.put("monitor_id", m.getId()); r.put("name", m.getName());
        r.put("target", m.scheduleTarget()); r.put("domain", hostOf(m.scheduleTarget()));
        r.put("team_id", m.getTeamId());
        return r;
    }

    /** URL/host:port → çıplak host (envanter alanıyla eşleşsin; takımsız DNS/Port izlemeleri buradan görünür olur). */
    static String hostOf(String target) {
        if (target == null || target.isBlank()) return null;
        String s = target.trim();
        int i = s.indexOf("://"); if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/'); if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@'); if (at >= 0) s = s.substring(at + 1);
        if (!s.startsWith("[")) { int colon = s.indexOf(':'); if (colon >= 0) s = s.substring(0, colon); }
        return s.isBlank() ? null : s.toLowerCase();
    }

    private static boolean bad(String status) { return "ERROR".equals(status) || "TIMEOUT".equals(status); }

    // ── 1) Kararsız (flapping) ──────────────────────────────────────────────────────────────
    private List<Map<String, Object>> flapping(Instant now, Map<String, MonitorSchedule> monitors) {
        String since = ISO.format(now.minus(Duration.ofHours(FLAP_WINDOW_HOURS)));
        Map<String, List<Long>> idsByType = new LinkedHashMap<>();
        for (Object[] r : activityRepo.monitorsWithFailureSince(since))
            idsByType.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(((Number) r[1]).longValue());
        List<Map<String, Object>> items = new ArrayList<>();
        for (var e : idsByType.entrySet()) {
            String type = e.getKey();
            Long curId = null; Boolean prevBad = null; int transitions = 0; String lastStatus = null, lastTime = null;
            List<Object[]> seq = activityRepo.statusSequence(type, e.getValue(), since);
            for (int i = 0; i <= seq.size(); i++) {
                Object[] r = i < seq.size() ? seq.get(i) : null;
                Long id = r == null ? null : ((Number) r[0]).longValue();
                if (r == null || !id.equals(curId)) {
                    if (curId != null && transitions >= FLAP_MIN_TRANSITIONS) {
                        MonitorSchedule m = monitors.get(key(type, curId));
                        if (m != null && Boolean.TRUE.equals(m.getActive())) {
                            Map<String, Object> row = row(m);
                            row.put("transitions", transitions); row.put("last_status", bad(lastStatus) ? "DOWN" : "UP"); row.put("last_time", lastTime);
                            items.add(row);
                        }
                    }
                    if (r == null) break;
                    curId = id; prevBad = null; transitions = 0;
                }
                boolean b = bad((String) r[2]);
                if (prevBad != null && prevBad != b) transitions++;
                prevBad = b; lastStatus = (String) r[2]; lastTime = (String) r[1];
            }
        }
        items.sort(Comparator.<Map<String, Object>>comparingInt(m -> -(Integer) m.get("transitions"))
                .thenComparing(m -> String.valueOf(m.get("name"))));
        return items;
    }

    // ── 2) Yavaşlayan ────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> slow(Instant now, Map<String, MonitorSchedule> monitors) {
        String dayAgo = ISO.format(now.minus(Duration.ofHours(24)));
        String baseFrom = ISO.format(now.minus(Duration.ofDays(SLOW_BASELINE_DAYS)));
        Map<String, double[]> today = avg(activityRepo.avgResponseByMonitor(dayAgo, "9999-12-31T23:59:59"));
        Map<String, double[]> base = avg(activityRepo.avgResponseByMonitor(baseFrom, dayAgo));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var e : today.entrySet()) {
            double[] t = e.getValue(), b = base.get(e.getKey());
            if (b == null || t[1] < SLOW_MIN_SAMPLES || b[1] < SLOW_MIN_SAMPLES || b[0] <= 0) continue;
            if (t[0] < b[0] * SLOW_RATIO || t[0] - b[0] < SLOW_MIN_DELTA_MS) continue;
            MonitorSchedule m = monitors.get(e.getKey());
            if (m == null || !Boolean.TRUE.equals(m.getActive()) || !SLOW_TYPES.contains(m.scheduleType())) continue;
            Map<String, Object> row = row(m);
            row.put("today_ms", Math.round(t[0])); row.put("baseline_ms", Math.round(b[0]));
            row.put("ratio", Math.round(t[0] / b[0] * 10) / 10.0); row.put("samples", (int) t[1]);
            items.add(row);
        }
        items.sort(Comparator.comparingDouble((Map<String, Object> m) -> -(Double) m.get("ratio")));
        return items;
    }

    /** (tür, id, avg, count) satırları → "TYPE:id" → {avg, count}. */
    private static Map<String, double[]> avg(List<Object[]> rows) {
        Map<String, double[]> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[2] == null) continue;
            out.put(key((String) r[0], ((Number) r[1]).longValue()),
                    new double[]{((Number) r[2]).doubleValue(), ((Number) r[3]).doubleValue()});
        }
        return out;
    }

    // ── 3) Sessiz / bayat ────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> stale(Instant now, Map<String, MonitorSchedule> monitors, Set<String> activeDomains, int[] paused) {
        Instant sinceAt = now.minus(Duration.ofDays(STALE_LOOKBACK_DAYS));
        String since = ISO.format(sinceAt);
        Map<String, String> last = new HashMap<>();
        for (Object[] r : activityRepo.lastCheckByMonitor(since))
            if (r[2] != null) last.put(key((String) r[0], ((Number) r[1]).longValue()), (String) r[2]);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MonitorSchedule m : monitors.values()) {
            if (!Boolean.TRUE.equals(m.getActive())) { paused[0]++; continue; }
            // Envanter-türevi DNS/Port: alanı aktif envanterde değilse süpürme BİLEREK atlıyor (öksüz) → bayat değil.
            if (!m.scheduleStandalone() && !activeDomains.contains(String.valueOf(hostOf(m.scheduleTarget())))) continue;
            int interval = m.getIntervalSeconds() != null && m.getIntervalSeconds() > 0 ? m.getIntervalSeconds() : STALE_FLOOR_SEC;
            long thresholdSec = Math.max((long) interval * STALE_FACTOR, STALE_FLOOR_SEC);
            String lastAt = last.get(key(m.scheduleType(), m.getId()));
            Instant created = parse(m.getCreatedAt());
            // Pencerede kontrol yok: pencereden ÖNCE yaratılmışsa "7+ gündür kontrol yok" (yaş pencereyle sınırlı —
            // tam tarih için tüm tabloyu taramayız); pencerede yaratılmışsa yaratılış anından ölç ("hiç kontrol edilmedi").
            boolean never = lastAt == null && created != null && created.isAfter(sinceAt);
            Instant ref = lastAt != null ? parse(lastAt) : (created == null || created.isBefore(sinceAt) ? sinceAt : created);
            if (ref == null) continue;
            long ageSec = Duration.between(ref, now).getSeconds();
            if (ageSec <= thresholdSec) continue;
            Map<String, Object> row = row(m);
            row.put("last_check", lastAt); row.put("age_min", ageSec / 60); row.put("never", never);
            row.put("interval_sec", interval); row.put("expected_min", thresholdSec / 60);
            items.add(row);
        }
        items.sort(Comparator.comparingLong((Map<String, Object> m) -> -(Long) m.get("age_min")));
        return items;
    }

    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC); }
        catch (Exception e) { return null; }
    }

    // ── 4) Alan adı kaydı dolan ──────────────────────────────────────────────────────────────
    private List<Map<String, Object>> domains(Map<String, MonitorSchedule> monitors, int[] expired) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DomainCheck c : domainCheckRepo.findLatestPerMonitor()) {
            if (c.getMonitorId() == null || c.getDaysRemaining() == null || c.getDaysRemaining() > DOMAIN_DAYS) continue;
            MonitorSchedule m = monitors.get(key("DOMAIN", c.getMonitorId()));
            if (m == null || !Boolean.TRUE.equals(m.getActive())) continue;
            if (c.getDaysRemaining() < 0) expired[0]++;
            Map<String, Object> row = row(m);
            row.put("days", c.getDaysRemaining()); row.put("expiry_date", c.getExpiryDate()); row.put("registrar", c.getRegistrar());
            items.add(row);
        }
        items.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("days")));
        return items;
    }
}
