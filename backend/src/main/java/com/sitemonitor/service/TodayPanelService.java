package com.sitemonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TodayPanelSnapshot;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TodayPanelSnapshotRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Predicate;

/**
 * "Sizin için — bugün" paneli (2026-09-12, zenginleştirme #3): giriş sonrası ilk ekranda takımın
 * ilgilenmesi gerekenler. Kartlar: 30 gün altı sertifika · açık alarm · bu haftanın haftalık raporu ·
 * ve dört İZLEME kartı (2026-09-19, zayıf-algoritma istisnası kartının yerine, kullanıcı seçimi):
 * kararsız · yavaşlayan · sessiz/bayat · alan adı kaydı dolan ({@link TodayMonitorInsightsService},
 * takım-bağımsız 60 sn önbellek; görünürlük BURADA satır bazında süzülür).
 * Kapsam çağıranın predicate'i (takım görünürlüğü). Her kart try/catch'li — biri düşerse panel yine çizilir.
 *
 * <p>2026-09-23: "Susturulmuş ve bakımda" kartı ({@code quiet}: süren/yaklaşan bakım, duraklatılmış izleme,
 * süresi dolacak istisna) ve "dünden bugüne": her kart bloğunda {@code prev} (~24 saat önceki görünür sayı,
 * saatlik {@link TodayPanelSnapshot}'tan) + {@code recent} şeridi (son 24 saatte açılan/çözülen alarm,
 * yenilenen sertifika).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodayPanelService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int CERT_DAYS = 30;
    public static final int TOP = 5;   // kart başına satır (pop-up tavansız: build(..., limit))
    /** Duraklatma bu kadar gündür sürüyorsa "unutulmuş olabilir" sayılır (kart tonu uyarıya döner). */
    static final int PAUSED_LONG_DAYS = 7;
    /** "Dün bu saatte": şimdi − 24 sa'e kadarki en yeni görüntü; bundan daha eskiyse karşılaştırma yapılmaz. */
    static final int TREND_HOURS = 24, TREND_TOLERANCE_HOURS = 3;
    /** Görüntü tablosu sınırı (saat) — saatte bir satır → en fazla ~72 satır. */
    static final int TREND_KEEP_HOURS = 72;
    /** Bu kadar dakika içinde görüntü alınmışsa yenisi yazılmaz (yeniden başlatma sonrası çift satır olmasın). */
    static final int TREND_MIN_GAP_MIN = 50;
    /** "Dün bu saatte" karşılaştırması yapılan kartlar (haftalık rapor hariç: haftalık döngü, günlük fark anlamsız). */
    static final List<String> TREND_CARDS = List.of("certs", "alerts", "flapping", "slow", "stale", "domains", "notifications", "health", "quiet");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final WeeklyReportRepository weeklyReportRepo;
    private final TeamRepository teamRepo;
    private final TodayMonitorInsightsService monitorInsights;
    private final TodayPanelSnapshotRepository snapshotRepo;

    /** "Dün bu saatte" görüntüsü — 10 dk'lık kovada bellekte (her panel isteği DB'ye gitmesin). */
    private record Prior(String bucket, String takenAt, Map<String, List<Map<String, Object>>> cards) { }
    private volatile Prior priorCache;

    public Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds) {
        return build(canViewTeam, ownTeamIds, TOP);
    }

    public Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds, int limit) {
        return build(canViewTeam, ownTeamIds, limit, true);
    }

    /**
     * {@code limit}: kart başına satır tavanı — panel {@link #TOP}, "Tümünü gör" pop-up'ı {@code Integer.MAX_VALUE}
     * (2026-09-18: kullanıcı listenin tamamını sayfada değil pop-up'ta, sayfalı görmek istedi).
     * {@code showMaintenance}: kullanıcının {@code maintenance.view} izni — yoksa bakım satırları kartta çıkmaz
     * (bakım sayfasını göremeyen kullanıcıya pencere adlarını panelden sızdırmayalım).
     */
    public Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds, int limit, boolean showMaintenance) {
        return build(canViewTeam, ownTeamIds, limit, showMaintenance, Instant.now());
    }

    /** Test edilebilir çekirdek — {@code now} enjekte edilir (trend penceresi kayan). */
    Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds, int limit, boolean showMaintenance, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<CertificateInventory> visible = new ArrayList<>();
        try {
            for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc())
                if (canViewTeam.test(i.getTeamId())) visible.add(i);
        } catch (Exception e) { log.debug("today: envanter okunamadı: {}", e.toString()); }
        Set<String> visibleDomains = new HashSet<>();
        Map<String, Long> domainTeam = new HashMap<>();
        for (CertificateInventory i : visible) { visibleDomains.add(i.getDomain()); domainTeam.put(i.getDomain(), i.getTeamId()); }
        Map<Long, String> teamNames = new HashMap<>();
        try { teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName())); } catch (Exception ignored) { }

        out.put("certs", safe(() -> certs(visibleDomains, domainTeam, teamNames, limit)));
        out.put("alerts", safe(() -> alerts(canViewTeam, visibleDomains, teamNames, limit)));
        out.put("weekly", safe(() -> weekly(ownTeamIds, teamNames)));
        // İzleme kartları: tüm-takım anlık görüntü (önbellekli) → görünürlük süzgeci → tavan.
        TodayMonitorInsightsService.Snapshot snap;
        try { snap = monitorInsights.snapshot(); }
        catch (Exception e) { log.debug("today: izleme anlık görüntüsü alınamadı: {}", e.toString()); snap = null; }
        final TodayMonitorInsightsService.Snapshot sn = snap;
        out.put("flapping", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.flapping(), canViewTeam, visibleDomains, teamNames), limit, Map.of())));
        out.put("slow", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.slow(), canViewTeam, visibleDomains, teamNames), limit, Map.of())));
        // "paused" 2026-09-23'e kadar TÜM takımların sayısıydı (kapsam sızıntısı) — artık görünür satırlardan.
        List<Map<String, Object>> pausedRows = sn == null ? List.of() : visibleRows(sn.paused(), canViewTeam, visibleDomains, teamNames);
        out.put("stale", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.stale(), canViewTeam, visibleDomains, teamNames), limit,
                Map.of("paused", pausedRows.size()))));
        out.put("domains", safe(() -> {
            List<Map<String, Object>> rows = visibleRows(sn == null ? List.of() : sn.domains(), canViewTeam, visibleDomains, teamNames);
            long expired = rows.stream().filter(r -> ((Number) r.get("days")).intValue() < 0).count();
            return monitorBlock(rows, limit, Map.of("expired", expired));
        }));
        // 2026-09-19 (ikinci tur, kullanıcı seçimi): teslim edilemeyen bildirim (24 sa) · sertifika sağlık bulguları.
        out.put("notifications", safe(() -> {
            List<Map<String, Object>> rows = visibleRows(sn == null ? List.of() : sn.notifications(), canViewTeam, visibleDomains, teamNames);
            Map<String, Object> extra = new LinkedHashMap<>();
            for (String ch : List.of("EMAIL", "WEBHOOK", "PUSH")) extra.put(ch.toLowerCase(), rows.stream().filter(r -> ch.equals(r.get("channel"))).count());
            return monitorBlock(rows, limit, extra);
        }));
        out.put("health", safe(() -> {
            List<Map<String, Object>> rows = visibleRows(sn == null ? List.of() : sn.health(), canViewTeam, visibleDomains, teamNames);
            long critical = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("critical"))).count();
            return monitorBlock(rows, limit, Map.of("critical", critical));
        }));
        // 2026-09-23: "Susturulmuş ve bakımda" — bakım (izinliyse) · duraklatılmış izleme · dolacak istisna.
        out.put("quiet", safe(() -> quiet(sn, pausedRows, canViewTeam, visibleDomains, teamNames, limit, showMaintenance)));
        // 2026-09-23: "Son 24 saatte" şeridi — görünür satır sayıları.
        out.put("recent", safe(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            for (String k : List.of("opened", "resolved", "renewed"))
                r.put(k, sn == null ? 0 : visibleRows(sn.recent().getOrDefault(k, List.of()), canViewTeam, visibleDomains, teamNames).size());
            r.put("hours", TodayMonitorInsightsService.RECENT_WINDOW_HOURS);
            return r;
        }));
        // "Dün bu saatte" — her karta prev; görüntü yoksa (ilk gün / uzun kesinti) alan hiç yazılmaz.
        try {
            Prior prior = prior(now);
            if (prior != null) {
                for (String card : TREND_CARDS) {
                    Object blk = out.get(card);
                    // Görüntüde kart yoksa (o saat kart hata vermiş) prev YAZILMAZ — 0 yazmak sahte "+N kötüleşme" olurdu.
                    if (!(blk instanceof Map<?, ?> m) || m.containsKey("error") || !prior.cards().containsKey(card)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> bm = (Map<String, Object>) blk;
                    bm.put("prev", countVisible(prior.cards().get(card), canViewTeam, visibleDomains, showMaintenance));
                }
                out.put("trend_at", prior.takenAt());
            }
        } catch (Exception e) { log.debug("today: trend okunamadı: {}", e.toString()); }
        out.put("scope_domains", visibleDomains.size());
        return out;
    }

    // ── Susturulmuş ve bakımda (2026-09-23) ─────────────────────────────────────────────────
    private Map<String, Object> quiet(TodayMonitorInsightsService.Snapshot sn, List<Map<String, Object>> pausedRows,
                                      Predicate<Long> canViewTeam, Set<String> visibleDomains, Map<Long, String> teamNames,
                                      int limit, boolean showMaintenance) {
        List<Map<String, Object>> maint = new ArrayList<>();
        if (showMaintenance && sn != null) maint = visibleMaintenance(sn.maintenance(), canViewTeam, teamNames);
        List<Map<String, Object>> ex = sn == null ? List.of() : visibleRows(sn.exceptions(), canViewTeam, visibleDomains, teamNames);
        // Sıra: süren bakım → yaklaşan bakım → duraklatılmış (en eski üstte) → dolacak istisna. Kartın ilk 5'i
        // böylece "şu an susturulmuş olan"la başlar.
        List<Map<String, Object>> rows = new ArrayList<>(maint);
        rows.addAll(pausedRows);
        rows.addAll(ex);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("maint_active", maint.stream().filter(r -> "MAINT_ACTIVE".equals(r.get("kind"))).count());
        extra.put("maint_soon", maint.stream().filter(r -> "MAINT_SOON".equals(r.get("kind"))).count());
        extra.put("paused", pausedRows.size());
        extra.put("paused_long", pausedRows.stream().filter(r -> r.get("paused_days") instanceof Number n && n.longValue() >= PAUSED_LONG_DAYS).count());
        extra.put("exceptions", ex.size());
        return monitorBlock(rows, limit, extra);
    }

    /** Bakım sayfasıyla AYNI kural: takımı görünür OLAN ya da herkese açık (tüm-monitör / takımsız) pencere. */
    static List<Map<String, Object>> visibleMaintenance(List<Map<String, Object>> all, Predicate<Long> canViewTeam, Map<Long, String> teamNames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Long tid = r.get("team_id") == null ? null : ((Number) r.get("team_id")).longValue();
            if (!Boolean.TRUE.equals(r.get("public")) && !(tid != null && canViewTeam.test(tid))) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("team_name", tid == null ? null : teamNames.get(tid));
            out.add(m);
        }
        return out;
    }

    // ── Dünden bugüne (2026-09-23) ──────────────────────────────────────────────────────────
    /**
     * Saatlik kimlik görüntüsü: TÜM takımların kart satırlarının kimliği + görünürlük ipuçları. Sayı değil
     * kimlik saklanır — görünürlük kullanıcıya göre değişir, tek bir toplam takım kapsamını delerdi.
     * Son görüntü {@link #TREND_MIN_GAP_MIN} dakikadan yeniyse yazılmaz (yeniden başlatma sonrası çift satır
     * yok); her kayıtta {@link #TREND_KEEP_HOURS} saatten eskiler silinir (tablo sınırlı).
     */
    @Scheduled(fixedDelayString = "${site.monitor.today.trend-snapshot-ms:3600000}",
               initialDelayString = "${site.monitor.today.trend-snapshot-initial-ms:120000}")
    public void recordTrendSnapshot() {
        try { recordTrendSnapshot(Instant.now()); }
        catch (Exception e) { log.warn("today: trend görüntüsü yazılamadı: {}", e.toString()); }
    }

    void recordTrendSnapshot(Instant now) throws Exception {
        Optional<TodayPanelSnapshot> last = snapshotRepo.findFirstByOrderByTakenAtDesc();
        if (last.isPresent() && last.get().getTakenAt().compareTo(ISO.format(now.minus(Duration.ofMinutes(TREND_MIN_GAP_MIN)))) > 0) return;
        Map<String, Object> all = build(t -> true, List.of(), Integer.MAX_VALUE, true, now);
        Map<String, List<Map<String, Object>>> cards = new LinkedHashMap<>();
        for (String card : TREND_CARDS) {
            List<Map<String, Object>> ids = new ArrayList<>();
            if (all.get(card) instanceof Map<?, ?> blk && blk.get("items") instanceof List<?> items && !blk.containsKey("error")) {
                for (Object o : items) if (o instanceof Map<?, ?> row) ids.add(ident(card, row));
            } else continue;   // kart hata verdiyse o kart için görüntü YOK (yanlış "−N iyileşme" üretmesin)
            cards.put(card, ids);
        }
        TodayPanelSnapshot s = new TodayPanelSnapshot();
        s.setTakenAt(ISO.format(now));
        s.setPayload(MAPPER.writeValueAsString(cards));
        snapshotRepo.save(s);
        snapshotRepo.deleteOlderThan(ISO.format(now.minus(Duration.ofHours(TREND_KEEP_HOURS))));
    }

    /** Satır kimliği + görünürlük ipuçları: k (kimlik), t (takım), d (alan), a (herkese açık bakım), m (bakım satırı). */
    static Map<String, Object> ident(String card, Map<?, ?> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object kind = row.get("kind");
        String k;
        if (row.get("monitor_id") != null) k = row.get("type") + ":" + row.get("monitor_id");
        else if (row.get("window_id") != null) k = "MW:" + row.get("window_id");
        else if (row.get("channel") != null) k = row.get("channel") + ":" + row.get("alert_event_id") + ":" + row.get("target") + ":" + row.get("at");
        else if ("alerts".equals(card)) k = "A:" + row.get("id");
        else k = (kind == null ? "" : kind + ":") + row.get("domain");
        m.put("k", k);
        if (row.get("team_id") != null) m.put("t", ((Number) row.get("team_id")).longValue());
        if (row.get("domain") != null) m.put("d", row.get("domain"));
        if (row.get("window_id") != null) m.put("m", true);
        if (Boolean.TRUE.equals(row.get("public"))) m.put("a", true);
        return m;
    }

    /** Görüntüdeki kimliklerden bu kullanıcıya GÖRÜNÜR olanların sayısı — kartların kendi kuralıyla aynı. */
    static long countVisible(List<Map<String, Object>> ids, Predicate<Long> canViewTeam, Set<String> visibleDomains, boolean showMaintenance) {
        if (ids == null) return 0;
        long n = 0;
        for (Map<String, Object> r : ids) {
            Long t = r.get("t") == null ? null : ((Number) r.get("t")).longValue();
            if (Boolean.TRUE.equals(r.get("m"))) {
                if (showMaintenance && (Boolean.TRUE.equals(r.get("a")) || (t != null && canViewTeam.test(t)))) n++;
                continue;
            }
            Object d = r.get("d");
            if ((t != null && canViewTeam.test(t)) || (d != null && visibleDomains.contains(String.valueOf(d)))) n++;
        }
        return n;
    }

    /** şimdi − 24 sa'e kadarki en yeni görüntü; {@link #TREND_TOLERANCE_HOURS} saatten daha eskiyse null. 10 dk kovada önbellekli. */
    private Prior prior(Instant now) throws Exception {
        Instant target = now.minus(Duration.ofHours(TREND_HOURS));
        Instant bucketAt = target.truncatedTo(ChronoUnit.MINUTES);
        String bucket = ISO.format(bucketAt.minusSeconds(bucketAt.getEpochSecond() % 600));
        Prior cached = priorCache;
        if (cached != null && cached.bucket().equals(bucket)) return cached.takenAt() == null ? null : cached;
        Optional<TodayPanelSnapshot> row = snapshotRepo.findFirstByTakenAtLessThanEqualOrderByTakenAtDesc(ISO.format(target));
        Prior p;
        if (row.isEmpty() || row.get().getTakenAt().compareTo(ISO.format(target.minus(Duration.ofHours(TREND_TOLERANCE_HOURS)))) < 0) {
            p = new Prior(bucket, null, Map.of());
        } else {
            Map<String, List<Map<String, Object>>> cards = MAPPER.readValue(row.get().getPayload(), new TypeReference<>() { });
            p = new Prior(bucket, row.get().getTakenAt(), cards);
        }
        priorCache = p;
        return p.takenAt() == null ? null : p;
    }

    private interface Block { Map<String, Object> run(); }
    private static Map<String, Object> safe(Block b) {
        try { return b.run(); }
        catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("error", e.getClass().getSimpleName()); m.put("count", 0); m.put("items", List.of());
            return m;
        }
    }

    /** 30 gün altı (ve dolmuş) sertifikalar — en az gün üstte. */
    private Map<String, Object> certs(Set<String> domains, Map<String, Long> domainTeam, Map<Long, String> teamNames, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        int expired = 0;
        for (LatestCheck lc : latestCheckRepo.findAll()) {
            if (lc.getDomain() == null || !domains.contains(lc.getDomain()) || lc.getDaysRemaining() == null) continue;
            if (lc.getDaysRemaining() > CERT_DAYS) continue;
            if (lc.getDaysRemaining() < 0) expired++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", lc.getDomain()); m.put("days", lc.getDaysRemaining()); m.put("not_after", lc.getNotAfter());
            Long tid = domainTeam.get(lc.getDomain());
            m.put("team_id", tid); m.put("team_name", tid == null ? null : teamNames.get(tid));
            items.add(m);
        }
        items.sort(Comparator.comparingInt(m -> (Integer) m.get("days")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.put("expired", expired); out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /** Açık alarmlar — takımı görünür olan ya da alanı görünür envanterde olan. */
    private Map<String, Object> alerts(Predicate<Long> canViewTeam, Set<String> domains, Map<Long, String> teamNames, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        int critical = 0;
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            boolean vis = (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
            if (!vis) continue;
            if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) critical++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId()); m.put("domain", e.getDomain()); m.put("type", e.getAlertType()); m.put("level", e.getAlertLevel());
            m.put("since", e.getCreatedAt()); m.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
            m.put("team_id", e.getTeamId()); m.put("team_name", e.getTeamId() == null ? null : teamNames.get(e.getTeamId()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.put("critical", critical); out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /**
     * İzleme kartı süzgeci — alarm kartıyla AYNI görünürlük kuralı: takımı görünür OLAN ya da (takımsız,
     * envanter-türevi DNS/Port izlemelerinde) hedefi görünür envanterde olan satır. Önbellekteki satır
     * DEĞİŞTİRİLMEZ (kopyalanır; team_name burada eklenir).
     */
    static List<Map<String, Object>> visibleRows(List<Map<String, Object>> all, Predicate<Long> canViewTeam,
                                                 Set<String> domains, Map<Long, String> teamNames) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Long tid = r.get("team_id") == null ? null : ((Number) r.get("team_id")).longValue();
            String domain = (String) r.get("domain");
            boolean vis = (tid != null && canViewTeam.test(tid)) || (domain != null && domains.contains(domain));
            if (!vis) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("team_name", tid == null ? null : teamNames.get(tid));
            items.add(m);
        }
        return items;
    }

    /** count + ek sayaçlar + tavanlı items (sıralama anlık görüntüden gelir). */
    static Map<String, Object> monitorBlock(List<Map<String, Object>> items, int limit, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.putAll(extra);
        out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /** Bu ISO haftasının raporu — kullanıcının KENDİ takımları için (görüntüleme kapsamı değil; rapor girme sorumluluğu). */
    private Map<String, Object> weekly(List<Long> ownTeamIds, Map<Long, String> teamNames) {
        LocalDate today = LocalDate.now(IST);
        int year = today.get(WeekFields.ISO.weekBasedYear()), week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        List<Map<String, Object>> items = new ArrayList<>();
        int missing = 0;
        for (Long tid : ownTeamIds == null ? List.<Long>of() : ownTeamIds) {
            if (tid == null) continue;
            // Modül o takımda kapalıysa kart da yok (2026-09-16): takım Haftalık Raporlar sayfasını
            // görmüyorsa "bu hafta raporun eksik" demenin karşılığı da yok.
            if (!teamRepo.findById(tid).map(t -> Boolean.TRUE.equals(t.getWeeklyReportsEnabled())).orElse(false)) continue;
            Optional<WeeklyReport> r = weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(tid, year, week);
            String status = r.map(WeeklyReport::getStatus).orElse("MISSING");
            if ("MISSING".equals(status) || "DRAFT".equals(status) || "REJECTED".equals(status)) missing++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", tid); m.put("team_name", teamNames.get(tid)); m.put("status", status); m.put("report_id", r.map(WeeklyReport::getId).orElse(null));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year); out.put("week", week); out.put("count", items.size()); out.put("missing", missing); out.put("items", items);
        return out;
    }
}
