package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SystemHeartbeat;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.SystemHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtendedHealthService {

    private final NotificationLogRepository notificationLogRepo;
    private final SystemHeartbeatRepository heartbeatRepo;
    private final AlertEventRepository alertEventRepo;
    private final JdbcTemplate jdbcTemplate;
    private final RdapDomainClient rdapDomainClient;
    private final com.sitemonitor.repository.RetentionRunRepository retentionRunRepo;

    /** Dağıtım kaydının last_seen_at'ı heartbeat ritminde tazelenir (hard-kill'de son görülme kalır). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeploymentHistoryService deploymentHistory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SchemaTableRegistryService schemaRegistry;
    private final AppSettingsService appSettings;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SchedulerService schedulerService;

    /** Küme durumu deposu (2026-10-23, çok pod R2b) — bean yoksa (elle kurulum / test) kendi JdbcTemplate'imizle kurulur. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.health.ClusterHealthStore clusterHealthStore;
    private volatile com.sitemonitor.service.health.ClusterHealthStore fallbackStore;

    /** Bu pod'un örnek kimliği — testler iki "pod"u ayrı kimlikle canlandırır. */
    java.util.function.Supplier<String> instanceIdSupplier = SchedulerService::instanceId;

    /** Bu pod'un en son yayınladığı RDAP gözleminin anı (epoch ms; -1 = hiç). */
    private volatile long lastPublishedRdapMs = -1L;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Pod son heartbeat'i bu kadar dakikadan yeniyse canlı (Metrikler sayfasıyla aynı kural, {@code LIVE_MINUTES}). */
    static final int POD_LIVE_MINUTES = 3;
    /** Heartbeat pod listesinin penceresi (saat) ve en çok satır. */
    static final int POD_WINDOW_HOURS = 24;
    static final int POD_LIST_MAX = 20;
    /** Zaman çizelgesindeki pod başına satır tavanı (30 gün × sık dağıtım = yüzlerce örnek). */
    static final int TIMELINE_POD_MAX = 12;

    com.sitemonitor.service.health.ClusterHealthStore store() {
        com.sitemonitor.service.health.ClusterHealthStore s = clusterHealthStore;
        if (s != null) return s;
        s = fallbackStore;
        if (s == null) {
            s = new com.sitemonitor.service.health.ClusterHealthStore(jdbcTemplate);
            fallbackStore = s;
        }
        return s;
    }

    private String instanceId() {
        return instanceIdSupplier.get();
    }

    /**
     * Domain-expiry veri kaynağı (RDAP) durumu — Sistem Sağlığı kartı için. <b>Küme geneli</b> (2026-10-23, çok pod R2b, N4):
     * RDAP sorgusunu yalnız alan adı taramalarını koşturan pod (tarama lideri) — ve elle tanı / kayıt sekmesi açan pod —
     * yapar; durum o pod'un belleğindeydi, takipçi "boşta" diyor ve RDAP kesintisini gizliyordu. Her pod son gözlemini
     * heartbeat ritminde {@code cluster_observations}'a yayınlar ({@link #publishDomainExpirySource}); burada bu pod'un
     * belleği ile kümedeki son gözlemden YENİ olanı döner, {@code observed_by} (gözlemleyen pod) ve {@code scope}
     * ({@code cluster}; tablo okunamazsa {@code local} — yalnız bu pod) ile. Hiçbir pod sorgu yapmadıysa {@code IDLE}.
     */
    public Map<String, Object> getDomainExpirySourceStatus() {
        Map<String, Object> local;
        try {
            local = new LinkedHashMap<>(rdapDomainClient.getSourceStatus());
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", "NONE");
            m.put("alarm", true);
            m.put("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            m.put("scope", "local");
            m.put("observed_by", instanceId());
            m.put("observed_by_pod", com.sitemonitor.service.health.ClusterHealthStore.podOf(instanceId()));
            return m;
        }
        long localMs = rdapEventMs(local);
        java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> row;
        try {
            row = store().read(com.sitemonitor.service.health.ClusterHealthStore.OBS_RDAP);
        } catch (Exception e) {
            log.debug("RDAP küme gözlemi okunamadı — bu pod'un durumu gösteriliyor: {}", e.toString());
            return withObserver(local, "local", localMs >= 0 ? instanceId() : null, localMs);
        }
        if (row.isPresent() && row.get().observedMs() > localMs) {
            Map<String, Object> remote = parsePayload(row.get().payload());
            if (remote != null) return withObserver(remote, "cluster", row.get().instanceId(), row.get().observedMs());
        }
        return withObserver(local, "cluster", localMs >= 0 ? instanceId() : null, localMs);
    }

    private static Map<String, Object> withObserver(Map<String, Object> m, String scope, String by, long atMs) {
        m.put("scope", scope);
        m.put("observed_by", by);
        m.put("observed_by_pod", com.sitemonitor.service.health.ClusterHealthStore.podOf(by));
        m.put("observed_at", atMs >= 0 ? java.time.Instant.ofEpochMilli(atMs).toString() : null);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object o = JSON.readValue(json, Map.class);
            return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** RDAP durumunun son olay anı (son başarı / son hata — hangisi yeniyse), epoch ms; hiç sorgu yoksa -1. */
    static long rdapEventMs(Map<String, Object> s) {
        long best = -1L;
        if (s == null) return best;
        for (String k : new String[] {"last_success", "last_error"}) {
            Object v = s.get(k);
            if (v == null) continue;
            try { best = Math.max(best, java.time.Instant.parse(String.valueOf(v)).toEpochMilli()); }
            catch (Exception ignore) { /* biçim dışı → yok say */ }
        }
        return best;
    }

    /**
     * Bu pod'un RDAP gözlemini kümeye yayınlar (heartbeat ritmi): yalnız son yayından bu yana YENİ bir sorgu olduysa ve
     * satırdakinden yeniyse yazılır (pod başına en çok dakikada bir yazım; sorgu yoksa hiç). Hiç fırlatmaz.
     */
    void publishDomainExpirySource() {
        try {
            Map<String, Object> s = rdapDomainClient.getSourceStatus();
            long ms = rdapEventMs(s);
            if (ms < 0 || ms <= lastPublishedRdapMs) return;
            var r = store().publish(com.sitemonitor.service.health.ClusterHealthStore.OBS_RDAP, instanceId(), ms,
                    JSON.writeValueAsString(s), LocalDateTime.now(ZoneOffset.UTC).withNano(0).toString());
            if (r != com.sitemonitor.service.health.ClusterHealthStore.Publish.FAILED) lastPublishedRdapMs = ms;
        } catch (Exception e) {
            log.debug("RDAP gözlemi yayınlanamadı: {}", e.toString());
        }
    }

    public Map<String, Object> getNetworkStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (schedulerService == null) {
            m.put("alarm", false);
            return m;
        }
        // Durum veritabanından (2026-10-21, çok pod — MP-01): tek okuma → alanlar aynı andan; hangi pod cevaplarsa aynı.
        com.sitemonitor.service.outage.CertNetworkOutageService.Snapshot s = schedulerService.networkOutageSnapshot();
        if (s != null) {
            m.put("alarm",               s.active());
            m.put("detected_at",         s.detectedAt());
            m.put("resolved_at",         s.resolvedAt());
            m.put("last_error_rate",     s.lastErrorRate());
            m.put("last_network_errors", s.lastNetworkErrors());
            m.put("last_total",          s.lastTotal());
            m.put("threshold",           schedulerService.getErrorRateThreshold());
            m.put("min_errors",          schedulerService.getMinNetworkErrors());
            m.put("pending_alert_email", s.pendingAlertEmail());
            m.put("pending_resolved_email", s.pendingResolvedEmail());
            return m;
        }
        m.put("alarm",               schedulerService.isNetworkOutageActive());
        m.put("detected_at",         schedulerService.getNetworkOutageDetectedAt());
        m.put("resolved_at",         schedulerService.getNetworkOutageResolvedAt());
        m.put("last_error_rate",     schedulerService.getNetworkLastErrorRate());
        m.put("last_network_errors", schedulerService.getNetworkLastNetworkErrors());
        m.put("last_total",          schedulerService.getNetworkLastTotal());
        m.put("threshold",           schedulerService.getErrorRateThreshold());
        m.put("min_errors",          schedulerService.getMinNetworkErrors());
        m.put("pending_alert_email", schedulerService.isPendingAdminAlertEmail());
        m.put("pending_resolved_email", schedulerService.isPendingAdminResolvedEmail());
        return m;
    }

    @Value("${site.monitor.email.from:noreply@sitemonitor}")
    private String emailFrom;

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    // fixedDelay (fixedRate değil): DB yavaşlarsa çalışmalar üst üste binmesin (leak analizi #5).
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void recordHeartbeat() {
        try {
            SystemHeartbeat hb = new SystemHeartbeat();
            hb.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            hb.setInstanceId(instanceId());   // çok pod (2026-10-23, R2b, MP-35): hangi pod yazdı
            heartbeatRepo.save(hb);
            log.debug("Heartbeat recorded at {}", hb.getRecordedAt());
            if (deploymentHistory != null) deploymentHistory.touch();
            if (schemaRegistry != null) schemaRegistry.tick();   // dakika hassasiyetinde "son veri değişimi"
        } catch (Exception e) {
            log.warn("Failed to record heartbeat: {}", e.getMessage());
            return;
        }
        publishDomainExpirySource();   // RDAP kaynağının son gözlemi kümeye (N4) — hiç fırlatmaz
    }

    /**
     * Düzgün kapanış işareti (2026-10-23, çok pod R2b, MP-35): pod listesinde bu örnek "durdu" görünür (dağıtım /
     * ölçekleme — uyarı değil); işaret yazılamadan ölen pod "sessiz" kalır (uyarı). Sayımlara katılmaz. Hiç fırlatmaz.
     */
    @jakarta.annotation.PreDestroy
    public void recordShutdownMarker() {
        try {
            SystemHeartbeat hb = new SystemHeartbeat();
            hb.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            hb.setInstanceId(instanceId());
            hb.setShutdownMarker(Boolean.TRUE);
            heartbeatRepo.save(hb);
        } catch (Exception e) {
            log.debug("Heartbeat kapanış işareti yazılamadı: {}", e.toString());
        }
    }

    /**
     * Heartbeat durumu — <b>küme geneli</b> (2026-10-23, çok pod R2b, MP-35): son sinyal ve alarm HERHANGİ bir pod'un son
     * heartbeat'idir ("uygulama yaşıyor mu"); {@code pods} her pod'un ayrı durumudur (son {@value #POD_WINDOW_HOURS} saat):
     * {@code live} (son heartbeat < {@value #POD_LIVE_MINUTES} dk), {@code silent} (işaretsiz sustu — çöktü / zamanlayıcısı
     * takıldı: UYARI), {@code restarted} (sustu ama aynı adlı pod'un yeni süreci canlı — konteyner yeniden başladı, uyarı
     * değil), {@code stopped} (kapanış işareti — dağıtım / ölçekleme). Eskiden tek bir "son heartbeat" vardı: ikinci pod
     * yazmaya devam ettikçe ölen / takılan pod hiç görünmüyordu. Kapanış işaretleri son sinyal sayılmaz.
     */
    public Map<String, Object> getHeartbeatStatus() {
        try {
            List<SystemHeartbeat> recent5 = heartbeatRepo.findTop5ByOrderByRecordedAtDesc().stream()
                    .filter(hb -> !Boolean.TRUE.equals(hb.getShutdownMarker()))
                    .toList();
            if (recent5.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("last_heartbeat", null);
                m.put("minutes_since", -1);
                m.put("alarm", true);
                m.put("recent", List.of());
                putPods(m);
                return m;
            }
            LocalDateTime lastTs = recent5.get(0).getRecordedAt();
            long minutes = ChronoUnit.MINUTES.between(lastTs, LocalDateTime.now(ZoneOffset.UTC));
            List<String> recentList = recent5.stream()
                    .map(hb -> hb.getRecordedAt().toString())
                    .collect(java.util.stream.Collectors.toList());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("last_heartbeat", lastTs.toString());
            m.put("minutes_since", minutes);
            m.put("alarm", minutes > 3);
            m.put("recent", recentList);
            putPods(m);
            return m;
        } catch (Exception e) {
            log.warn("getHeartbeatStatus failed: {}", e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("last_heartbeat", null);
            m.put("minutes_since", -1);
            m.put("alarm", true);
            m.put("recent", List.of());
            m.put("error", e.getMessage());
            return m;
        }
    }

    /** Pod listesi + sayaçlar; okunamazsa {@code pods_error} (pod listesi bilinmiyor — "hepsi canlı" denmez). */
    private void putPods(Map<String, Object> m) {
        m.put("served_by", instanceId());
        m.put("served_by_pod", com.sitemonitor.service.health.ClusterHealthStore.podOf(instanceId()));
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            List<Map<String, Object>> pods = podStates(heartbeatRepo.podSummariesSince(now.minusHours(POD_WINDOW_HOURS)), now);
            m.put("pods_total", pods.size());
            m.put("pods_live", pods.stream().filter(p -> "live".equals(p.get("state"))).count());
            m.put("pods_silent", pods.stream().filter(p -> "silent".equals(p.get("state"))).count());
            m.put("pods", pods.size() > POD_LIST_MAX ? new ArrayList<>(pods.subList(0, POD_LIST_MAX)) : pods);
        } catch (Exception e) {
            log.debug("Heartbeat pod listesi okunamadı: {}", e.toString());
            m.put("pods", null);
            m.put("pods_error", e.getClass().getSimpleName());
        }
    }

    /**
     * Pod özet satırları ({@code [instanceId, ilk, son, işaret sayısı]}) → durumlu, sıralı liste (canlı → sessiz →
     * yeniden başladı → durdu; aynı durumda en yeni süreç önce). Aynı adlı birden çok süreç kimliğin son ekiyle ayrılır.
     */
    static List<Map<String, Object>> podStates(List<Object[]> rows, LocalDateTime now) {
        List<Map<String, Object>> all = new ArrayList<>();
        LocalDateTime liveSince = now.minusMinutes(POD_LIVE_MINUTES);
        java.util.Set<String> livePods = new java.util.HashSet<>();
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            if (r == null || r.length < 3 || r[0] == null || !(r[2] instanceof LocalDateTime last)) continue;
            String id = String.valueOf(r[0]);
            LocalDateTime first = r[1] instanceof LocalDateTime f ? f : last;
            boolean marker = r.length > 3 && r[3] instanceof Number n && n.longValue() > 0;
            String state = marker ? "stopped" : !last.isBefore(liveSince) ? "live" : "silent";
            String pod = com.sitemonitor.service.health.ClusterHealthStore.podOf(id);
            if ("live".equals(state)) livePods.add(pod);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("pod", pod);
            m.put("name", pod);
            m.put("state", state);
            m.put("first_seen", first.toString());
            m.put("last_seen", last.toString());
            m.put("minutes_since", Math.max(0L, ChronoUnit.MINUTES.between(last, now)));
            all.add(m);
        }
        Map<String, Integer> sameName = new java.util.HashMap<>();
        for (Map<String, Object> m : all) sameName.merge(String.valueOf(m.get("pod")), 1, Integer::sum);
        for (Map<String, Object> m : all) {
            String pod = String.valueOf(m.get("pod"));
            if ("silent".equals(m.get("state")) && livePods.contains(pod)) m.put("state", "restarted");
            if (sameName.get(pod) > 1) m.put("name", pod + " · " + suffixOf(String.valueOf(m.get("id"))));
        }
        all.sort(java.util.Comparator
                .<Map<String, Object>>comparingInt(m -> podRank(m.get("state")))
                .thenComparing(m -> String.valueOf(m.get("first_seen")), java.util.Comparator.reverseOrder())
                .thenComparing(m -> String.valueOf(m.get("id"))));
        return all;
    }

    private static int podRank(Object state) {
        if ("live".equals(state)) return 0;
        if ("silent".equals(state)) return 1;
        if ("restarted".equals(state)) return 2;
        return 3;
    }

    private static String suffixOf(String id) {
        int dash = id.lastIndexOf('-');
        return dash >= 0 && dash < id.length() - 1 ? id.substring(dash + 1) : id;
    }

    /**
     * Gece temizliğinin kendi sağlığı. Bir izleme ürününün kendi temizlik job'ını izlememesi
     * ironisini kapatır: sessizce çöken bir cleanup, disk dolduğunda değil ertesi sabah bu
     * sinyalde fark edilir. Alarm eşiği 36 saat (günlük cron + kaçırılan bir gece toleransı).
     */
    public Map<String, Object> getCleanupStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            boolean hold = appSettings.getBoolean("site.monitor.retention.hold-enabled", false);
            var last = retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc().orElse(null);
            m.put("hold_active", hold);
            if (last == null) {
                m.put("last_run", null);
                m.put("hours_since", -1);
                // Hiç çalışmamış olmak yeni kurulumda normaldir; legal hold varken de alarm üretme.
                m.put("alarm", false);
                m.put("never_run", true);
                return m;
            }
            long hours = ChronoUnit.HOURS.between(
                    java.time.LocalDateTime.parse(last.getStartedAt()), LocalDateTime.now(ZoneOffset.UTC));
            m.put("last_run", last.getStartedAt());
            m.put("hours_since", hours);
            m.put("total_deleted", last.getTotalDeleted());
            m.put("failed_count", last.getFailedCount());
            m.put("duration_ms", last.getDurationMs());
            m.put("never_run", false);
            m.put("alarm", !hold && (hours > 36 || last.getFailedCount() != null && last.getFailedCount() > 0));
            return m;
        } catch (Exception e) {
            log.warn("getCleanupStatus failed: {}", e.getMessage());
            m.put("last_run", null);
            m.put("hours_since", -1);
            m.put("alarm", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    /**
     * Heartbeat zaman çizelgesi — <b>küme</b> (2026-10-23, çok pod R2b, MP-35): kovada heartbeat ALAN dakika sayısı
     * (farklı dakika; iki pod aynı dakikada yazınca 1). Eskiden satır sayılıyordu: 2 pod → "2880 / 1440 alınan", bir pod
     * ölse de şerit yeşil kalıyordu. {@code pods}: pod başına alınan / beklenen dakika (ilk heartbeat'inden son
     * heartbeat'ine — canlıysa şimdiye); en yeni {@value #TIMELINE_POD_MAX} süreç, fazlası {@code pods_more}.
     */
    public Map<String, Object> getHeartbeatTimeline(int days) {
        int d = Math.max(1, Math.min(days, 30));
        int bucketMinutes = (d == 1) ? 10 : 60;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        LocalDateTime cutoff = now.minusDays(d);
        int alignMin = cutoff.getMinute() % bucketMinutes;
        cutoff = cutoff.minusMinutes(alignMin);

        long totalMinutes = ChronoUnit.MINUTES.between(cutoff, now);
        // Kova sayısı YUKARI yuvarlanır: cutoff kova sınırına geri çekildiği için pencere tam kova
        // katı değildir; aşağı yuvarlama "şimdi"yi içeren son (kısmi) kovayı düşürüyor, en yeni
        // heartbeat'ler (< kova süresi) hiçbir kovaya düşmüyordu (2026-09-11). Son kovanın
        // `expected`i kalan dakika kadardır — "10 beklenen / 3 gelen" yanlış kaybı üretmesin.
        int totalBuckets = (int) Math.ceil(totalMinutes / (double) bucketMinutes);
        if (totalBuckets <= 0) totalBuckets = 1;
        int span = totalBuckets * bucketMinutes;

        java.util.BitSet cluster = new java.util.BitSet(span);
        Map<String, java.util.BitSet> perPod = new LinkedHashMap<>();
        for (Object[] row : heartbeatRepo.findBeatsAfter(cutoff)) {
            if (row == null || row.length < 1 || !(row[0] instanceof LocalDateTime at)) continue;
            long minute = ChronoUnit.MINUTES.between(cutoff, at);
            if (minute < 0 || minute >= span) continue;
            cluster.set((int) minute);
            Object id = row.length > 1 ? row[1] : null;
            if (id != null) perPod.computeIfAbsent(String.valueOf(id), k -> new java.util.BitSet(span)).set((int) minute);
        }

        List<Map<String, Object>> buckets = new ArrayList<>(totalBuckets);
        for (int i = 0; i < totalBuckets; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            int bucketStartMin = i * bucketMinutes;
            // Son kova kısmi olabilir: beklenen = pencerede kalan dakika (en az 1).
            int expected = (int) Math.max(1, Math.min(bucketMinutes, totalMinutes - bucketStartMin));
            b.put("start",    cutoff.plusMinutes(bucketStartMin).toString());
            b.put("expected", expected);
            b.put("received", cluster.get(bucketStartMin, bucketStartMin + bucketMinutes).cardinality());
            buckets.add(b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days",           d);
        out.put("bucket_minutes", bucketMinutes);
        out.put("counting",       "distinct_minutes");
        out.put("buckets",        buckets);
        out.put("served_by",      instanceId());
        putTimelinePods(out, perPod, cutoff, now, totalMinutes);
        return out;
    }

    private void putTimelinePods(Map<String, Object> out, Map<String, java.util.BitSet> perPod, LocalDateTime cutoff,
                                 LocalDateTime now, long totalMinutes) {
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        try {
            for (Map<String, Object> p : podStates(heartbeatRepo.podSummariesSince(cutoff), now)) states.put(String.valueOf(p.get("id")), p);
        } catch (Exception e) {
            log.debug("Zaman çizelgesi pod durumları okunamadı: {}", e.toString());
        }
        List<Map<String, Object>> pods = new ArrayList<>();
        for (Map.Entry<String, java.util.BitSet> e : perPod.entrySet()) {
            java.util.BitSet bits = e.getValue();
            int first = bits.nextSetBit(0);
            int last = bits.length() - 1;
            if (first < 0) continue;
            Map<String, Object> st = states.get(e.getKey());
            String state = st != null ? String.valueOf(st.get("state")) : null;
            // Canlı pod şimdiye kadar beklenir; diğerleri son heartbeat'lerine kadar (durdu / sustu sonrası "kayıp" değil).
            long end = "live".equals(state) ? Math.max(last, totalMinutes - 1) : last;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", st != null ? st.get("name") : com.sitemonitor.service.health.ClusterHealthStore.podOf(e.getKey()));
            m.put("state", state);
            m.put("first_seen", cutoff.plusMinutes(first).toString());
            m.put("last_seen", cutoff.plusMinutes(last).toString());
            m.put("received", bits.cardinality());
            m.put("expected", (int) Math.max(1L, end - first + 1));
            pods.add(m);
        }
        pods.sort(java.util.Comparator
                .<Map<String, Object>>comparingInt(m -> podRank(m.get("state")))
                .thenComparing(m -> String.valueOf(m.get("last_seen")), java.util.Comparator.reverseOrder())
                .thenComparing(m -> String.valueOf(m.get("id"))));
        out.put("pods_more", Math.max(0, pods.size() - TIMELINE_POD_MAX));
        out.put("pods", pods.size() > TIMELINE_POD_MAX ? new ArrayList<>(pods.subList(0, TIMELINE_POD_MAX)) : pods);
    }

    // ── SMTP stats ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getSmtpFailures() {
        return getSmtpFailures(30);
    }

    public List<Map<String, Object>> getSmtpFailures(int days) {
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<NotificationLog> logs = notificationLogRepo.findAllSince(cutoff);
        Map<Long, String> aeToDomain = aeIdToDomainMap(logs);
        // 7/24 satırı MASKELİ (A3, 2026-09-28): gövdedeki arama listesi telefonları ve 7/24 grup adresleri
        // SmtpLogQueryService / alarm bildirim listesiyle aynı biçimde gizlenir — bu uç atlıyordu.
        return logs.stream()
                .map(com.sitemonitor.service.noc.NocLogRedaction::forViewer)
                .map(n -> {
                    String status = n.getEmailStatus() != null ? n.getEmailStatus() : "";
                    String kind   = status.equals("SENT") ? "SENT"
                                  : status.startsWith("FAILED") ? "FAILED"
                                  : status.startsWith("SKIPPED") ? "SKIPPED" : "UNKNOWN";
                    String error  = (status.startsWith("FAILED") || status.startsWith("SKIPPED"))
                                  ? status.replaceFirst("^(FAILED|SKIPPED)[_:]?\\s*", "").trim() : "";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",              n.getId());
                    m.put("alert_event_id",  n.getAlertEventId());
                    m.put("domain",          aeToDomain.get(n.getAlertEventId()));
                    m.put("sent_at",         n.getSentAt());
                    m.put("sender_email",    emailFrom);
                    m.put("recipient_name",  n.getRecipientName());
                    m.put("recipient_email", n.getRecipientEmail());
                    m.put("subject",         n.getSubject());
                    m.put("message",         n.getMessage());
                    m.put("kind",            kind);
                    m.put("error",           error);
                    m.put("trigger",         n.getTrigger());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<Long, String> aeIdToDomainMap(List<NotificationLog> logs) {
        java.util.Set<Long> ids = logs.stream()
                .map(NotificationLog::getAlertEventId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return java.util.Collections.emptyMap();
        return alertEventRepo.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(AlertEvent::getId, AlertEvent::getDomain));
    }

    /** Domains whose last N consecutive non-SKIPPED mail attempts (within the last
     *  {@code days} days) are all FAILED. SKIPPED_DISABLED is excluded from the
     *  "last N" window — it represents an admin choice, not a delivery failure. */
    // Global veri (kullanıcıya özel değil), her /api/notifications/failure-domains isteğinde 7 günlük
    // notification_logs taraması. 100 kullanıcı × 5 dk dashboard fan-out'unda 100× tekrar ediyordu →
    // cache'le (sync: eşzamanlı çağrılar tek hesaba iner). TTL 300 sn CacheConfig'te; anahtar (consecutive,days).
    @Cacheable(value = "failure-domains", sync = true)
    public List<String> findDomainsWithConsecutiveMailFailures(int consecutive, int days) {
        if (consecutive < 1) return List.of();
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<NotificationLog> recent = notificationLogRepo.findAllSince(cutoff).stream()
                .filter(n -> n.getEmailStatus() != null && !n.getEmailStatus().startsWith("SKIPPED"))
                .filter(n -> n.getAlertEventId() != null)
                .collect(java.util.stream.Collectors.toList());
        if (recent.isEmpty()) return List.of();

        Map<Long, String> aeToDomain = aeIdToDomainMap(recent);
        Map<String, List<NotificationLog>> byDomain = recent.stream()
                .filter(n -> aeToDomain.containsKey(n.getAlertEventId()))
                .collect(java.util.stream.Collectors.groupingBy(
                        n -> aeToDomain.get(n.getAlertEventId())));

        java.util.Comparator<NotificationLog> bySentAtDesc = (a, b) -> {
            String sa = a.getSentAt();
            String sb = b.getSentAt();
            if (sa == null && sb == null) return 0;
            if (sa == null) return 1;   // nulls last
            if (sb == null) return -1;
            return sb.compareTo(sa);    // DESC
        };

        List<String> failing = new java.util.ArrayList<>();
        for (Map.Entry<String, List<NotificationLog>> e : byDomain.entrySet()) {
            List<NotificationLog> top = e.getValue().stream()
                    .sorted(bySentAtDesc)
                    .limit(consecutive)
                    .collect(java.util.stream.Collectors.toList());
            if (top.size() < consecutive) continue;
            boolean allFailed = top.stream()
                    .allMatch(n -> n.getEmailStatus() != null && n.getEmailStatus().startsWith("FAILED"));
            if (allFailed) failing.add(e.getKey());
        }
        java.util.Collections.sort(failing);
        return failing;
    }

    public Map<String, Object> getSmtpStats() {
        try {
            int[] days = { 1, 7, 15, 30 };
            Map<String, Map<String, Object>> periods = new LinkedHashMap<>();
            for (int d : days) {
                periods.put(d + "d", computeSmtpPeriod(d));
            }
            // Top-level fields mirror 30d defaults for backward compatibility (existing clients/tests).
            Map<String, Object> m = new LinkedHashMap<>(periods.get("30d"));
            m.put("periods", periods);
            return m;
        } catch (Exception e) {
            log.warn("getSmtpStats failed: {}", e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", 0);
            m.put("attempted", 0);
            m.put("sent", 0);
            m.put("rate", 100);
            m.put("alarm", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    private Map<String, Object> computeSmtpPeriod(int days) {
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // attempted = SENT + FAILED (excludes intentional SKIPPED_DISABLED)
        long attempted = notificationLogRepo.countAttemptedSince(cutoff);
        long sent      = notificationLogRepo.countSentSince(cutoff);
        long total     = notificationLogRepo.countAllSince(cutoff);
        double rate    = attempted == 0 ? 100.0 : (sent * 100.0 / attempted);
        // İKİ ayrı kusur vardı, ikisi de burada:
        //
        // 1) `/10L` TAMSAYI bölmesiydi — Math.round long döndürdüğü için "*10 … /10" ile hedeflenen
        //    tek ondalık anında atılıyordu (96,7 → 967/10L → 96).
        // 2) YUVARLAMA, ekranı alarmla çelişkiye düşürüyordu: alarm ham `rate < 95.0` üzerinden
        //    hesaplanıyor, ama 94,96 yukarı yuvarlanınca pano "95" (sağlıklı) gösteriyordu. Bunu
        //    ondalık eklemek TEK BAŞINA çözmez — 94,96 bir ondalıkla da 95,0'a yuvarlanır.
        //
        // Çözüm AŞAĞI yuvarlamak: gösterilen oran gerçek oranı ASLA olduğundan iyi göstermez, yani
        // eşiği yukarı doğru geçemez. Projede aynı gerekçenin emsali var — WeeklyAvailabilityReport
        // `pct >= 100 && up < total` iken 99.99 yazar: "tamamlanmamış olanı tam gösterme".
        double rateRounded = Math.floor(rate * 10) / 10.0;
        boolean alarm  = attempted > 0 && rate < 95.0;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total",     total);
        p.put("attempted", attempted);
        p.put("sent",      sent);
        p.put("rate",      rateRounded);
        p.put("alarm",     alarm);
        return p;
    }

    // ── Database ──────────────────────────────────────────────────────────────

    public long measureDbResponseMs() {
        try {
            long t0 = System.currentTimeMillis();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            log.warn("measureDbResponseMs failed: {}", e.getMessage());
            return -1;
        }
    }

    public List<Map<String, Object>> getTableStats() {
        String sql = """
            SELECT relname                                          AS table_name,
                   n_live_tup                                      AS row_count,
                   pg_size_pretty(pg_relation_size(relid))         AS table_size,
                   pg_size_pretty(pg_total_relation_size(relid))   AS total_size,
                   pg_relation_size(relid)                         AS table_size_bytes,
                   pg_total_relation_size(relid)                   AS total_size_bytes
            FROM pg_stat_user_tables
            ORDER BY pg_total_relation_size(relid) DESC
            """;
        return jdbcTemplate.queryForList(sql);
    }
}
