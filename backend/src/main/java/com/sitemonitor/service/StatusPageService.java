package com.sitemonitor.service;

import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * KURUM İÇİ DURUM SAYFASI (2026-10-01, onaylı öneri 18) — operasyon ekipleri DIŞINDAKİLER (diğer takımlar, yönetim) için
 * "hizmetler ayakta mı?" sorusunun tek sayfalık cevabı. {@code GET /api/status-page}; giriş gerektirir (AuthInterceptor
 * {@code /api/**}), dışarıya AÇILMAZ (PUBLIC listesinde yok). Yalnız OKUR — hiçbir mevcut ekranın/ucun davranışı değişmez.
 *
 * <h2>Veri (yeni izleme sorgusu YOK)</h2>
 * İzleme başına durum İzleme Panosu'nun hesabından gelir: {@link MonitoringOverviewService#build(String, Predicate, boolean,
 * int, boolean)} kurum geneli ({@code "ALL"} kapsamı, 24 sa) — aynı bellek kaydını global görüntüleyicinin panosuyla
 * PAYLAŞIR. Üstüne: takım adları (1 sorgu), etkin bakım pencereleri (1 sorgu), olay kaydı özetleri (2 projeksiyon + 2
 * gruplu sayım sorgusu, tavanlı), 7 günlük kullanılabilirlik ({@code monitor_check_daily} rollup'ından TEK gruplu sorgu,
 * 15 dk bellek).
 *
 * <h2>Hizmet</h2>
 * Hizmet = (takım, izleme grubu adı) — dokuz izleme türü boyunca; grup adı büyük/küçük harf ve kenar boşluğu duyarsız
 * birleşir. Grubu olmayan izlemeler takım başına tek bir "gruplanmamış" hizmette toplanır ({@code ungrouped=true},
 * ad arayüzde çevrilir). Silinmiş ve envanterden çıkmış (taramanın atladığı) izlemeler hiç sayılmaz; AKTİF izlemesi
 * olmayan (tamamı duraklatılmış) hizmet listelenmez — izlenmeyen bir şeyin durumu bilinmez.
 *
 * <h2>İzleme sağlığı ({@link #monitorHealth})</h2>
 * <ol>
 *   <li>duraklatılmış → {@code paused} (duruma katılmaz, sayılır);</li>
 *   <li>hedefi şu an etkin bir bakım penceresinde (ya da "tüm izlemeler" penceresi açık) → {@code maintenance} — taramanın
 *       kullandığı alarm anahtarıyla BİREBİR eşleşme ({@link MaintenanceService#isUnderMaintenance} kuralı);</li>
 *   <li>hiç kontrol edilmemiş → {@code unknown} (veri yok; duruma katılmaz);</li>
 *   <li>kontrolü gecikmiş (panonun "stale" kuralı) → {@code degraded};</li>
 *   <li>panoda "down": son kontrol başarısız YA DA açık alarm HIGH/CRITICAL → {@code down}; yalnız UYARI seviyeli açık alarm
 *       (yavaşlık, yaklaşan bitiş) ve son kontrol başarılı → {@code degraded}. Alan adı kaydı izlemesi bir erişim kesintisi
 *       değildir: sorunu en fazla {@code degraded};</li>
 *   <li>aksi → {@code up}.</li>
 * </ol>
 *
 * <h2>Hizmet durumu ({@link #serviceState})</h2>
 * A = aktif izleme, B = bakımdaki, U = veri yok, D = down, G = degraded; değerlendirilen E = A − B − U.
 * <ul>
 *   <li>B = A → {@code maintenance}; E = 0 → B &gt; 0 ise {@code maintenance}, değilse {@code no_data};</li>
 *   <li>D &gt; 0 → D, E'nin YARISINDAN FAZLAYSA {@code major_outage}, değilse {@code partial_outage};</li>
 *   <li>G &gt; 0 → {@code degraded}; aksi → {@code operational}.</li>
 * </ul>
 * Kurum (üst şerit) durumu = hizmetlerin en kötüsü; sıra {@link #STATE_ORDER} (no_data &lt; operational &lt; maintenance
 * &lt; degraded &lt; partial_outage &lt; major_outage). Olay kayıtları şeridi DEĞİŞTİRMEZ (elle açılan kayıtlar uzun süre
 * açık kalabiliyor) — sayıları şeritte ayrıca verilir.
 *
 * <h2>Görünürlük — "mevcudu bozma" (2026-10-01 düzeltmesi)</h2>
 * Sayfa oturum açmış herkese açıktır (yeni izin anahtarı YOK) ama BUGÜN göremediği hiçbir AYRINTIYI göstermez:
 * <ul>
 *   <li>Hizmet düzeyi (ad, sayılar, durum, sorun başlangıcı, 7 gün) — herkese (onaylı kapsam). URL/host/hedef, hata metni,
 *       alıcı ASLA.</li>
 *   <li>Hizmetin izleme listesi (ad + tür + sağlık) — yalnız takımı görüş kapsamında olana ({@link Viewer#canSeeTeam}).</li>
 *   <li>Olay kaydı satırı (başlık, önem, durum, zaman, servis, takım) — yalnız Olaylar ekranının OKUMA kuralına uyan kayıt
 *       ({@link Viewer#canReadIncident} = {@code incidents.view} + {@code IncidentController.canReadIncident}); kapsam dışı
 *       kayıt yalnız SAYIYA katılır ({@code active_hidden} / {@code resolved_hidden}).</li>
 *   <li>Bakım penceresi satırı (ad, zaman, kapsam) — yalnız Bakım Pencereleri listesinin kuralına uyan pencere
 *       ({@link Viewer#canSeeWindow} = {@code maintenance.view} + {@code MaintenanceController.canSeeWindow}); diğerleri yalnız
 *       sayı ({@code active_hidden} / {@code upcoming_hidden}). Hizmetin "bakımda" DURUMU toplu bilgidir ve kalır; bakım
 *       bitiş saati ({@code maintenance_until}) ise yalnız görülebilen pencerelerden hesaplanır.</li>
 * </ul>
 *
 * <h2>Performans</h2>
 * Kurum geneli taban hesap ({@code ORG}) {@link #cacheMs} (varsayılan 30 sn) paylaşılır; görüntüleyici izdüşümü (izleme
 * listesi, olay ve pencere satırlarının süzülmesi — DB YOK) bellek anahtarıyla ayrıca belleklenir (anahtar görüş kapsamı +
 * iki izin bayrağı, yüklemleri TAM belirler). {@code fresh=true} belleği en fazla {@link #FRESH_MIN_MS}'de bir atlar.
 * Dönen haritalar PAYLAŞILIR — çağıran değiştirmez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusPageService {

    // ── Hizmet / kurum durumları ─────────────────────────────────────────────────────────────────────────────────────
    public static final String NO_DATA = "no_data";
    public static final String OPERATIONAL = "operational";
    public static final String MAINTENANCE = "maintenance";
    public static final String DEGRADED = "degraded";
    public static final String PARTIAL_OUTAGE = "partial_outage";
    public static final String MAJOR_OUTAGE = "major_outage";
    /** Önem sırası (artan) — kurum durumu en kötü hizmetinkidir. */
    public static final List<String> STATE_ORDER =
            List.of(NO_DATA, OPERATIONAL, MAINTENANCE, DEGRADED, PARTIAL_OUTAGE, MAJOR_OUTAGE);

    // ── İzleme sağlığı (hizmet durumunun girdisi) ───────────────────────────────────────────────────────────────────
    static final String M_UP = "up";
    static final String M_DEGRADED = "degraded";
    static final String M_DOWN = "down";
    static final String M_MAINTENANCE = "maintenance";
    static final String M_UNKNOWN = "unknown";
    static final String M_PAUSED = "paused";
    static final Set<String> SEVERE_LEVELS = Set.of("CRITICAL", "HIGH");

    /** İzleme durumunun alındığı pano penceresi (saat) — global panonun varsayılanı; bellek kaydı onunla paylaşılır. */
    static final int OVERVIEW_WINDOW_HOURS = 24;
    /** Kurum geneli kapsam anahtarı (TtlMemo.scopeKey(true, …)) — global görüntüleyicinin panosuyla aynı. */
    static final String ORG_SCOPE = TtlMemo.scopeKey(true, null);
    /** Olay geçmişi / yaklaşan bakım / kullanılabilirlik ufku (gün). */
    static final int DAYS = 7;
    /** Görüntüleyiciye dönen satır tavanları (sayılar ayrıca ve TAM döner). */
    static final int INCIDENT_LIMIT = 50;
    static final int MAINTENANCE_LIMIT = 50;
    /** Tabanda tutulan olay satırı tavanı — kapsamlı görüntüleyicinin kendi olayları kurum geneli ilk 50'nin dışında
     *  kalabilir; süzme bu daha geniş dilimden yapılır. */
    static final int INCIDENT_BASE_LIMIT = 200;
    static final int WINDOW_SERVICE_NAMES = 8;
    static final int INCIDENT_SERVICE_NAMES = 10;
    /** Taze istek belleği ancak kayıt bu yaştan eskiyse atlar (ms) — Yenile düğmesi sel koruması. */
    static final long FRESH_MIN_MS = 5_000;
    /** Rollup gece yazılır; gün içinde değişmez — 15 dk yeterince taze. */
    static final long UPTIME_CACHE_MS = 15 * 60_000;
    static final int MEMO_MAX_KEYS = 500;

    /** Rollup tablosundaki tür adı → pano tür anahtarı (DNS ve alan adı rollup'ta yok → kullanılabilirlik hesaplanmaz). */
    static final Map<String, String> ROLLUP_TYPES = Map.of(
            "HTTP", "http", "PING", "ping", "PORT", "port", "KEYWORD", "keyword",
            "PAGE", "page", "PAGESPEED", "pagespeed", "SCRIPTED", "scripted");
    static final String UPTIME_SQL =
            "SELECT monitor_type, monitor_key, SUM(total_checks) AS total, SUM(up_checks) AS up FROM monitor_check_daily "
          + "WHERE day >= ? AND day < ? AND monitor_type IN ('HTTP','PING','PORT','KEYWORD','PAGE','PAGESPEED','SCRIPTED') "
          + "GROUP BY monitor_type, monitor_key";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String BASE_KEY = "ORG";

    // Tabanın İÇ alanları — izdüşüm her zaman çıkarır, yanıta asla gitmez.
    static final String K_MONITORS = "monitors";
    static final String K_SVC_WINDOWS = "_maintenance_windows";     // hizmet: pencere id → bitiş (ISO)
    static final String K_WINDOWS = "_windows";                     // bakım: tüm (etkin + yaklaşan) pencere satırları
    static final String K_ACTIVE_ROWS = "_active_rows";             // olay: satırlar (kayıt + giren takım dahil)
    static final String K_ACTIVE_COUNTS = "_active_counts";         // olay: [teamId, createdByTeamId, adet]
    static final String K_RESOLVED_ROWS = "_resolved_rows";
    static final String K_RESOLVED_COUNTS = "_resolved_counts";
    static final String K_CREATED_BY = "_created_by_team_id";

    private final MonitoringOverviewService overviewService;
    private final MaintenanceService maintenanceService;
    private final MaintenanceWindowRepository maintenanceRepo;
    private final IncidentRecordRepository incidentRepo;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbc;

    /** Sunucu tarafı bellek penceresi (ms); 0 → kapalı (birim testlerinde {@code new} ile kurulunca varsayılan). */
    @Value("${site.monitor.status-page.cache-ms:30000}")
    long cacheMs;

    /** Final değil: birim testi saat enjekte edilmiş belleklerle değiştirir. */
    private TtlMemo<Map<String, Object>> baseMemo = new TtlMemo<>(4);
    private TtlMemo<Map<String, Object>> viewMemo = new TtlMemo<>(MEMO_MAX_KEYS);
    private TtlMemo<UptimeData> uptimeMemo = new TtlMemo<>(4);

    /** Rollup okuması: {@code available=false} → tablo okunamadı (ör. H2 / henüz rollup yok), sayfa "veri yok" der. */
    record UptimeData(boolean available, Map<String, long[]> byMonitor) {}

    /**
     * Görüntüleyicinin BUGÜNKÜ görünürlüğü — denetleyici oturumdan kurar (mevcut ekranların kurallarıyla AYNI).
     *
     * @param canSeeTeam      izleme listesi: takım görüş kapsamında mı ({@code SessionScope.canView})
     * @param canReadIncident olay satırı: (kayıt takımı, giren takım) → Olaylar ekranının okuma kuralı + {@code incidents.view}
     * @param canSeeWindow    bakım satırı: (pencere takımı, tüm izlemeler mi) → Bakım listesinin kuralı + {@code maintenance.view}
     */
    public record Viewer(Predicate<Long> canSeeTeam, BiPredicate<Long, Long> canReadIncident,
                         BiPredicate<Long, Boolean> canSeeWindow) {
        /** Her şeyi gören görüntüleyici (global yönetici / AUDIT + iki izin) — testler ve iç çağıranlar için. */
        public static Viewer all() {
            return new Viewer(t -> true, (t, c) -> true, (t, a) -> true);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Giriş
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Denetleyici girişi — görüntüleyici başına bellekli izdüşüm. {@code memoKey} {@link Viewer}'in üç yüklemini TAM
     * belirlemelidir (görüş kapsamı + izin bayrakları); null anahtar belleği atlar. Dönen harita PAYLAŞILIR.
     */
    public Map<String, Object> view(String memoKey, Viewer viewer, boolean fresh) {
        long ttl = ttl(fresh);
        Map<String, Object> projected = viewMemo.get(memoKey, ttl, false, () -> project(base(fresh), viewer));
        if (systemMaintenance == null) return projected;
        // Sistem Bakım Modu notu (2026-10-02, kullanıcı kararı): "Planlı bakım: 22:00–23:00" — EK alan; bellekli (paylaşılan)
        // izdüşüme DOKUNULMAZ, kopyaya eklenir. Kaynak bakım servisinin pod önbelleği (≤ 5 sn) → 30 sn'lik bellekten taze.
        // İçerik giriş sayfasının public bloğuyla aynı: durum, saatler, TR/EN mesaj, iletişim — kimlik/sayaç yok.
        Map<String, Object> out = new LinkedHashMap<>(projected);
        try {
            out.put("system_maintenance", systemMaintenance.publicStatus());
        } catch (Exception e) {
            log.debug("Durum sayfası bakım notu eklenemedi: {}", e.getMessage());
        }
        return out;
    }

    /** Sistem Bakım Modu (2026-10-02) — isteğe bağlı; yokken yanıt bugünküyle birebir. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemMaintenanceService systemMaintenance;

    /** Test kancası. */
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    private long ttl(boolean fresh) {
        return fresh && cacheMs > 0 ? Math.min(cacheMs, FRESH_MIN_MS) : cacheMs;
    }

    /** Kurum geneli taban (kapsamsız) — tek bellek kaydı. İç alanlar taşır; doğrudan yanıt OLARAK DÖNÜLMEZ. */
    Map<String, Object> base(boolean fresh) {
        return baseMemo.get(BASE_KEY, ttl(fresh), false, () -> computeBase(fresh, Instant.now()));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Görüntüleyici izdüşümü (DB yok; tabana DOKUNMAZ — yeni haritalar kurar)
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    static Map<String, Object> project(Map<String, Object> base, Viewer viewer) {
        Viewer v = viewer != null ? viewer : new Viewer(t -> false, (t, c) -> false, (t, a) -> false);
        Map<String, Object> maintBase = base.get("maintenance") instanceof Map<?, ?> mb ? (Map<String, Object>) mb : Map.of();
        List<Map<String, Object>> windows = listOf(maintBase.get(K_WINDOWS));
        Map<Long, Boolean> windowVisible = new HashMap<>();
        for (Map<String, Object> w : windows) {
            Long id = asLong(w.get("id"));
            if (id != null) windowVisible.put(id, v.canSeeWindow().test(asLong(w.get("team_id")), Boolean.TRUE.equals(w.get("all_monitors"))));
        }

        // ── Hizmetler ──
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map<String, Object> s : listOf(base.get("services"))) {
            Map<String, Object> c = new LinkedHashMap<>(s);
            Object monitors = c.remove(K_MONITORS);
            Object byWindow = c.remove(K_SVC_WINDOWS);
            boolean visible = v.canSeeTeam().test(asLong(s.get("team_id")));
            c.put("monitors_visible", visible);
            if (visible) c.put(K_MONITORS, monitors == null ? List.of() : monitors);
            // Bakım bitişi yalnız GÖRÜLEBİLEN pencerelerden (durum "bakımda" toplu bilgi olarak kalır)
            String until = null;
            if (byWindow instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Long id = asLong(e.getKey());
                    String end = e.getValue() == null ? null : String.valueOf(e.getValue());
                    if (id == null || end == null || !Boolean.TRUE.equals(windowVisible.get(id))) continue;
                    if (until == null || end.compareTo(until) > 0) until = end;
                }
            }
            c.put("maintenance_until", until);
            services.add(c);
        }

        // ── Bakım pencereleri ──
        List<Map<String, Object>> active = new ArrayList<>(), upcoming = new ArrayList<>();
        long activeTotal = 0, upcomingTotal = 0, activeHidden = 0, upcomingHidden = 0;
        for (Map<String, Object> w : windows) {
            boolean isActive = "active".equals(w.get("state"));
            boolean vis = Boolean.TRUE.equals(windowVisible.get(asLong(w.get("id"))));
            if (isActive) { activeTotal++; if (!vis) activeHidden++; else if (active.size() < MAINTENANCE_LIMIT) active.add(w); }
            else { upcomingTotal++; if (!vis) upcomingHidden++; else if (upcoming.size() < MAINTENANCE_LIMIT) upcoming.add(w); }
        }
        Map<String, Object> maintenance = new LinkedHashMap<>();
        maintenance.put("active", active);
        maintenance.put("active_total", activeTotal);
        maintenance.put("active_hidden", activeHidden);
        maintenance.put("upcoming", upcoming);
        maintenance.put("upcoming_total", upcomingTotal);
        maintenance.put("upcoming_hidden", upcomingHidden);
        maintenance.put("days", DAYS);

        // ── Olay kayıtları ──
        Map<String, Object> incBase = base.get("incidents") instanceof Map<?, ?> ib ? (Map<String, Object>) ib : Map.of();
        Map<String, Object> incidents = new LinkedHashMap<>();
        putIncidentBlock(incidents, "active", listOf(incBase.get(K_ACTIVE_ROWS)), incBase.get(K_ACTIVE_COUNTS), v);
        putIncidentBlock(incidents, "resolved", listOf(incBase.get(K_RESOLVED_ROWS)), incBase.get(K_RESOLVED_COUNTS), v);
        incidents.put("days", DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", base.get("generated_at"));
        out.put("overall", base.get("overall"));
        out.put("services", services);
        out.put("incidents", incidents);
        out.put("maintenance", maintenance);
        out.put("uptime", base.get("uptime"));
        return out;
    }

    /**
     * Olay bloğu: görülebilen satırlar (en fazla {@value #INCIDENT_LIMIT}, iç alan çıkarılmış), TAM toplam, görülebilen
     * toplam ve gizli (kapsam dışı) sayı — sayılar gruplu sayım satırlarından, satır tavanından bağımsız.
     */
    private static void putIncidentBlock(Map<String, Object> out, String name, List<Map<String, Object>> rows,
                                         Object countsRaw, Viewer v) {
        List<Map<String, Object>> visibleRows = new ArrayList<>();
        int visibleInRows = 0;
        for (Map<String, Object> r : rows) {
            if (!v.canReadIncident().test(asLong(r.get("team_id")), asLong(r.get(K_CREATED_BY)))) continue;
            visibleInRows++;
            if (visibleRows.size() >= INCIDENT_LIMIT) continue;
            Map<String, Object> c = new LinkedHashMap<>(r);
            c.remove(K_CREATED_BY);
            visibleRows.add(c);
        }
        long total = 0, visible = 0;
        boolean haveCounts = false;
        if (countsRaw instanceof List<?> counts) {
            for (Object o : counts) {
                if (!(o instanceof Object[] g) || g.length < 3) continue;
                long n = g[2] instanceof Number num ? num.longValue() : 0L;
                haveCounts = true;
                total += n;
                if (v.canReadIncident().test(asLong(g[0]), asLong(g[1]))) visible += n;
            }
        }
        if (!haveCounts) { total = rows.size(); visible = visibleInRows; }
        visible = Math.max(visible, visibleInRows);
        total = Math.max(total, visible);
        out.put(name, visibleRows);
        out.put(name + "_total", total);
        out.put(name + "_visible", visible);
        out.put(name + "_hidden", total - visible);
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Taban hesap
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    Map<String, Object> computeBase(boolean fresh, Instant now) {
        Map<String, Object> overview = overviewService.build(ORG_SCOPE, team -> true, true, OVERVIEW_WINDOW_HOURS, fresh);
        List<Map<String, Object>> rows = overview != null && overview.get("monitors") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        Map<Long, String> teamNames = new HashMap<>();
        try { for (Team t : teamRepo.findAll()) if (t.getId() != null) teamNames.put(t.getId(), t.getName()); }
        catch (Exception e) { log.debug("durum sayfası: takım adları okunamadı: {}", e.toString()); }

        MaintenanceIndex maint = maintenanceIndex(now);
        UptimeData uptime = uptime(now);

        // ── Hizmetler ──
        Map<String, ServiceAcc> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> serviceKeysByTarget = new HashMap<>();
        for (Map<String, Object> r : rows) {
            if (r == null) continue;
            String target = r.get("target") instanceof String s ? s : null;
            boolean inMaint = maint.covers(target);
            String health = monitorHealth(r, inMaint);
            if (health == null) continue;   // silinmiş / envanterden çıkmış
            Long teamId = asLong(r.get("team_id"));
            String group = r.get("group_name") instanceof String g && !g.isBlank() ? g.trim() : null;
            String key = serviceKey(teamId, group);
            ServiceAcc acc = byKey.computeIfAbsent(key, k -> new ServiceAcc(k, teamId, group));
            if (acc.teamName == null) acc.teamName = r.get("team_name") instanceof String tn ? tn : teamNames.get(teamId);
            acc.add(r, health, inMaint ? maint.endsFor(target) : Map.of(), uptime);
            if (target != null) serviceKeysByTarget.computeIfAbsent(target, x -> new LinkedHashSet<>()).add(key);
        }

        Collator collator = Collator.getInstance(Locale.forLanguageTag("tr"));
        collator.setStrength(Collator.SECONDARY);
        List<Map<String, Object>> services = new ArrayList<>();
        Map<String, Map<String, Object>> serviceByKey = new HashMap<>();
        List<ServiceAcc> accs = new ArrayList<>(byKey.values());
        accs.sort(Comparator.comparing((ServiceAcc a) -> a.teamName == null ? 1 : 0)
                .thenComparing(a -> a.teamName == null ? "" : a.teamName, collator)
                .thenComparing(a -> a.ungrouped() ? 1 : 0)
                .thenComparing(a -> a.name == null ? "" : a.name, collator)
                .thenComparing(a -> a.key));
        Map<String, Long> byState = new LinkedHashMap<>();
        for (String s : STATE_ORDER) byState.put(s, 0L);
        long mActive = 0, mDown = 0, mDegraded = 0, mMaint = 0;
        for (ServiceAcc a : accs) {
            Map<String, Object> s = a.toMap();
            if (s == null) continue;   // aktif izlemesi yok → listelenmez
            services.add(s);
            serviceByKey.put(a.key, s);
            byState.merge(String.valueOf(s.get("state")), 1L, Long::sum);
            mActive += a.active; mDown += a.down; mDegraded += a.degraded; mMaint += a.maintenance;
        }
        List<String> states = new ArrayList<>();
        for (Map<String, Object> s : services) states.add(String.valueOf(s.get("state")));

        // ── Bakım pencereleri (hizmet adları ancak hizmetler kurulduktan sonra bağlanır) ──
        List<Map<String, Object>> activeWindows = new ArrayList<>(), upcomingWindows = new ArrayList<>();
        for (WindowRef w : maint.windows) {
            Map<String, Object> m = windowMap(w, teamNames, serviceKeysByTarget, serviceByKey);
            (w.active() ? activeWindows : upcomingWindows).add(m);
        }
        activeWindows.sort(Comparator.comparing(m -> String.valueOf(m.get("ends_at"))));
        upcomingWindows.sort(Comparator.comparing(m -> String.valueOf(m.get("starts_at"))));
        List<Map<String, Object>> allWindows = new ArrayList<>(activeWindows);
        allWindows.addAll(upcomingWindows);

        // ── Olay kayıtları ──
        Map<String, Object> incidents = incidents(now);

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("state", overallState(states));
        overall.put("services_total", (long) services.size());
        overall.put("by_state", byState);
        overall.put("monitors_active", mActive);
        overall.put("monitors_down", mDown);
        overall.put("monitors_degraded", mDegraded);
        overall.put("monitors_maintenance", mMaint);
        overall.put("active_incidents", incidents.get("active_total"));          // kurum geneli SAYI (satır değil)
        overall.put("active_maintenance", (long) activeWindows.size());
        overall.put("upcoming_maintenance", (long) upcomingWindows.size());

        Map<String, Object> maintenance = new LinkedHashMap<>();
        maintenance.put(K_WINDOWS, allWindows);
        maintenance.put("days", DAYS);

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("available", uptime.available());
        up.put("days", DAYS);
        up.put("from", today.minusDays(DAYS).toString());
        up.put("to", today.minusDays(1).toString());
        up.put("source", "daily_rollup");

        Map<String, Object> out = new LinkedHashMap<>();
        Object gen = overview != null ? overview.get("generated_at") : null;
        out.put("generated_at", gen != null ? gen : ISO.format(now));
        out.put("overall", overall);
        out.put("services", services);
        out.put("incidents", incidents);
        out.put("maintenance", maintenance);
        out.put("uptime", up);
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Kurallar (saf — birim testleri doğrudan sınar)
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Pano satırından izleme sağlığı (sınıf belgesindeki kural 1–6); hiç sayılmayacak satır (silinmiş / envanterden
     *  çıkmış) için null. */
    static String monitorHealth(Map<String, Object> row, boolean inMaintenance) {
        if (row == null || Boolean.TRUE.equals(row.get("deleted")) || Boolean.TRUE.equals(row.get("inventory_inactive"))) return null;
        if (!Boolean.TRUE.equals(row.get("active"))) return M_PAUSED;
        if (inMaintenance) return M_MAINTENANCE;
        String st = String.valueOf(row.get("status"));
        return switch (st) {
            case "unknown" -> M_UNKNOWN;
            case "stale" -> M_DEGRADED;
            case "down" -> {
                boolean failed = Boolean.FALSE.equals(row.get("last_ok"));
                Object lvl = row.get("open_alert_level");
                boolean severe = lvl != null && SEVERE_LEVELS.contains(String.valueOf(lvl).toUpperCase(Locale.ROOT));
                if (!failed && !severe) yield M_DEGRADED;            // yalnız uyarı seviyeli açık alarm
                yield "domain".equals(row.get("type")) ? M_DEGRADED : M_DOWN;   // kayıt sorunu erişim kesintisi değildir
            }
            case "paused" -> M_PAUSED;
            case "deleted" -> null;
            default -> M_UP;
        };
    }

    /** Hizmet durumu (sınıf belgesindeki kural); aktif izleme yoksa null (hizmet listelenmez). */
    static String serviceState(int active, int maintenance, int unknown, int down, int degraded) {
        if (active <= 0) return null;
        if (maintenance >= active) return MAINTENANCE;
        int evaluated = active - maintenance - unknown;
        if (evaluated <= 0) return maintenance > 0 ? MAINTENANCE : NO_DATA;
        if (down > 0) return down * 2 > evaluated ? MAJOR_OUTAGE : PARTIAL_OUTAGE;
        if (degraded > 0) return DEGRADED;
        return OPERATIONAL;
    }

    /** Kurum durumu = en kötü hizmet durumu; hizmet yoksa {@code no_data}. */
    static String overallState(Collection<String> states) {
        String worst = NO_DATA;
        if (states != null) for (String s : states) if (rank(s) > rank(worst)) worst = s;
        return worst;
    }

    static int rank(String state) {
        int i = STATE_ORDER.indexOf(state);
        return Math.max(i, 0);
    }

    static String serviceKey(Long teamId, String group) {
        return (teamId == null ? "-" : String.valueOf(teamId)) + "|" + (group == null ? "" : group.trim().toLowerCase(Locale.ROOT));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Hizmet biriktirici
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static final class ServiceAcc {
        final String key;
        final Long teamId;
        final String name;
        String teamName;
        int total, active, up, degraded, down, maintenance, unknown, paused;
        String since;            // en eski açık sorun (down/degraded izlemelerin open_since'i)
        final Map<Long, String> maintenanceEnds = new LinkedHashMap<>();   // bakımdaki izlemeleri kapsayan pencere → bitiş
        long upTotal, upOk;      // 7 günlük rollup toplamı
        boolean uptimeSeen;
        final Set<String> types = new LinkedHashSet<>();
        final List<Map<String, Object>> monitors = new ArrayList<>();

        ServiceAcc(String key, Long teamId, String name) { this.key = key; this.teamId = teamId; this.name = name; }

        boolean ungrouped() { return name == null; }

        void add(Map<String, Object> r, String health, Map<Long, Instant> windowEnds, UptimeData uptime) {
            total++;
            String type = r.get("type") instanceof String t ? t : null;
            if (type != null) types.add(type);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name"));
            m.put("type", type);
            m.put("status", health);
            monitors.add(m);
            if (M_PAUSED.equals(health)) { paused++; return; }
            active++;
            switch (health) {
                case M_DOWN -> down++;
                case M_DEGRADED -> degraded++;
                case M_MAINTENANCE -> maintenance++;
                case M_UNKNOWN -> unknown++;
                default -> up++;
            }
            if ((M_DOWN.equals(health) || M_DEGRADED.equals(health)) && r.get("open_since") instanceof String os
                    && (since == null || os.compareTo(since) < 0)) since = os;
            if (M_MAINTENANCE.equals(health)) {
                for (Map.Entry<Long, Instant> e : windowEnds.entrySet()) {
                    String end = ISO.format(e.getValue());
                    maintenanceEnds.merge(e.getKey(), end, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                }
            }
            if (uptime != null && uptime.available() && type != null && r.get("id") != null) {
                long[] v = uptime.byMonitor().get(type + "|" + r.get("id"));
                if (v != null && v[0] > 0) { upTotal += v[0]; upOk += Math.min(v[1], v[0]); uptimeSeen = true; }
            }
        }

        Map<String, Object> toMap() {
            String state = serviceState(active, maintenance, unknown, down, degraded);
            if (state == null) return null;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", key);
            s.put("name", name);
            s.put("ungrouped", name == null);
            s.put("team_id", teamId);
            s.put("team_name", teamName);
            s.put("state", state);
            s.put("monitors_total", total);
            s.put("monitors_active", active);
            s.put("up", up);
            s.put("degraded", degraded);
            s.put("down", down);
            s.put("maintenance", maintenance);
            s.put("unknown", unknown);
            s.put("paused", paused);
            List<String> ordered = new ArrayList<>();
            for (String t : MonitorTypeCatalog.ORDER) if (types.contains(t)) ordered.add(t);
            for (String t : types) if (!ordered.contains(t)) ordered.add(t);
            s.put("types", ordered);
            boolean problem = DEGRADED.equals(state) || PARTIAL_OUTAGE.equals(state) || MAJOR_OUTAGE.equals(state);
            s.put("since", problem ? since : null);
            s.put("uptime_7d", uptimeSeen ? Math.round(upOk * 10000.0 / upTotal) / 100.0 : null);
            s.put(K_SVC_WINDOWS, maintenanceEnds);   // izdüşümde görülebilen pencerelerden maintenance_until'e dönüşür
            s.put(K_MONITORS, monitors);             // izdüşümde kapsam dışı kullanıcıdan çıkarılır
            return s;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Bakım pencereleri
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Etkin ya da 7 gün içinde başlayacak pencere (sayfada gösterilecek). */
    private record WindowRef(MaintenanceWindow w, boolean active, Instant start, Instant end, List<String> targets) {}

    /** Şu an bakımda olan hedefler (pencere id → bitiş) + gösterilecek pencereler — TEK sorgu ({@code findByActiveTrue}). */
    private static final class MaintenanceIndex {
        final Map<Long, Instant> allEnds = new LinkedHashMap<>();                 // "tüm izlemeler" pencereleri
        final Map<String, Map<Long, Instant>> endsByTarget = new HashMap<>();
        final List<WindowRef> windows = new ArrayList<>();

        boolean covers(String target) { return !allEnds.isEmpty() || (target != null && endsByTarget.containsKey(target)); }

        Map<Long, Instant> endsFor(String target) {
            Map<Long, Instant> out = new LinkedHashMap<>(allEnds);
            if (target != null) out.putAll(endsByTarget.getOrDefault(target, Map.of()));
            return out;
        }
    }

    private MaintenanceIndex maintenanceIndex(Instant now) {
        MaintenanceIndex idx = new MaintenanceIndex();
        List<MaintenanceWindow> list;
        try { list = maintenanceRepo.findByActiveTrue(); }
        catch (Exception e) { log.debug("durum sayfası: bakım pencereleri okunamadı: {}", e.toString()); return idx; }
        Instant horizon = now.plus(DAYS, ChronoUnit.DAYS);
        long synthetic = -1;   // id'siz (kaydedilmemiş) pencere — yalnız testlerde; çakışmasın
        for (MaintenanceWindow w : list) {
            if (w == null) continue;
            try {
                long id = w.getId() != null ? w.getId() : synthetic--;
                long dur = Math.min(MaintenanceService.MAX_DURATION_MINUTES,
                        Math.max(1, w.getDurationMinutes() == null ? 60 : w.getDurationMinutes()));
                boolean allMonitors = Boolean.TRUE.equals(w.getAllMonitors());
                List<String> targets = allMonitors ? List.of() : MaintenanceService.targetKeys(w.getTargetsJson());
                Instant end = maintenanceService.activeEndAt(w, now);
                if (end != null) {
                    if (allMonitors) idx.allEnds.put(id, end);
                    else for (String t : targets) idx.endsByTarget.computeIfAbsent(t, x -> new LinkedHashMap<>()).put(id, end);
                    idx.windows.add(new WindowRef(w, true, end.minus(Duration.ofMinutes(dur)), end, targets));
                    continue;
                }
                Instant next = parseUtc(maintenanceService.nextOccurrence(w, now));
                if (next != null && !next.isAfter(horizon)) {
                    idx.windows.add(new WindowRef(w, false, next, next.plus(Duration.ofMinutes(dur)), targets));
                }
            } catch (Exception e) { log.debug("durum sayfası: bakım penceresi #{} atlandı: {}", w.getId(), e.toString()); }
        }
        return idx;
    }

    /** Pencere satırı — ad, zaman, kapsam özeti (hedef anahtarları ASLA; yalnız etkilenen hizmet adları). */
    private static Map<String, Object> windowMap(WindowRef ref, Map<Long, String> teamNames,
                                                 Map<String, Set<String>> serviceKeysByTarget,
                                                 Map<String, Map<String, Object>> serviceByKey) {
        MaintenanceWindow w = ref.w();
        boolean all = Boolean.TRUE.equals(w.getAllMonitors());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("name", w.getName());
        m.put("state", ref.active() ? "active" : "upcoming");
        m.put("starts_at", ISO.format(ref.start()));
        m.put("ends_at", ISO.format(ref.end()));
        m.put("recurrence", w.getRecurrence() == null ? "NONE" : w.getRecurrence());
        m.put("all_monitors", all);
        m.put("monitor_count", all ? null : ref.targets().size());
        m.put("team_id", w.getTeamId());
        m.put("team_name", w.getTeamId() != null ? teamNames.get(w.getTeamId()) : null);
        Set<String> keys = new LinkedHashSet<>();
        if (!all) for (String t : ref.targets()) keys.addAll(serviceKeysByTarget.getOrDefault(t, Set.of()));
        List<Map<String, Object>> names = new ArrayList<>();
        int total = 0;
        for (String k : keys) {
            Map<String, Object> s = serviceByKey.get(k);
            if (s == null) continue;   // aktif izlemesi olmayan grup listelenmez
            total++;
            if (names.size() >= WINDOW_SERVICE_NAMES) continue;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("key", s.get("key"));
            n.put("name", s.get("name"));
            n.put("ungrouped", s.get("ungrouped"));
            n.put("team_name", s.get("team_name"));
            names.add(n);
        }
        m.put("services", names);
        m.put("services_total", total);
        return m;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Olay kayıtları (yalnız özet sütunlar — projeksiyon; görüntüleyici süzmesi izdüşümde)
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private Map<String, Object> incidents(Instant now) {
        String since = ISO.format(now.minus(DAYS, ChronoUnit.DAYS));
        List<Map<String, Object>> active = new ArrayList<>(), resolved = new ArrayList<>();
        List<Object[]> activeCounts = List.of(), resolvedCounts = List.of();
        try {
            for (Object[] r : incidentRepo.statusPageActive(PageRequest.of(0, INCIDENT_BASE_LIMIT))) {
                if (r == null || r.length < 9) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("title", r[1]);
                m.put("severity", r[2]);
                m.put("status", r[3]);
                m.put("started_at", r[4]);
                m.put("services", serviceNames(r[5]));
                m.put("team_id", r[6]);
                m.put("team_name", r[7]);
                m.put(K_CREATED_BY, r[8]);
                active.add(m);
            }
            activeCounts = incidentRepo.statusPageActiveCounts();
        } catch (Exception e) { log.debug("durum sayfası: açık olaylar okunamadı: {}", e.toString()); }
        try {
            for (Object[] r : incidentRepo.statusPageResolvedSince(since, PageRequest.of(0, INCIDENT_BASE_LIMIT))) {
                if (r == null || r.length < 10) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("title", r[1]);
                m.put("severity", r[2]);
                m.put("started_at", r[3]);
                m.put("resolved_at", r[4]);
                m.put("duration_minutes", durationMinutes(r[5], r[3], r[4]));
                m.put("services", serviceNames(r[6]));
                m.put("team_id", r[7]);
                m.put("team_name", r[8]);
                m.put(K_CREATED_BY, r[9]);
                resolved.add(m);
            }
            resolvedCounts = incidentRepo.statusPageResolvedSinceCounts(since);
        } catch (Exception e) { log.debug("durum sayfası: çözülen olaylar okunamadı: {}", e.toString()); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(K_ACTIVE_ROWS, active);
        out.put(K_ACTIVE_COUNTS, activeCounts == null ? List.of() : activeCounts);
        out.put("active_total", Math.max(sumCounts(activeCounts), active.size()));
        out.put(K_RESOLVED_ROWS, resolved);
        out.put(K_RESOLVED_COUNTS, resolvedCounts == null ? List.of() : resolvedCounts);
        out.put("resolved_total", Math.max(sumCounts(resolvedCounts), resolved.size()));
        out.put("days", DAYS);
        return out;
    }

    private static long sumCounts(List<Object[]> counts) {
        long n = 0;
        if (counts != null) for (Object[] g : counts) if (g != null && g.length >= 3 && g[2] instanceof Number c) n += c.longValue();
        return n;
    }

    /** Etkilenen servis CSV'si → ad listesi (tekil, boşsuz, en fazla {@value #INCIDENT_SERVICE_NAMES}); adres biçimli
     *  girdiler ({@code ://}) atılır — sayfa hiçbir URL göstermez. */
    static List<String> serviceNames(Object csv) {
        if (!(csv instanceof String s) || s.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.split(",")) {
            String v = p.trim();
            if (v.isEmpty() || v.contains("://")) continue;
            out.add(v);
            if (out.size() >= INCIDENT_SERVICE_NAMES) break;
        }
        return new ArrayList<>(out);
    }

    /** Süre (dk): kayıttaki değer (≥ 0) yoksa oluş → çözülme farkı; hesaplanamazsa null. */
    static Long durationMinutes(Object stored, Object occurredAt, Object resolvedAt) {
        if (stored instanceof Number n && n.longValue() >= 0) return n.longValue();
        Instant a = parseUtc(occurredAt instanceof String s ? s : null), b = parseUtc(resolvedAt instanceof String s ? s : null);
        if (a == null || b == null || b.isBefore(a)) return null;
        return Duration.between(a, b).toMinutes();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 7 günlük kullanılabilirlik (rollup)
    // ════════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Son {@value #DAYS} TAMAMLANMIŞ UTC günü (bugün hariç — rollup gece yazılır). Gün başına bir kez okunur. */
    UptimeData uptime(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String from = today.minusDays(DAYS).toString(), to = today.toString();
        long ttl = cacheMs > 0 ? UPTIME_CACHE_MS : 0;
        return uptimeMemo.get(from, ttl, false, () -> loadUptime(from, to));
    }

    private UptimeData loadUptime(String from, String to) {
        if (jdbc == null) return new UptimeData(false, Map.of());
        try {
            Map<String, long[]> out = new HashMap<>();
            for (Map<String, Object> r : jdbc.queryForList(UPTIME_SQL, from, to)) {
                String type = ROLLUP_TYPES.get(String.valueOf(r.get("monitor_type")));
                Object key = r.get("monitor_key");
                if (type == null || key == null) continue;
                long total = r.get("total") instanceof Number n ? n.longValue() : 0L;
                long up = r.get("up") instanceof Number n ? n.longValue() : 0L;
                out.put(type + "|" + String.valueOf(key).trim(), new long[]{ total, up });
            }
            return new UptimeData(true, out);
        } catch (Exception e) {
            log.debug("durum sayfası: kullanılabilirlik rollup'ı okunamadı: {}", e.toString());
            return new UptimeData(false, Map.of());
        }
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object v) {
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** UTC ISO ("yyyy-MM-ddTHH:mm[:ss]", bölge eksiz) ya da Z/ofsetli damga → Instant; çözülemezse null. */
    static Instant parseUtc(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            if (s.endsWith("Z") || s.endsWith("z")) return Instant.parse(s.toUpperCase(Locale.ROOT));
            if (s.matches(".*[+-]\\d\\d:?\\d\\d$")) return OffsetDateTime.parse(s).toInstant();
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
