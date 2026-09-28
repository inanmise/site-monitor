package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import com.sitemonitor.service.HttpMetricsAggregate.Agg;
import com.sitemonitor.service.HttpMetricsAggregate.Row;
import com.sitemonitor.util.Msg;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Kalıcı HTTP metrik satırlarını ({@link HttpMetricMinute}) zaman-serisi + özet olarak sorgular.
 * Dakika satırları Europe/Istanbul yerel dk/saat kovalarına gruplanır; p50/p95/p99 her kovadaki
 * gecikme histogramları birleştirilerek (Prometheus histogram_quantile yaklaşımı) hesaplanır.
 *
 * <p>2026-09-28 (İstek Gezgini yeniden tasarımı): satırlar artık AKIŞ olarak, tek geçişte toplanır
 * ({@link HttpMetricsAggregate}); {@link #overview} aynı geçişte uç tablosunu (uç başına p95/p99, durum sınıfları,
 * son görülme), süzülmüş zaman serisini ve durum kodu dağılımını üretir. Maliyet: aralıktaki satır sayısı kadar
 * CPU (histogram ayrıştırma), bellek SABİT (kova + uç başına birer toplayıcı). Aralık en çok
 * {@value #MAX_RANGE_DAYS} güne kırpılır. Sistem Sağlığı bölümünün "en yavaş / en çok hata" listeleri
 * ({@link #topEndpoints}) son 24 saatten, dakikada en çok bir kez hesaplanır (veri zaten dakikada bir yazılır).
 */
@Service
public class HttpMetricsQueryService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Tek sorguda taranan en uzun aralık (gün) — üstü kırpılır ve yanıt {@code clamped=true} taşır. */
    static final int MAX_RANGE_DAYS = 31;
    /** Uç tablosunda dönen en çok satır (yoğunluğa göre); aşılırsa {@code endpoints_truncated=true}. */
    static final int MAX_ENDPOINTS_OUT = 1000;
    /** Durum kodu dağılımında dönen en çok kod. */
    static final int MAX_STATUS_CODES_OUT = 40;
    /** Bölüm listeleri: ilk N uç; "en yavaş" için en az şu kadar istek (tek bir yavaş isteğin listeyi ele geçirmemesi). */
    static final int TOP_N = 3;
    static final long TOP_MIN_SAMPLES = 5;
    static final long TOP_TTL_MS = 60_000;
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final HttpMetricMinuteRepository repo;
    private final TransactionTemplate readTx;
    private volatile Clock clock = Clock.systemUTC();

    /** Hatalı hesabın önbellekte kalma süresi — bu sürede tarama yeniden denenmez (bkz. {@link #topEndpoints}). */
    static final long TOP_FAIL_TTL_MS = 15_000;
    /** Hiç sonuç yokken (ilk hesap) bir isteğin kilitte bekleyebileceği en uzun süre; aşılırsa null döner. */
    static final long TOP_WAIT_MS = 2_000;

    private final java.util.concurrent.locks.ReentrantLock topLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile TopCache topCache;

    /** {@code failed}: hesap istisnayla düştü — {@code value} son iyi sonuçtur (yoksa null). */
    private record TopCache(long at, Map<String, Object> value, boolean failed) { }

    public HttpMetricsQueryService(HttpMetricMinuteRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setReadOnly(true);   // akış sorgusu bir okuma transaction'ı ister (Postgres imleci fetchSize ile)
        this.readTx = tt;
    }

    /** Test kancası: sabit saat (kayan pencere hesabı "şimdi"ye bağlı). Önbelleği de boşaltır. */
    void setClock(Clock clock) {
        this.clock = clock;
        this.topCache = null;
    }

    /** Endpoint listesi (seçici + özet): aralıkta endpoint başına toplamlar (GROUP BY — satır taşınmaz). */
    public List<Map<String, Object>> endpoints(String from, String to) {
        // Güvenli ada çevirme iki ham adı aynı ada katlayabilir (eski satırlar) → birleştir.
        Map<String, long[]> merged = new LinkedHashMap<>();
        Map<String, String> lastSeen = new HashMap<>();
        for (Object[] r : repo.aggregateByEndpoint(from, to)) {
            String name = HttpMetricsAggregate.safeEndpoint(r[0] == null ? null : r[0].toString());
            long[] a = merged.computeIfAbsent(name, k -> new long[4]);
            a[0] += num(r[1]);
            a[1] += num(r[2]);
            a[2] += num(r[3]);
            a[3] = Math.max(a[3], num(r[4]));
            String seen = r.length > 5 && r[5] != null ? r[5].toString() : null;
            if (seen != null && (lastSeen.get(name) == null || seen.compareTo(lastSeen.get(name)) > 0)) lastSeen.put(name, seen);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : merged.entrySet()) {
            long count = e.getValue()[0];
            long errors = e.getValue()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", e.getKey());
            m.put("method", HttpMetricsAggregate.methodOf(e.getKey()));
            m.put("path", HttpMetricsAggregate.pathOf(e.getKey()));
            m.put("count", count);
            m.put("errors", errors);
            m.put("error_rate_pct", count > 0 ? Math.round(errors * 1000.0 / count) / 10.0 : 0.0);
            m.put("avg_ms", count > 0 ? e.getValue()[2] / count : 0L);
            m.put("max_ms", e.getValue()[3]);
            m.put("last_seen", lastSeen.get(e.getKey()));
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return out;
    }

    /**
     * Zaman-serisi + aralık özeti. endpoint null/boş → tüm endpoint'ler birlikte ("Tümü"). Yanıt şekli korunur;
     * 2026-09-28 ekleri (geriye uyumlu): nokta başına {@code t} (epoch ms) + durum sınıfı sayıları, özete durum
     * sınıfları ve {@code status_codes}. Satırlar akışla okunur (liste/entity yüklenmez).
     */
    public Map<String, Object> series(String from, String to, String endpoint, String granularity) {
        // 2026-09-28c (ek-2): overview ile AYNI aralık kuralı — doğrulanır (from > to → 400) ve 31 güne kırpılır.
        // Eskiden bu uç kırpmasızdı: uç ayrıntısı 90 günlük aralık isteyince tarama 90 günü geziyor, 208+ günde kova
        // tavanı en YENİ veriyi kesiyordu. Kırpılmış from/to + clamped yanıtta döner (arayüz aralığı oradan yazar).
        Range rg = Range.of(from, to);
        String gran = resolveGranularity(granularity, rg.from, rg.to);
        boolean one = endpoint != null && !endpoint.isBlank();
        Scan scan = new Scan(gran, false, name -> true);
        stream(one ? () -> repo.streamRangeForEndpoint(endpoint, rg.from, rg.to) : () -> repo.streamRange(rg.from, rg.to), scan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", rg.from);
        out.put("to", rg.to);
        out.put("clamped", rg.clamped);
        out.put("granularity", gran);
        out.put("granularity_clamped", minuteCoerced(granularity, gran));
        List<String> axis = bucketKeysInRange(rg.from, rg.to, gran);
        out.put("data", points(scan, axis));
        out.put("summary", summary(scan.overall, null));
        out.put("status_codes", scan.overall.codeList(MAX_STATUS_CODES_OUT));
        // Eksen tavanda durduysa özet ile grafik AYNI aralığı kapsamıyor demektir; çağıran bilsin.
        out.put("capped", axis.size() >= MAX_BUCKETS);
        return out;
    }

    /**
     * İstek Gezgini'nin TEK çağrısı: aralıktaki tüm uçların tablosu (süzgeçten BAĞIMSIZ — kullanıcı başka uca
     * geçebilsin) + {@code endpoint}/{@code method} süzgecine uyan satırların zaman serisi, özeti ve durum kodu
     * dağılımı. Tek akış geçişi.
     *
     * @param method virgüllü yöntem listesi (GET,POST…); bilinmeyen değer yok sayılır
     * @throws IllegalArgumentException geçersiz tarih ya da from &gt; to (400)
     */
    public Map<String, Object> overview(String from, String to, String endpoint, String method, String granularity) {
        Range rg = Range.of(from, to);
        String gran = resolveGranularity(granularity, rg.from, rg.to);
        String ep = endpoint == null || endpoint.isBlank() ? null : HttpMetricsAggregate.safeEndpoint(endpoint);
        Set<String> methods = parseMethods(method);
        Predicate<String> match = name -> (ep == null || ep.equals(name))
                && (methods.isEmpty() || methods.contains(HttpMetricsAggregate.methodOf(name)));
        Scan scan = new Scan(gran, true, match);
        stream(() -> repo.streamRange(rg.from, rg.to), scan);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", rg.from);
        out.put("to", rg.to);
        out.put("clamped", rg.clamped);
        out.put("granularity", gran);
        out.put("granularity_clamped", minuteCoerced(granularity, gran));
        List<String> axis = bucketKeysInRange(rg.from, rg.to, gran);
        out.put("capped", axis.size() >= MAX_BUCKETS);
        out.put("data", points(scan, axis));
        out.put("summary", summary(scan.overall, rg.minutes()));
        out.put("status_codes", scan.overall.codeList(MAX_STATUS_CODES_OUT));
        List<Map<String, Object>> eps = scan.endpointRows();
        out.put("endpoints_total", eps.size());
        out.put("endpoints_truncated", eps.size() > MAX_ENDPOINTS_OUT);
        out.put("endpoints", eps.size() > MAX_ENDPOINTS_OUT ? new ArrayList<>(eps.subList(0, MAX_ENDPOINTS_OUT)) : eps);
        return out;
    }

    /**
     * Sistem Sağlığı → HTTP istekleri bölümünün listeleri: son 24 saatte en yavaş (p95, en az
     * {@value #TOP_MIN_SAMPLES} istek) ve en çok hata veren ilk {@value #TOP_N} uç. Sayfa 30 sn'de bir yoklanır ve
     * 100 eşzamanlı kullanıcı aynı anda bakabilir → sonuç {@value #TOP_TTL_MS} ms önbellekte; hesap kilitte TEK iş
     * parçacığında (bekleyenler hazır sonucu alır, veritabanına yığılmazlar). Kilit transaction'dan ÖNCE alınır.
     */
    public Map<String, Object> topEndpoints() {
        long now = clock.millis();
        TopCache c = topCache;
        if (fresh(c, now)) return c.value();
        // 2026-09-28c (ek-3): bekleyenler kilitte BİRİKMEZ. Eskiden `synchronized` blok taramanın tamamı boyunca
        // tutuluyor ve istisna önbelleğe yazılmıyordu: yavaş/düşen DB'de her bekleyen istek taramayı SIRAYLA yeniden
        // deniyor, 100 kullanıcının 30 sn yoklaması Tomcat iş parçacıklarını kilitte biriktiriyordu (tek pod = kesinti).
        // Şimdi: bayat sonuç varsa beklenmez (hemen o döner, hesaplayan tazeler); ilk hesapta en çok TOP_WAIT_MS
        // beklenir; hata TOP_FAIL_TTL_MS boyunca önbellekte kalır (sonraki çağrılar taramayı yeniden koşmaz).
        boolean locked;
        if (c != null && c.value() != null) {
            locked = topLock.tryLock();
        } else {
            try {
                locked = topLock.tryLock(TOP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                locked = false;
            }
        }
        if (!locked) return c != null ? c.value() : null;
        try {
            c = topCache;
            if (fresh(c, now)) return c.value();
            try {
                Map<String, Object> v = computeTop(now);
                topCache = new TopCache(now, v, false);
                return v;
            } catch (RuntimeException e) {
                // Varsa son iyi sonuç korunur (generated_at bayatlığı gösterir); yoksa null → bölüm listeleri gizler.
                topCache = new TopCache(now, c != null ? c.value() : null, true);
                throw e;
            }
        } finally {
            topLock.unlock();
        }
    }

    private static boolean fresh(TopCache c, long now) {
        return c != null && now - c.at() < (c.failed() ? TOP_FAIL_TTL_MS : TOP_TTL_MS);
    }

    private Map<String, Object> computeTop(long nowMs) {
        Instant to = Instant.ofEpochMilli(nowMs).truncatedTo(ChronoUnit.MINUTES);
        String toStr = ISO.withZone(ZoneOffset.UTC).format(to);
        String fromStr = ISO.withZone(ZoneOffset.UTC).format(to.minus(24, ChronoUnit.HOURS));
        Scan scan = new Scan(null, true, name -> true);
        stream(() -> repo.streamRange(fromStr, toStr), scan);
        List<Map<String, Object>> eps = scan.endpointRows();

        List<Map<String, Object>> sampled = eps.stream().filter(e -> (long) e.get("count") >= TOP_MIN_SAMPLES).toList();
        List<Map<String, Object>> slowPool = sampled.isEmpty() ? eps : sampled;
        List<Map<String, Object>> slowest = slowPool.stream()
                .sorted(Comparator.<Map<String, Object>>comparingLong(e -> (long) e.get("p95_ms")).reversed()
                        .thenComparing(Comparator.<Map<String, Object>>comparingLong(e -> (long) e.get("avg_ms")).reversed()))
                .limit(TOP_N).toList();
        List<Map<String, Object>> errors = eps.stream()
                .filter(e -> (long) e.get("errors") > 0)
                .sorted(Comparator.<Map<String, Object>>comparingLong(e -> (long) e.get("errors")).reversed()
                        .thenComparing(Comparator.<Map<String, Object>>comparingDouble(e -> (double) e.get("error_rate_pct")).reversed()))
                .limit(TOP_N).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_hours", 24);
        out.put("generated_at", toStr);
        out.put("endpoint_count", eps.size());
        out.put("slowest", slowest);
        out.put("errors", errors);
        return out;
    }

    // ── Akış + toplayıcı ────────────────────────────────────────────────────────────────────────

    private void stream(java.util.function.Supplier<Stream<Object[]>> source, Scan scan) {
        readTx.executeWithoutResult(st -> {
            try (Stream<Object[]> rows = source.get()) {
                rows.forEach(scan::accept);
            }
        });
    }

    /**
     * Tek geçiş: kova serisi (süzgece uyanlar), genel özet (süzgece uyanlar), uç tablosu (HEPSİ). Kovalar
     * DOĞRUDAN hedef granülaritenin IST anahtarıyla tutulur (31 gün saatlik = en çok 744 toplayıcı; dakika
     * anahtarıyla tutmak 44 bin toplayıcı ≈ 11 MB olurdu). Aynı dakikanın satırları art arda geldiği için
     * UTC→IST dönüşümü son anahtarla ezberlenir.
     */
    private static final class Scan {
        final String gran;
        final boolean perEndpoint;
        final Predicate<String> match;
        final TreeMap<String, Agg> buckets = new TreeMap<>();
        final Agg overall = new Agg(true);
        final Map<String, Agg> endpoints = new HashMap<>();
        private String lastUtc;
        private String lastKey;

        /** @param gran null → kova serisi tutulmaz (yalnız özet / uç tablosu) */
        Scan(String gran, boolean perEndpoint, Predicate<String> match) {
            this.gran = gran;
            this.perEndpoint = perEndpoint;
            this.match = match;
        }

        void accept(Object[] raw) {
            Row r = Row.of(raw);
            if (perEndpoint) endpoints.computeIfAbsent(r.endpoint(), k -> new Agg(false)).add(r);
            if (!match.test(r.endpoint())) return;
            overall.add(r);
            if (gran != null && r.bucket() != null) buckets.computeIfAbsent(keyOf(r.bucket()), k -> new Agg(false)).add(r);
        }

        private String keyOf(String utcMinute) {
            if (!utcMinute.equals(lastUtc)) {
                lastUtc = utcMinute;
                lastKey = bucketKey(utcMinute, gran);
            }
            return lastKey;
        }

        List<Map<String, Object>> endpointRows() {
            List<Map<String, Object>> out = new ArrayList<>(endpoints.size());
            endpoints.forEach((name, a) -> out.add(a.toEndpoint(name)));
            out.sort((a, b) -> {
                int c = Long.compare((long) b.get("count"), (long) a.get("count"));
                return c != 0 ? c : String.valueOf(a.get("endpoint")).compareTo(String.valueOf(b.get("endpoint")));
            });
            return out;
        }
    }

    /**
     * Dakika satırlarını seçili granülariteye (IST yerel dk/saat) toplayıp eksenin TAMAMINI doldurur: istek
     * GELMEYEN kovalar count=0 + null süreler → grafik boşlukları çizgiyle BİRLEŞTİRMEZ.
     */
    private static List<Map<String, Object>> points(Scan scan, List<String> axis) {
        List<Map<String, Object>> data = new ArrayList<>(axis.size());
        for (String key : axis) {
            Agg a = scan.buckets.get(key);
            data.add(a != null ? toPoint(a, key) : emptyPoint(key));
        }
        return data;
    }

    private static Map<String, Object> toPoint(Agg a, String ts) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ts", ts);
        p.put("t", epochOf(ts));
        p.put("count", a.count);
        p.put("errors", a.errors);
        p.put("avg_ms", a.count > 0 ? a.sumMs / a.count : 0L);
        p.put("max_ms", a.maxMs);
        p.put("min_ms", a.count > 0 ? a.minMs : 0L);
        p.put("p50_ms", a.p(0.50));
        p.put("p95_ms", a.p(0.95));
        p.put("p99_ms", a.p(0.99));
        a.putClasses(p);
        return p;
    }

    /** İstek gelmeyen kova — count/errors 0, süreler null (grafik boşlukta çizgiyi birleştirmesin). */
    private static Map<String, Object> emptyPoint(String ts) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ts", ts);
        p.put("t", epochOf(ts));
        p.put("count", 0L);
        p.put("errors", 0L);
        p.put("avg_ms", null);
        p.put("max_ms", null);
        p.put("min_ms", null);
        p.put("p50_ms", null);
        p.put("p95_ms", null);
        p.put("p99_ms", null);
        new Agg(false).putClasses(p);
        return p;
    }

    private static Map<String, Object> summary(Agg o, Long minutes) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", o.count);
        s.put("errors", o.errors);
        s.put("error_rate_pct", o.errorRatePct());
        s.put("avg_ms", o.avg());
        s.put("max_ms", o.maxMs);
        s.put("min_ms", o.count > 0 ? o.minMs : 0L);
        s.put("p50_ms", o.p(0.50));
        s.put("p95_ms", o.p(0.95));
        s.put("p99_ms", o.p(0.99));
        o.putClasses(s);
        if (minutes != null) s.put("req_per_min", Math.round(o.count * 10.0 / Math.max(1, minutes)) / 10.0);
        return s;
    }

    // ── Aralık ──────────────────────────────────────────────────────────────────────────────────

    /** Doğrulanmış, {@value #MAX_RANGE_DAYS} güne kırpılmış UTC aralık ("yyyy-MM-ddTHH:mm:ss"). */
    record Range(String from, String to, boolean clamped, long minutesSpan) {

        long minutes() { return minutesSpan; }

        static Range of(String from, String to) {
            LocalDateTime f = parse(from);
            LocalDateTime t = parse(to);
            if (f.isAfter(t)) {
                throw new IllegalArgumentException(Msg.t("Başlangıç bitişten sonra olamaz", "The start must not be after the end"));
            }
            boolean clamped = false;
            if (t.minusDays(MAX_RANGE_DAYS).isAfter(f)) {
                f = t.minusDays(MAX_RANGE_DAYS);
                clamped = true;
            }
            return new Range(ISO.format(f), ISO.format(t), clamped, Math.max(1, Duration.between(f, t).toMinutes()));
        }

        private static LocalDateTime parse(String s) {
            try {
                return LocalDateTime.parse(s.strip().substring(0, Math.min(19, s.strip().length())));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(Msg.t("Geçersiz tarih: ", "Invalid date: ") + safe(s));
            }
        }

        private static String safe(String s) {
            if (s == null) return "null";
            String cut = s.length() > 32 ? s.substring(0, 32) : s;
            return cut.replaceAll("[^0-9A-Za-z:.+-]", "?");
        }
    }

    static Set<String> parseMethods(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String m : csv.split(",")) {
            String u = m.strip().toUpperCase(java.util.Locale.ROOT);
            if (METHODS.contains(u)) out.add(u);
        }
        return out;
    }

    // ── Granularite + IST kova anahtarı ─────────────────────────────────────────

    /**
     * Açıkça istenen dakika kovasının azami aralığı (saat). Üstünde "minute" SESSİZCE DEĞİL yanıtta
     * {@code granularity=hour} + {@code granularity_clamped=true} ile saate çevrilir (2026-09-28c, B4): toplayıcılar
     * hedef granülaritenin anahtarıyla tutulduğu için 31 gün × 1440 = 44.640 dakika toplayıcısı (≈ 11 MB) belleğe
     * alınıyordu — arayüz bu parametreyi hiç göndermese de {@code system_health.read} taşıyan her oturum doğrudan
     * çağırabiliyordu (tek pod, yığın tavanı). 48 sa = 2.880 kova (eksen tavanı 5.000'in altında).
     */
    static final int MAX_MINUTE_HOURS = 48;

    static String resolveGranularity(String gran, String from, String to) {
        if ("hour".equalsIgnoreCase(gran)) return "hour";
        Long minutes = spanMinutes(from, to);
        if ("minute".equalsIgnoreCase(gran)) {
            return minutes != null && minutes > MAX_MINUTE_HOURS * 60L ? "hour" : "minute";
        }
        // otomatik: aralık > 24 saat → saat, değilse dakika (Grafana benzeri ince çözünürlük; 24s = dakika).
        return minutes != null && minutes / 60 > 24 ? "hour" : "minute";
    }

    /** İstenen "minute" tavan yüzünden "hour"a çevrildi mi (yanıttaki {@code granularity_clamped}). */
    static boolean minuteCoerced(String requested, String resolved) {
        return "minute".equalsIgnoreCase(requested) && "hour".equals(resolved);
    }

    /** [from,to] aralığının dakika cinsinden uzunluğu; ayrıştırılamazsa null. */
    private static Long spanMinutes(String from, String to) {
        try {
            LocalDateTime f = LocalDateTime.parse(from.substring(0, Math.min(19, from.length())));
            LocalDateTime t = LocalDateTime.parse(to.substring(0, Math.min(19, to.length())));
            return java.time.Duration.between(f, t).toMinutes();
        } catch (Exception e) {
            return null;
        }
    }

    /** UTC dakika string'ini Europe/Istanbul yerel dk/saat kova anahtarına çevirir (sıralanabilir). */
    static String bucketKey(String utcMinute, String gran) {
        try {
            LocalDateTime utc = LocalDateTime.parse(utcMinute.substring(0, Math.min(19, utcMinute.length())));
            ZonedDateTime ist = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST);
            String d = ist.toLocalDate().toString();
            if ("hour".equals(gran)) return d + "T" + String.format("%02d:00:00", ist.getHour());
            return d + "T" + String.format("%02d:%02d:00", ist.getHour(), ist.getMinute());
        } catch (Exception e) {
            return utcMinute;
        }
    }

    /** IST yerel kova anahtarı → epoch ms (tarayıcı hangi dilimde olursa olsun aynı an). Bozuk → null. */
    static Long epochOf(String istKey) {
        try {
            return LocalDateTime.parse(istKey.substring(0, Math.min(19, istKey.length())))
                    .atZone(IST).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** Zaman ekseni tavanı — aşılırsa yanıt "capped" bayrağı taşır (sessiz kırpma YOK). */
    static final int MAX_BUCKETS = 5000;

    /** [from,to] (UTC ISO) aralığındaki TÜM kova anahtarları (IST yerel, granülarite adımıyla) — boş kovalar dahil. */
    static List<String> bucketKeysInRange(String fromUtc, String toUtc, String gran) {
        List<String> keys = new ArrayList<>();
        try {
            boolean hour = "hour".equals(gran);
            ChronoUnit step = hour ? ChronoUnit.HOURS : ChronoUnit.MINUTES;
            ZonedDateTime f = LocalDateTime.parse(fromUtc.substring(0, Math.min(19, fromUtc.length())))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST).truncatedTo(step);
            ZonedDateTime t = LocalDateTime.parse(toUtc.substring(0, Math.min(19, toUtc.length())))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST);
            // Tavan aşılırsa çağıran BİLİR (capped bayrağı): eskiden liste sessizce 5000'de
            // duruyordu. granularity=minute + 7 gün = 10.080 kova → grafik ~3,5 gün çiziyor, aynı
            // yanıtın summary.total/p95 alanları 7 günün TAMAMINI kapsıyordu; kullanıcı "özet 400k
            // diyor, grafiğin altındaki alan bunun yarısı" çelişkisini kırpma bayrağı olmadan
            // görüyordu. Desen MonitoringController.buildResponseSeries'te zaten var.
            int guard = 0;
            for (ZonedDateTime cur = f; !cur.isAfter(t) && guard < MAX_BUCKETS; guard++, cur = cur.plus(1, step)) {
                String d = cur.toLocalDate().toString();
                keys.add(hour ? d + "T" + String.format("%02d:00:00", cur.getHour())
                              : d + "T" + String.format("%02d:%02d:00", cur.getHour(), cur.getMinute()));
            }
        } catch (Exception ignore) { }
        return keys;
    }

    // ── Histogram yardımcıları (test edilebilir) ────────────────────────────────

    static long[] parseHist(String csv) {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        if (csv == null || csv.isBlank()) return h;
        String[] parts = csv.split(",");
        for (int i = 0; i < h.length && i < parts.length; i++) {
            try { h[i] = Long.parseLong(parts[i].trim()); } catch (Exception ignore) { }
        }
        return h;
    }

    /** Birleşik histogramdan kantil (ms) — kümülatif sayım + kova içi lineer interpolasyon. */
    static long percentile(long[] hist, double q, long maxMs) {
        long total = 0;
        for (long h : hist) total += h;
        if (total == 0) return 0;
        double rank = q * total;
        long cum = 0;
        long[] bounds = HttpMetricsService.HISTOGRAM_BOUNDS;
        for (int i = 0; i < hist.length; i++) {
            long c = hist[i];
            if (c == 0) continue;
            if (cum + c >= rank) {
                long lower = i == 0 ? 0 : bounds[i - 1];
                long upper = i < bounds.length ? bounds[i] : Math.max(maxMs, bounds[bounds.length - 1]);
                double frac = c > 0 ? (rank - cum) / c : 0;            // 0..1 kova içinde
                return Math.round(lower + (upper - lower) * Math.max(0, Math.min(1, frac)));
            }
            cum += c;
        }
        return maxMs;
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
