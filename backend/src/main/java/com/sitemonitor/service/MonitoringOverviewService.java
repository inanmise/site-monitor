package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * İZLEME PANOSU (2026-09-30, kullanıcı isteği): İzleme menüsündeki 9 izleme türünün TEK ekranda durumu — tür başına
 * izleme sayıları (toplam / aktif / duraklatılmış / silinmiş), şu an düşük / eski (kontrolü gecikmiş) / hiç kontrol
 * edilmemiş izlemeler, son kontrol zamanı, pencere içi (varsayılan 24 sa) koşum ve başarı sayıları, açık ve pencerede
 * çözülen alarmlar; ayrıca izleme başına satır (ad, hedef, takım, durum, son kontrol, açık alarm) — sayfa süzer/sıralar.
 *
 * <p>Kapsam: {@code canViewTeam} yüklemi (İzleme sayfalarının kendi listeleriyle AYNI: satırın takımı görüş
 * kapsamında olmalı); alarmlar hedef anahtarıyla izlemelere BAĞLANIR (alarm olayının {@code domain}'i sweep anahtarıdır:
 * HTTP/İçerik/Sayfa/Sayfa Hızı → url, Ping/Port → host, DNS/Alan adı → domain, Sentetik → ad) — kapsam izlemelerden
 * miras kalır. Yalnızca okuma; mevcut sorgular (son kontrol haritası + gruplu pencere sayımı) — tür başına 3 sorgu.
 *
 * <p><b>Performans (2026-10-01):</b> sayfa dakikada bir yoklanır. Aktif envanter host'ları tek sütunluk projeksiyondan
 * ({@code findActiveDomains}), pencerede çözülen alarmlar (tip, damgalı takım) başına gruplu sayımdan
 * ({@code countRecoveredSinceByTypeAndTeam}) gelir — tam entity yüklenmez. Denetleyici girişi {@link #build(String,
 * Predicate, boolean, int)} sonucu (görüş kapsamı anahtarı, pencere) başına {@code site.monitor.monitoring-overview.cache-ms}
 * (varsayılan 30 sn) paylaşır.
 *
 * <p><b>Zenginleştirme (2026-10-01, sayfa yeniden tasarımı) — YENİ SORGU YOK:</b> satıra pencere başarı oranı
 * ({@code success_rate_window}), pencere ortalama yanıt/süre ({@code avg_response_ms_window} — HTTP/Ping/Sayfa/Sayfa Hızı
 * sorgusunun zaten döndürdüğü {@code AVG(responseMs)}, Sentetik'te {@code AVG(durationMs)}; DNS'te 4. sütun değişim sayısı
 * olduğu için okunmaz), açık alarmın başlangıcı ({@code open_since} — en eski açık alarmın {@code createdAt}'i) ve
 * sahiplenme ({@code open_acknowledged} — açık alarmların HEPSİ sahiplenilmiş) eklenir; tür özetine ağırlıklı ortalama
 * yanıt, toplamlara filo başarı oranı. Sayfanın Yenile düğmesi {@code fresh=true} ile belleği en fazla
 * {@link #FRESH_MIN_MS}'de bir atlar (düğmeye art arda basmak DB'ye art arda binmesin).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringOverviewService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** "Eski" (kontrolü gecikmiş) eşiği: aralığın bu katı geçtiyse. */
    static final int STALE_FACTOR = 3;
    /** Aralığı olmayan izlemede eski eşiği (sn). */
    static final long STALE_FALLBACK_SECONDS = 3 * 3600;
    /** Taze istek ({@code fresh=true}) belleği ancak kayıt bu yaştan eskiyse atlar (ms) — Yenile düğmesi sel koruması. */
    static final long FRESH_MIN_MS = 5_000;
    /** Pencere sorgusunun 4. sütunu ORTALAMA yanıt/süre olan türler (DNS'te aynı sütun değişim sayısıdır — okunmaz). */
    static final Set<String> AVG_RESPONSE_TYPES = Set.of("http", "ping", "page", "pagespeed", "scripted");

    private final HttpMonitorRepository httpMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;

    private final HttpCheckRepository httpCheckRepo;
    private final PingCheckRepository pingCheckRepo;
    private final PortCheckRepository portCheckRepo;
    private final DnsRecordRepository dnsRecordRepo;
    private final KeywordResultRepository keywordResultRepo;
    private final PageCheckRepository pageCheckRepo;
    private final PageSpeedCheckRepository pageSpeedCheckRepo;
    private final ScriptedCheckRepository scriptedCheckRepo;
    private final DomainCheckRepository domainCheckRepo;

    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;

    /**
     * Envanter türevi Port/DNS izlemeleri (2026-09-30, kullanıcı bildirimi): host'u artık AKTİF envanterde olmayan
     * satırı tarama atlar ({@code SchedulerService} "not in active inventory") ve tür sayfası listelemez; pano ise onu
     * "aktif ama kontrol edilmemiş" sayıp GECİKMİŞ gösteriyordu — kullanıcı sayfada bulamıyordu. Aynı kural burada:
     * böyle satır "duraklatılmış" sayılır ({@code inventory_inactive} bayrağıyla). Alan enjeksiyonu, null-güvenli.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;

    /** Bellekli girişin hesaplamayı PROXY üzerinden çağırması için (salt-okunur işlem korunur) — CertificateService deseni.
     *  Birim testinde null → doğrudan {@code this}. */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private MonitoringOverviewService self;

    /** Sunucu tarafı bellek penceresi (ms); 0 → kapalı (birim testlerinde {@code new} ile kurulunca varsayılan). */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.monitoring-overview.cache-ms:30000}")
    long cacheMs;

    /** Bellek anahtar tavanı (kapsam × pencere kombinasyonu). */
    static final int MEMO_MAX_KEYS = 500;
    /** Final değil: birim testi saat enjekte edilmiş bir bellekle değiştirir (fresh sel koruması testi). */
    private com.sitemonitor.util.TtlMemo<Map<String, Object>> memo = new com.sitemonitor.util.TtlMemo<>(MEMO_MAX_KEYS);

    /**
     * Bir izleme türünün panodaki tanımı — satır ve son-kontrol okuyucuları. {@code unmonitored}: tarama bu satırı atlar;
     * {@code standalone}: Port/DNS'te kullanıcının eklediği (envanterden bağımsız) satır, diğer türlerde null.
     */
    private record TypeSpec<M, C>(String type,
                                 java.util.function.Supplier<List<M>> monitors,
                                 Function<M, Long> id, Function<M, String> name, Function<M, String> target,
                                 Function<M, Long> team, Function<M, Boolean> active, Function<M, Boolean> deleted,
                                 Function<M, Integer> interval,
                                 java.util.function.Supplier<List<C>> latest, Function<C, Long> checkMonitorId,
                                 Function<C, Boolean> ok, Function<C, String> checkedAt, Function<C, Object> responseMs,
                                 Function<C, String> error,
                                 StatsQuery stats,
                                 Function<M, Boolean> unmonitored,
                                 Function<M, Boolean> standalone) {
        TypeSpec(String type, java.util.function.Supplier<List<M>> monitors,
                 Function<M, Long> id, Function<M, String> name, Function<M, String> target,
                 Function<M, Long> team, Function<M, Boolean> active, Function<M, Boolean> deleted,
                 Function<M, Integer> interval,
                 java.util.function.Supplier<List<C>> latest, Function<C, Long> checkMonitorId,
                 Function<C, Boolean> ok, Function<C, String> checkedAt, Function<C, Object> responseMs,
                 Function<C, String> error, StatsQuery stats) {
            this(type, monitors, id, name, target, team, active, deleted, interval, latest, checkMonitorId, ok, checkedAt,
                 responseMs, error, stats, m -> false, m -> null);
        }
    }

    /**
     * Aktif envanterin alan adları — HAM (normalizasyon YOK). Tarama ({@code SchedulerService} port/DNS sweep'i)
     * {@code activeDomains.contains(m.getHost()/getDomain())} ile BİREBİR, büyük/küçük harf duyarlı karşılaştırır; pano da
     * aynısını yapmalı. 2026-10-01 prod hatası: küçük harfe çevrilmiş karşılaştırma, envanterde yalnız harf büyüklüğü
     * farklı kalan (ya da envanterden çıkarılıp aynı host için bağımsız izlemesi açılan) eski envanter-türevi satırları
     * "aktif ama kontrolü gecikmiş" sayıyordu — tarama onları hiç kontrol etmediği için DNS kartı 3 "gecikmiş" gösteriyor,
     * DNS sayfası onları listelemiyordu. Depo yoksa null = "bilinmiyor, hiçbirini atlama".
     */
    private java.util.Set<String> activeInventoryHosts() {
        if (inventoryRepo == null) return null;
        try {
            return new java.util.HashSet<>(inventoryRepo.findActiveDomains());
        } catch (Exception e) { log.debug("aktif envanter okunamadı: {}", e.toString()); return null; }
    }

    /**
     * Envanter türevi satır (standalone değil) ve host'u aktif envanterde BİREBİR yok → tarama atlar. Taramayla aynı:
     * host null ise de atlanır ({@code contains(null)} — envanterde alan adı boş aktif satır olmaz).
     */
    static boolean inventoryInactive(java.util.Set<String> activeHosts, Boolean standalone, String host) {
        if (activeHosts == null || Boolean.TRUE.equals(standalone)) return false;
        return host == null || !activeHosts.contains(host);
    }

    @FunctionalInterface
    private interface StatsQuery { List<Object[]> run(Collection<Long> ids, String from, String to); }

    /**
     * Denetleyici girişi — bellekli: sonuç {@code (scopeKey, pencere, seesAllAlerts)} başına {@link #cacheMs} boyunca
     * paylaşılır. {@code scopeKey} yüklemi TAM belirlemelidir (global görüntüleyici {@code "ALL"}, aksi halde sıralı görüş
     * takımları — {@link com.sitemonitor.util.TtlMemo#scopeKey}); null anahtar belleği atlar. Dönen harita PAYLAŞILIR —
     * çağıran değiştirmez.
     */
    public Map<String, Object> build(String scopeKey, Predicate<Long> canViewTeam, boolean seesAllAlerts, int hours) {
        return build(scopeKey, canViewTeam, seesAllAlerts, hours, false);
    }

    /**
     * {@code fresh=true} (sayfanın Yenile düğmesi): bellekteki kayıt {@link #FRESH_MIN_MS}'den eskiyse yeniden hesaplanır
     * ve bellek TAZELENİR (sonraki yoklamalar da taze veriyi görür); daha yeniyse o kayıt döner — düğmeye art arda basmak
     * kapsam başına 5 sn'de birden sık hesaplama yaptırmaz. Bellek kapalıysa ({@code cacheMs ≤ 0}) her çağrı hesaplar.
     */
    public Map<String, Object> build(String scopeKey, Predicate<Long> canViewTeam, boolean seesAllAlerts, int hours, boolean fresh) {
        int h = Math.max(1, Math.min(hours, 24 * 30));
        MonitoringOverviewService target = self != null ? self : this;
        String key = scopeKey == null ? null : scopeKey + "|h=" + h + "|all=" + seesAllAlerts;
        long ttl = fresh && cacheMs > 0 ? Math.min(cacheMs, FRESH_MIN_MS) : cacheMs;
        return memo.get(key, ttl, false, () -> target.build(canViewTeam, seesAllAlerts, h));
    }

    /** Kuruluş geneli özetin penceresi (sa) — İzleme Panosu'nun varsayılan penceresi; anahtar onunla AYNI olsun diye. */
    public static final int ORG_SUMMARY_WINDOW_HOURS = 24;

    /**
     * Kuruluş geneli (KAPSAMSIZ) özet — giriş sayfası "Kullanım istatistikleri" (2026-10-04, kullanıcı bildirimi: pano
     * 688 sağlıklı izleme gösterirken giriş sayfası fırtına paydasından 621 "izleme" gösteriyordu). Hesap ve bellek
     * global görüntüleyicinin İzleme Panosu ile BİREBİR aynıdır: anahtar {@code TtlMemo.scopeKey(true, null)} +
     * {@link #ORG_SUMMARY_WINDOW_HOURS} + tüm alarmlar ({@code StatusPageService} de bu anahtarı kullanır) — iki ekran
     * aynı anda aynı sayıyı görür, ek hesap yapılmaz. Yalnız SAYILAR döner (satır, ad, hedef yok):
     * {@code total, active, healthy, down, stale, unknown, paused, checks_window, failed_window, window_hours,
     * generated_at}. {@code healthy} = {@link #healthyOf} (pano "Sağlıklı" kutusu ile aynı formül). Toplamlar
     * okunamazsa {@code null}.
     */
    public Map<String, Object> orgSummary() {
        // Proxy (self) üzerinden — bellekli build zaten @Transactional asıl hesabı self ile çağırıyor; çağrının da proxy'den
        // geçmesi TransactionalSelfInvocationGuardTest'in aşırı yükleri ayırt edemeyen taramasını da netleştirir.
        MonitoringOverviewService target = self != null ? self : this;
        Map<String, Object> built = target.build(com.sitemonitor.util.TtlMemo.scopeKey(true, null), team -> true, true,
                ORG_SUMMARY_WINDOW_HOURS);
        if (built == null || !(built.get("totals") instanceof Map<?, ?> totals)) return null;
        long active = num(totals.get("active"));
        long down = num(totals.get("down"));
        long stale = num(totals.get("stale"));
        long unknown = num(totals.get("unknown"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", num(totals.get("total")));
        out.put("active", active);
        out.put("healthy", healthyOf(active, down, stale, unknown));
        out.put("down", down);
        out.put("stale", stale);
        out.put("unknown", unknown);
        out.put("paused", num(totals.get("paused")));
        out.put("checks_window", num(totals.get("checks_window")));
        out.put("failed_window", num(totals.get("failed_window")));
        out.put("window_hours", built.get("window_hours"));
        out.put("generated_at", built.get("generated_at"));
        return out;
    }

    /**
     * "Sağlıklı" izleme sayısı — İzleme Panosu KPI'ı ile AYNI formül ({@code MonitoringOverviewPage.jsx} "up" kutusu):
     * aktif − düşük − gecikmiş − hiç kontrol edilmemiş. Üçü de aktif kümesinin alt kümesidir (duraklatılmış/silinmiş
     * satır bu durumları almaz), sonuç durum "up" olan satır sayısıdır; savunma için tabanı 0.
     */
    public static long healthyOf(long active, long down, long stale, long unknown) {
        return Math.max(0L, active - down - stale - unknown);
    }

    private static long num(Object v) { return v instanceof Number n ? n.longValue() : 0L; }

    /** Sayfa satırı ve tür özeti — {@code Map} olarak (Jackson snake_case ile doğrudan yanıt). Belleksiz hesaplama. */
    @Transactional(readOnly = true)
    public Map<String, Object> build(Predicate<Long> canViewTeam, boolean seesAllAlerts, int hours) {
        int h = Math.max(1, Math.min(hours, 24 * 30));
        Instant nowI = Instant.now();
        String now = ISO.format(nowI);
        String from = ISO.format(nowI.minus(h, ChronoUnit.HOURS));

        Map<Long, String> teamNames = new HashMap<>();
        try { for (Team t : teamRepo.findAll()) if (t.getId() != null) teamNames.put(t.getId(), t.getName()); }
        catch (Exception e) { log.debug("takım adları okunamadı: {}", e.toString()); }

        // Açık alarmlar — hedef anahtarına göre (izlemeye bağlanır; kapsam izlemeden miras). Tür süzgeci katalogdan.
        Map<String, List<AlertEvent>> openByKey = new HashMap<>();
        try {
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (e.getDomain() == null) continue;
                String family = MonitorTypeCatalog.typeOfAlert(e.getAlertType());
                if (family == null || "cert".equals(family)) continue;
                openByKey.computeIfAbsent(family + "|" + e.getDomain().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
            }
        } catch (Exception e) { log.debug("açık alarmlar okunamadı: {}", e.toString()); }

        List<Map<String, Object>> types = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        types.add(summarize(httpSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pingSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        java.util.Set<String> activeHosts = activeInventoryHosts();
        types.add(summarize(portSpec(activeHosts), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(dnsSpec(activeHosts), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(domainSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(keywordSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pageSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pageSpeedSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(scriptedSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));

        // Pencerede çözülen alarmlar (tür başına) — kapsam: alarm satırının takımı görüş kapsamında (global → hepsi).
        // Gruplu sayım (tip, damgalı takım): sessiz kapanış sorguda elenir, kapsam takım sütunu üzerinden burada.
        Map<String, Long> resolvedByType = new HashMap<>();
        try {
            for (Object[] r : alertEventRepo.countRecoveredSinceByTypeAndTeam(from)) {
                if (r == null || r.length < 3 || r[0] == null) continue;
                Long teamId = r[1] instanceof Number tid ? tid.longValue() : null;
                long n = r[2] instanceof Number c ? c.longValue() : 0L;
                if (n <= 0) continue;
                if (!seesAllAlerts && !(teamId != null && canViewTeam.test(teamId))) continue;
                String family = MonitorTypeCatalog.typeOfAlert(String.valueOf(r[0]));
                if (family == null) continue;
                resolvedByType.merge(family, n, Long::sum);
            }
        } catch (Exception e) { log.debug("çözülen alarmlar okunamadı: {}", e.toString()); }
        for (Map<String, Object> t : types) t.put("resolved_window", resolvedByType.getOrDefault(String.valueOf(t.get("type")), 0L));

        linkStandaloneTwins(rows);

        Map<String, Object> totals = new LinkedHashMap<>();
        for (String k : List.of("total", "active", "paused", "inventory_inactive", "deleted", "down", "stale", "unknown", "checks_window",
                "failed_window", "open_alerts", "open_critical", "resolved_window")) {
            long sum = 0;
            for (Map<String, Object> t : types) sum += ((Number) t.getOrDefault(k, 0L)).longValue();
            totals.put(k, sum);
        }
        totals.put("success_rate_window", successRate(((Number) totals.get("checks_window")).longValue(),
                ((Number) totals.get("failed_window")).longValue()));
        String lastChecked = null;
        for (Map<String, Object> t : types) {
            Object v = t.get("last_checked_at");
            if (v instanceof String s && (lastChecked == null || s.compareTo(lastChecked) > 0)) lastChecked = s;
        }
        totals.put("last_checked_at", lastChecked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", now);
        out.put("window_hours", h);
        out.put("totals", totals);
        out.put("types", types);
        out.put("monitors", rows);
        return out;
    }

    private <M, C> Map<String, Object> summarize(TypeSpec<M, C> spec, Predicate<Long> canViewTeam, Map<Long, String> teamNames,
                                                 Map<String, List<AlertEvent>> openByKey, String from, String now, Instant nowI,
                                                 List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", spec.type());
        long total = 0, active = 0, paused = 0, deleted = 0, down = 0, stale = 0, unknown = 0, openAlerts = 0, openCritical = 0;
        long checks = 0, failed = 0, inventoryInactive = 0;
        String lastChecked = null;

        List<M> monitors;
        try { monitors = spec.monitors().get(); } catch (Exception e) { log.warn("{} izlemeleri okunamadı: {}", spec.type(), e.toString()); monitors = List.of(); }
        List<M> visible = new ArrayList<>();
        for (M m : monitors) {
            if (m == null) continue;
            Long team = spec.team().apply(m);
            if (!canViewTeam.test(team)) continue;
            visible.add(m);
        }
        Map<Long, C> latest = new HashMap<>();
        try {
            for (C c : spec.latest().get()) { Long id = spec.checkMonitorId().apply(c); if (id != null) latest.putIfAbsent(id, c); }
        } catch (Exception e) { log.debug("{} son kontrolleri okunamadı: {}", spec.type(), e.toString()); }

        List<Long> ids = new ArrayList<>();
        for (M m : visible) if (!Boolean.TRUE.equals(spec.deleted().apply(m))) ids.add(spec.id().apply(m));
        // [toplam, başarılı, ortalama yanıt (ms, yoksa -1), ortalamanın ağırlığı (ölçümlü kontrol sayısı)]
        Map<Long, long[]> stats = new HashMap<>();
        boolean hasAvg = AVG_RESPONSE_TYPES.contains(spec.type());
        if (!ids.isEmpty()) {
            try {
                for (Object[] r : spec.stats().run(ids, from, now)) {
                    if (r == null || r.length < 3 || r[0] == null) continue;
                    long tot = r[1] instanceof Number n ? n.longValue() : 0L;
                    long ok = r[2] instanceof Number n ? n.longValue() : 0L;
                    long avg = hasAvg && r.length > 3 && r[3] instanceof Number a ? Math.round(a.doubleValue()) : -1L;
                    // Ağırlık: ölçümü olan kontrol sayısı (5. sütun); Sentetik sorgusunda yok → toplam koşum.
                    long weight = avg < 0 ? 0L : r.length > 4 && r[4] instanceof Number w ? w.longValue() : tot;
                    stats.put(((Number) r[0]).longValue(), new long[]{ tot, ok, avg, weight });
                }
            } catch (Exception e) { log.debug("{} pencere sayımı okunamadı: {}", spec.type(), e.toString()); }
        }
        double avgSum = 0; long avgWeight = 0;

        for (M m : visible) {
            Long id = spec.id().apply(m);
            boolean isDeleted = Boolean.TRUE.equals(spec.deleted().apply(m));
            boolean unmonitored = Boolean.TRUE.equals(spec.unmonitored().apply(m));   // envanter pasif → tarama atlar
            boolean isActive = Boolean.TRUE.equals(spec.active().apply(m)) && !unmonitored;
            String target = spec.target().apply(m);
            C c = latest.get(id);
            String checkedAt = c != null ? spec.checkedAt().apply(c) : null;
            Boolean ok = c != null ? spec.ok().apply(c) : null;
            // Taramanın atladığı (envanterden çıkmış) satıra açık alarm BAĞLANMAZ: alarm hedef anahtarıyla eşleşir ve
            // aynı host'un taranan bağımsız izlemesine aittir — iki satıra bağlanırsa açık alarm iki kez sayılır, eski
            // satır "sorunlu" görünürdü (2026-10-01 prod: outboundivrtahprod'un eski Port satırı).
            List<AlertEvent> open = target == null || unmonitored ? List.of()
                    : openByKey.getOrDefault(spec.type() + "|" + target.toLowerCase(Locale.ROOT), List.of());
            String openLevel = null;
            String openSince = null;
            boolean allAcked = !open.isEmpty();
            for (AlertEvent e : open) {
                if (rank(e.getAlertLevel()) > rank(openLevel)) openLevel = e.getAlertLevel();
                String created = e.getCreatedAt();
                if (created != null && (openSince == null || created.compareTo(openSince) < 0)) openSince = created;
                if (!Boolean.TRUE.equals(e.getAcknowledged())) allAcked = false;
            }

            String status;
            if (isDeleted) status = "deleted";
            else if (!isActive) status = "paused";
            else if (c == null) status = "unknown";
            else if (!open.isEmpty() || Boolean.FALSE.equals(ok)) status = "down";
            else if (isStale(checkedAt, spec.interval().apply(m), nowI)) status = "stale";
            else status = "up";

            total++;
            if (isDeleted) deleted++;
            else if (!isActive) { paused++; if (unmonitored) inventoryInactive++; }
            else active++;
            if ("down".equals(status)) down++;
            if ("stale".equals(status)) stale++;
            if ("unknown".equals(status)) unknown++;
            if (!isDeleted) { openAlerts += open.size(); for (AlertEvent e : open) if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) openCritical++; }
            long[] st = stats.get(id);
            long rowChecks = st != null ? st[0] : 0L, rowFailed = st != null ? Math.max(0, st[0] - st[1]) : 0L;
            checks += rowChecks; failed += rowFailed;
            Long rowAvg = st != null && st[2] >= 0 ? st[2] : null;
            if (rowAvg != null && st[3] > 0) { avgSum += (double) rowAvg * st[3]; avgWeight += st[3]; }
            if (checkedAt != null && (lastChecked == null || checkedAt.compareTo(lastChecked) > 0)) lastChecked = checkedAt;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", spec.type());
            row.put("id", id);
            row.put("name", spec.name().apply(m));
            row.put("target", target);
            Long team = spec.team().apply(m);
            row.put("team_id", team);
            row.put("team_name", team != null ? teamNames.get(team) : null);
            row.put("active", isActive);
            row.put("deleted", isDeleted);
            row.put("standalone", spec.standalone().apply(m));
            row.put("inventory_inactive", unmonitored);
            row.put("status", status);
            row.put("last_checked_at", checkedAt);
            row.put("last_ok", ok);
            row.put("response_ms", c != null ? spec.responseMs().apply(c) : null);
            row.put("last_error", c != null ? spec.error().apply(c) : null);
            row.put("interval_seconds", spec.interval().apply(m));
            row.put("open_alerts", open.size());
            row.put("open_alert_level", openLevel);
            row.put("open_since", openSince);
            row.put("open_acknowledged", allAcked);
            row.put("checks_window", rowChecks);
            row.put("failed_window", rowFailed);
            row.put("success_rate_window", successRate(rowChecks, rowFailed));
            row.put("avg_response_ms_window", rowAvg);
            // İzleme grubu (2026-10-01, Durum Sayfası): hizmet = takım + grup adı. Yüklenmiş entity'den — ek sorgu YOK.
            row.put("group_name", groupNameOf(m));
            rows.add(row);
        }

        t.put("total", total); t.put("active", active); t.put("paused", paused); t.put("deleted", deleted);
        // paused'un alt kümesi: envanterden çıkmış (taramanın atladığı) eski envanter-türevi satırlar — tür sayfası
        // bunları listelemez; kart "aktif + duraklatılmış" sayısını sayfayla eşlemek için bunu düşer.
        t.put("inventory_inactive", inventoryInactive);
        t.put("down", down); t.put("stale", stale); t.put("unknown", unknown);
        t.put("checks_window", checks); t.put("failed_window", failed);
        t.put("success_rate_window", successRate(checks, failed));
        t.put("avg_response_ms_window", avgWeight > 0 ? Math.round(avgSum / avgWeight) : null);
        t.put("open_alerts", openAlerts); t.put("open_critical", openCritical);
        t.put("last_checked_at", lastChecked);
        return t;
    }

    static boolean isStale(String checkedAt, Integer intervalSeconds, Instant now) {
        if (checkedAt == null) return false;
        try {
            Instant at = java.time.LocalDateTime.parse(checkedAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")).toInstant(ZoneOffset.UTC);
            long limit = intervalSeconds != null && intervalSeconds > 0 ? (long) intervalSeconds * STALE_FACTOR : STALE_FALLBACK_SECONDS;
            return at.plusSeconds(Math.max(limit, 600)).isBefore(now);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Envanterden çıkmış eski envanter-türevi satıra (taramanın atladığı, {@code inventory_inactive}) aynı türde, aynı
     * hedefte (büyük/küçük harf duyarsız — bağımsız izleme küçük harfle kaydedilir) AKTİF ve silinmemiş bağımsız
     * (standalone) izleme varsa onu {@code standalone_twin} olarak bağlar: host'un ASIL kontrolü odur, arayüz
     * "duraklatılmış" açıklamasında ona yönlendirir. Yalnız yüklenmiş satırlardan — ek sorgu yok.
     */
    static void linkStandaloneTwins(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> live = new HashMap<>();
        for (Map<String, Object> r : rows) {
            if (!Boolean.TRUE.equals(r.get("standalone")) || !Boolean.TRUE.equals(r.get("active"))
                    || Boolean.TRUE.equals(r.get("deleted")) || !(r.get("target") instanceof String tg)) continue;
            live.putIfAbsent(r.get("type") + "|" + tg.trim().toLowerCase(Locale.ROOT), r);
        }
        for (Map<String, Object> r : rows) {
            if (!Boolean.TRUE.equals(r.get("inventory_inactive")) || !(r.get("target") instanceof String tg)) continue;
            Map<String, Object> twin = live.get(r.get("type") + "|" + tg.trim().toLowerCase(Locale.ROOT));
            if (twin == null) continue;
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("id", twin.get("id"));
            link.put("name", twin.get("name"));
            link.put("target", twin.get("target"));
            link.put("status", twin.get("status"));
            r.put("standalone_twin", link);
        }
    }

    /** Başarı oranı (%, tek ondalık) — koşum yoksa null. Tür, satır ve toplam AYNI yuvarlamayı kullanır. */
    static Double successRate(long checks, long failed) {
        return checks > 0 ? Math.round((checks - failed) * 1000.0 / checks) / 10.0 : null;
    }

    private static int rank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase(Locale.ROOT)) { case "CRITICAL" -> 3; case "HIGH" -> 2; case "WARNING" -> 1; default -> 0; };
    }

    private static Long asLong(Object v) { return v instanceof Number n ? n.longValue() : null; }

    // ── Tür tanımları ─────────────────────────────────────────────────────────

    private TypeSpec<HttpMonitor, HttpCheck> httpSpec() {
        return new TypeSpec<>("http", httpMonitorRepo::findAllByOrderByNameAsc,
                HttpMonitor::getId, m -> nz(m.getName(), m.getUrl()), HttpMonitor::getUrl, HttpMonitor::getTeamId,
                HttpMonitor::getActive, m -> false, HttpMonitor::getIntervalSeconds,
                httpCheckRepo::findLatestPerMonitor, HttpCheck::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), HttpCheck::getCheckedAt, HttpCheck::getResponseMs, HttpCheck::getError,
                httpCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PingMonitor, PingCheck> pingSpec() {
        return new TypeSpec<>("ping", pingMonitorRepo::findAllByOrderByNameAsc,
                PingMonitor::getId, m -> nz(m.getName(), m.getHost()), PingMonitor::getHost, PingMonitor::getTeamId,
                PingMonitor::getActive, m -> false, PingMonitor::getIntervalSeconds,
                pingCheckRepo::findLatestPerMonitor, PingCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getUp()), PingCheck::getCheckedAt, PingCheck::getRttMs, PingCheck::getError,
                pingCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PortMonitor, PortCheck> portSpec(java.util.Set<String> activeHosts) {
        return new TypeSpec<>("port", portMonitorRepo::findAllByOrderByNameAsc,
                PortMonitor::getId, m -> nz(m.getName(), m.getHost() + ":" + m.getPort()), PortMonitor::getHost, PortMonitor::getTeamId,
                PortMonitor::getActive, m -> m.getDeletedAt() != null, PortMonitor::getIntervalSeconds,
                portCheckRepo::findLatestPerMonitor, PortCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOpen()), PortCheck::getCheckedAt, PortCheck::getResponseMs, PortCheck::getError,
                portCheckRepo::weeklyStatsByMonitor,
                m -> inventoryInactive(activeHosts, m.getStandalone(), m.getHost()), m -> Boolean.TRUE.equals(m.getStandalone()));
    }

    private TypeSpec<DnsMonitor, com.sitemonitor.model.DnsRecord> dnsSpec(java.util.Set<String> activeHosts) {
        return new TypeSpec<>("dns", dnsMonitorRepo::findAllByOrderByNameAsc,
                DnsMonitor::getId, m -> nz(m.getName(), m.getDomain()), DnsMonitor::getDomain, DnsMonitor::getTeamId,
                DnsMonitor::getActive, m -> m.getDeletedAt() != null, DnsMonitor::getIntervalSeconds,
                dnsRecordRepo::findLatestPerMonitor, com.sitemonitor.model.DnsRecord::getMonitorId,
                c -> c.getValue() != null && !c.getValue().isBlank(), com.sitemonitor.model.DnsRecord::getCheckedAt,
                com.sitemonitor.model.DnsRecord::getResponseMs, c -> null,
                dnsRecordRepo::weeklyStatsByMonitor,
                m -> inventoryInactive(activeHosts, m.getStandalone(), m.getDomain()), m -> Boolean.TRUE.equals(m.getStandalone()));
    }

    private TypeSpec<DomainMonitor, DomainCheck> domainSpec() {
        return new TypeSpec<>("domain", domainMonitorRepo::findAllByOrderByNameAsc,
                DomainMonitor::getId, m -> nz(m.getName(), m.getDomain()), DomainMonitor::getDomain, DomainMonitor::getTeamId,
                DomainMonitor::getActive, m -> false, DomainMonitor::getIntervalSeconds,
                domainCheckRepo::findLatestPerMonitor, DomainCheck::getMonitorId,
                c -> c.getError() == null && !"UNKNOWN".equalsIgnoreCase(c.getStatus()), DomainCheck::getCheckedAt, c -> null, DomainCheck::getError,
                domainCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<KeywordMonitor, KeywordResult> keywordSpec() {
        return new TypeSpec<>("keyword", keywordMonitorRepo::findAllByOrderByNameAsc,
                KeywordMonitor::getId, m -> nz(m.getName(), m.getUrl()), KeywordMonitor::getUrl, KeywordMonitor::getTeamId,
                KeywordMonitor::getActive, m -> false, KeywordMonitor::getIntervalSeconds,
                keywordResultRepo::findLatestPerMonitor, KeywordResult::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), KeywordResult::getCheckedAt, KeywordResult::getResponseMs, KeywordResult::getError,
                keywordResultRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PageMonitor, PageCheck> pageSpec() {
        return new TypeSpec<>("page", pageMonitorRepo::findAllByOrderByNameAsc,
                PageMonitor::getId, m -> nz(m.getName(), m.getUrl()), PageMonitor::getUrl, PageMonitor::getTeamId,
                PageMonitor::getActive, m -> false, PageMonitor::getIntervalSeconds,
                pageCheckRepo::findLatestPerMonitor, PageCheck::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), PageCheck::getCheckedAt, PageCheck::getResponseMs, PageCheck::getError,
                pageCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PageSpeedMonitor, PageSpeedCheck> pageSpeedSpec() {
        return new TypeSpec<>("pagespeed", pageSpeedMonitorRepo::findAllByOrderByNameAsc,
                PageSpeedMonitor::getId, m -> nz(m.getName(), m.getUrl()), PageSpeedMonitor::getUrl, PageSpeedMonitor::getTeamId,
                PageSpeedMonitor::getActive, m -> false, PageSpeedMonitor::getIntervalSeconds,
                pageSpeedCheckRepo::findLatestPerMonitor, PageSpeedCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOk()), PageSpeedCheck::getCheckedAt, PageSpeedCheck::getResponseMs, c -> null,
                pageSpeedCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<ScriptedMonitor, ScriptedCheck> scriptedSpec() {
        return new TypeSpec<>("scripted", scriptedMonitorRepo::findAllByOrderByNameAsc,
                ScriptedMonitor::getId, ScriptedMonitor::getName, ScriptedMonitor::getName, ScriptedMonitor::getTeamId,
                ScriptedMonitor::getActive, m -> false, ScriptedMonitor::getIntervalSeconds,
                scriptedCheckRepo::findLatestPerMonitor, ScriptedCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOk()), ScriptedCheck::getCheckedAt, ScriptedCheck::getDurationMs, ScriptedCheck::getError,
                scriptedCheckRepo::weeklyStatsByMonitor);
    }

    private static String nz(String a, String b) { return a != null && !a.isBlank() ? a : b; }

    /**
     * İzlemenin grup adı (boş/boşluk → null) — dokuz türün hepsinde {@code group_name} sütunu var. {@link TypeSpec}'e yeni
     * bileşen eklemek yerine tür deseni: tür tanımları (ve onları kuran testler) değişmez.
     */
    static String groupNameOf(Object m) {
        String g = switch (m) {
            case HttpMonitor x -> x.getGroupName();
            case PingMonitor x -> x.getGroupName();
            case PortMonitor x -> x.getGroupName();
            case DnsMonitor x -> x.getGroupName();
            case DomainMonitor x -> x.getGroupName();
            case KeywordMonitor x -> x.getGroupName();
            case PageMonitor x -> x.getGroupName();
            case PageSpeedMonitor x -> x.getGroupName();
            case ScriptedMonitor x -> x.getGroupName();
            case null, default -> null;
        };
        return g == null || g.isBlank() ? null : g.trim();
    }
}
