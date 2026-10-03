package com.sitemonitor.service.loginstats;

import com.sitemonitor.controller.IdentityMask;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.UserAgentSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Giriş İSTATİSTİKLERİ (2026-10-03, kullanıcı isteği: "hangi kullanıcı hangi login kanalından kaç kez login isteği yaptı,
 * kaçı başarılı, kaçı başarısız, başarısız olma nedenleri … hem kullanıcı bazlı detayda hem de genel olarak") — Ayarlar →
 * Güvenlik → Giriş Yöntemleri → "İstatistikler" sekmesi. YALNIZ global yönetici (LoginMethodsController).
 *
 * <p><b>Kaynak</b> denetim kaydı ({@code audit_log}); sınıflandırma {@link LoginEventClassifier} (kanal, neden, tahmin,
 * bilinmeyen kullanıcı). Kanallar: LDAP, yerel şifre, push kodu, e-posta kodu, beni hatırla.
 *
 * <p><b>Performans</b> (yoklanan ekran): dönem başına bir izdüşüm sorgusu ({@code event_type IN … AND event_time >= ?}, en
 * çok {@value #ROW_CAP} satır — fazlası {@code truncated}), önceki dönem için tek gruplu sayım, dizin için tek izdüşüm
 * ({@code app_users}) + takım adları. Kullanıcı / gün başına sorgu YOK; kovalama Java'da (Europe/Istanbul; 24 saatte
 * saatlik, diğerlerinde günlük — DbAnalyticsService deseni). Özet dönem başına {@value #CACHE_MS} ms bellekte (pod başına);
 * {@code fresh} isteği önbelleği en sık {@value #FRESH_MIN_MS} ms'de bir atlar. Kullanıcı tablosu aynı anlık görüntüden
 * süzülür / sıralanır / sayfalanır; tek kullanıcı ayrıntısı kendi tek sorgusuyla (≤ {@value #USER_ROW_CAP} satır).
 *
 * <p><b>Kimlik izi:</b> kullanıcı ayrıntısındaki son olaylar IP / konum / cihaz taşır ve {@link IdentityMask}'ten geçer
 * (uç bugün yalnız global yöneticiye açık; yetki gevşerse iz kendiliğinden düşer). Özet ve tablo IP taşımaz.
 */
@Service
@Slf4j
public class LoginStatsService {

    public static final List<Integer> PERIODS = List.of(1, 7, 30, 90);
    public static final int DEFAULT_DAYS = 7;
    static final int ROW_CAP = 250_000;
    static final int USER_ROW_CAP = 20_000;
    static final int RECENT_MAX = 100;
    static final long CACHE_MS = 30_000L;
    static final long FRESH_MIN_MS = 5_000L;
    public static final int PAGE_SIZE_DEFAULT = 25;
    public static final int PAGE_SIZE_MAX = 100;
    /** CSV dışa aktarımında tek yanıttaki satır tavanı. */
    public static final int EXPORT_MAX = 20_000;
    public static final List<String> SORTS = List.of("logins", "failures", "last", "name");

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final LoginChannel[] CHANNELS = LoginChannel.values();
    /** Seri sütunları: kanal başına başarılı (CHANNELS sırası) + kanalsız başarılı + başarısız. */
    private static final int COL_OTHER = CHANNELS.length;
    private static final int COL_FAILED = CHANNELS.length + 1;

    private final AuditLogRepository auditRepo;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private volatile Clock clock = Clock.systemUTC();
    /** Dönem izdüşümü tavanı (test kancası; üretimde {@value #ROW_CAP}). */
    volatile int rowCap = ROW_CAP;

    private final Map<Integer, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<Integer, Object> locks = new ConcurrentHashMap<>();

    public LoginStatsService(AuditLogRepository auditRepo, AppUserRepository userRepo, TeamRepository teamRepo) {
        this.auditRepo = auditRepo;
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
    }

    /** Test kancası. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Desteklenen dönem (1 / 7 / 30 / 90 gün); başka değer → 7. */
    public static int normalizeDays(Integer days) {
        return days != null && PERIODS.contains(days) ? days : DEFAULT_DAYS;
    }

    // ── Pencere ──────────────────────────────────────────────────────────────────────────────

    /**
     * Dönem penceresi: 1 gün → son 24 SAATLİK kova (şimdiki saat dahil), diğerleri → son N TAKVİM günü (bugün dahil,
     * İstanbul gece yarısından). Önceki dönem AYNI uzunlukta, hemen öncesi (deltaların adil karşılaştırması).
     */
    record Window(int days, boolean hourly, long fromEpoch, long toEpoch, long prevFromEpoch, long bucketSeconds, int buckets) {
        int bucketOf(long epoch) {
            if (epoch < fromEpoch) return -1;
            long i = (epoch - fromEpoch) / bucketSeconds;
            return (int) Math.min(i, buckets - 1);
        }
        long bucketStart(int i) {
            return fromEpoch + i * bucketSeconds;
        }
    }

    static Window window(int days, Instant now) {
        ZonedDateTime z = now.atZone(ZONE);
        ZonedDateTime from;
        boolean hourly = days == 1;
        if (hourly) {
            from = z.truncatedTo(ChronoUnit.HOURS).minusHours(23);
        } else {
            from = z.toLocalDate().atStartOfDay(ZONE).minusDays(days - 1L);
        }
        long fromEpoch = from.toEpochSecond();
        long toEpoch = now.getEpochSecond();
        long prevFrom = fromEpoch - Math.max(1, toEpoch - fromEpoch);
        return new Window(days, hourly, fromEpoch, toEpoch, prevFrom, hourly ? 3600 : 86400, hourly ? 24 : days);
    }

    private static String iso(long epoch) {
        return ISO.format(Instant.ofEpochSecond(epoch));
    }

    /** Saklanan UTC damgası ("yyyy-MM-ddTHH:mm:ss…") → epoch saniye; istisnasız hızlı yol (250 bin satır). */
    static long epochOf(String s) {
        if (s == null || s.length() < 19) return Long.MIN_VALUE;
        try {
            return LocalDateTime.of(Integer.parseInt(s, 0, 4, 10), Integer.parseInt(s, 5, 7, 10),
                    Integer.parseInt(s, 8, 10, 10), Integer.parseInt(s, 11, 13, 10), Integer.parseInt(s, 14, 16, 10),
                    Integer.parseInt(s, 17, 19, 10)).toEpochSecond(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String lc(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ── Dizin ────────────────────────────────────────────────────────────────────────────────

    /** Kullanıcı dizini satırı (hesap kaynağı + tablo sütunları). */
    record UserInfo(String username, String displayName, Long teamId, String teamName, String source, boolean active) { }

    private Map<Long, String> teamNames() {
        Map<Long, String> out = new HashMap<>();
        try {
            for (Team t : teamRepo.findAll()) if (t.getId() != null) out.put(t.getId(), t.getName());
        } catch (Exception e) {
            log.debug("Giriş istatistikleri: takım adları okunamadı — {}", e.toString());
        }
        return out;
    }

    private Map<String, UserInfo> directory() {
        Map<Long, String> teams = teamNames();
        Map<String, UserInfo> out = new HashMap<>();
        for (Object[] r : userRepo.findLoginStatDirectory()) {
            String username = r[0] == null ? null : r[0].toString();
            if (username == null || username.isBlank()) continue;
            Long teamId = r[2] instanceof Number n ? Long.valueOf(n.longValue()) : null;
            out.put(lc(username), new UserInfo(username, r[1] == null ? null : r[1].toString(), teamId,
                    teamId == null ? null : teams.get(teamId), sourceOf(r[3] == null ? null : r[3].toString()),
                    !Boolean.FALSE.equals(r[4])));
        }
        return out;
    }

    /** Hesap kaynağı (boş = yerel) → "LOCAL" / "LDAP". */
    private static String sourceOf(String authSource) {
        return LoginChannel.ofAccountSource(authSource) == LoginChannel.LOCAL ? "LOCAL" : "LDAP";
    }

    // ── Toplama ──────────────────────────────────────────────────────────────────────────────

    static final class ChannelAgg {
        long success;
        long failed;
        long estimated;
        final Set<String> users = new HashSet<>();
        long requested;
        long sent;
        long suppressed;
        long rejected;
        long deliveryFailed;
        long wrongCode;
        long expired;
        long locked;
        final Map<String, Long> suppressedReasons = new TreeMap<>();
    }

    static final class UserAgg {
        final UserInfo info;
        final long[] success = new long[CHANNELS.length];
        final long[] failedBy = new long[CHANNELS.length];
        long failed;
        long estimated;
        String lastSuccessAt;
        LoginChannel lastSuccessChannel;
        String lastFailureAt;
        String lastFailureReason;

        UserAgg(UserInfo info) {
            this.info = info;
        }

        long successTotal() {
            long s = 0;
            for (long v : success) s += v;
            return s;
        }

        boolean touches(LoginChannel ch) {
            return success[ch.ordinal()] > 0 || failedBy[ch.ordinal()] > 0;
        }
    }

    /** Bir pencerenin toplayıcısı — satır başına O(1). */
    static final class Agg {
        final Window w;
        long rows;
        long success;
        long failed;
        long unknownFailures;
        long unknownSuccess;
        long deliveryFailures;
        long estimated;
        final Set<String> uniqueUsers = new HashSet<>();
        final ChannelAgg[] channels = new ChannelAgg[CHANNELS.length];
        final long[][] series;
        /** neden → [toplam, kanal başına (CHANNELS sırası)…, kanalsız]. */
        final Map<String, long[]> reasons = new HashMap<>();
        final Map<String, UserAgg> users = new HashMap<>();

        Agg(Window w) {
            this.w = w;
            for (int i = 0; i < channels.length; i++) channels[i] = new ChannelAgg();
            this.series = new long[w.buckets()][CHANNELS.length + 2];
        }

        LoginEventClassifier.Result add(String type, String time, String actor, String failureReason, String detail,
                                        Function<String, String> sourceOf, Map<String, UserInfo> dir) {
            rows++;
            LoginEventClassifier.Result r = LoginEventClassifier.classify(type, actor, failureReason, detail, sourceOf);
            int b = w.bucketOf(epochOf(time));
            String key = lc(actor);
            switch (r.kind()) {
                case SUCCESS -> {
                    success++;
                    if (!key.isEmpty()) uniqueUsers.add(key);
                    if (r.estimated()) estimated++;
                    if (r.channel() == null) {
                        unknownSuccess++;
                        if (b >= 0) series[b][COL_OTHER]++;
                    } else {
                        ChannelAgg c = channels[r.channel().ordinal()];
                        c.success++;
                        if (r.estimated()) c.estimated++;
                        if (!key.isEmpty()) c.users.add(key);
                        if (b >= 0) series[b][r.channel().ordinal()]++;
                    }
                    UserAgg u = user(key, dir);
                    if (u != null && r.channel() != null) {
                        u.success[r.channel().ordinal()]++;
                        if (r.estimated()) u.estimated++;
                        if (u.lastSuccessAt == null || (time != null && time.compareTo(u.lastSuccessAt) > 0)) {
                            u.lastSuccessAt = time;
                            u.lastSuccessChannel = r.channel();
                        }
                    }
                }
                case FAILURE -> {
                    failed++;
                    if (b >= 0) series[b][COL_FAILED]++;
                    long[] reason = reasons.computeIfAbsent(r.reason(), k -> new long[CHANNELS.length + 2]);
                    reason[0]++;
                    if (r.unknownUser()) {
                        unknownFailures++;
                        reason[CHANNELS.length + 1]++;
                    } else {
                        if (r.estimated()) estimated++;
                        ChannelAgg c = channels[r.channel().ordinal()];
                        c.failed++;
                        if (r.estimated()) c.estimated++;
                        reason[1 + r.channel().ordinal()]++;
                        UserAgg u = user(key, dir);
                        if (u != null) {
                            u.failed++;
                            u.failedBy[r.channel().ordinal()]++;
                            if (r.estimated()) u.estimated++;
                            if (u.lastFailureAt == null || (time != null && time.compareTo(u.lastFailureAt) > 0)) {
                                u.lastFailureAt = time;
                                u.lastFailureReason = r.reason();
                            }
                        }
                    }
                    if (r.otpChannel() != null) {
                        ChannelAgg c = channels[r.otpChannel().ordinal()];
                        switch (type) {
                            case LoginEventClassifier.OTP_VERIFY_FAILED -> c.wrongCode++;
                            case LoginEventClassifier.OTP_EXPIRED -> c.expired++;
                            case LoginEventClassifier.OTP_LOCKED -> c.locked++;
                            default -> { }
                        }
                    }
                }
                case OTP_REQUEST -> {
                    if (r.otpChannel() != null) {
                        ChannelAgg c = channels[r.otpChannel().ordinal()];
                        c.requested++;
                        if ("SENT".equals(r.otpResult())) c.sent++;
                        else if ("SUPPRESSED".equals(r.otpResult())) {
                            c.suppressed++;
                            c.suppressedReasons.merge(r.otpReason() == null ? LoginEventClassifier.OTHER : r.otpReason(), 1L, Long::sum);
                        } else if ("REJECTED".equals(r.otpResult())) c.rejected++;
                    }
                }
                case OTP_DELIVERY_FAILED -> {
                    deliveryFailures++;
                    if (r.otpChannel() != null) channels[r.otpChannel().ordinal()].deliveryFailed++;
                }
                default -> { }
            }
            return r;
        }

        private UserAgg user(String key, Map<String, UserInfo> dir) {
            if (key.isEmpty() || dir == null) return null;
            UserInfo info = dir.get(key);
            return info == null ? null : users.computeIfAbsent(key, k -> new UserAgg(info));
        }
    }

    /** Dizine göre aktör → hesap kaynağı (hesap yoksa null). */
    private static Function<String, String> sourceLookup(Map<String, UserInfo> dir) {
        return actor -> {
            UserInfo u = dir.get(lc(actor));
            return u == null ? null : u.source();
        };
    }

    // ── Anlık görüntü (özet + kullanıcı satırları) ──────────────────────────────────────────

    record Snapshot(long computedAtMs, Map<String, Object> summary, List<UserAgg> users) { }

    private Snapshot snapshot(int days, boolean fresh) {
        long now = clock.millis();
        Snapshot s = cache.get(days);
        if (s != null) {
            long age = now - s.computedAtMs();
            if (age < (fresh ? FRESH_MIN_MS : CACHE_MS)) return s;
        }
        synchronized (locks.computeIfAbsent(days, k -> new Object())) {
            s = cache.get(days);
            now = clock.millis();
            if (s != null && now - s.computedAtMs() < (fresh ? FRESH_MIN_MS : CACHE_MS)) return s;   // bekleyen başka istek hesapladı
            Snapshot built = compute(days);
            cache.put(days, built);
            return built;
        }
    }

    private Snapshot compute(int days) {
        Instant now = clock.instant();
        Window w = window(days, now);
        Map<String, UserInfo> dir = directory();
        List<Object[]> rows = auditRepo.findLoginStatRows(LoginEventClassifier.EVENT_TYPES, iso(w.fromEpoch()),
                PageRequest.of(0, rowCap + 1));
        boolean truncated = rows.size() > rowCap;
        if (truncated) rows = rows.subList(0, rowCap);
        Agg agg = new Agg(w);
        Function<String, String> src = sourceLookup(dir);
        for (Object[] r : rows) {
            agg.add(str(r[0]), str(r[1]), str(r[2]), str(r[4]), str(r[5]), src, dir);
        }
        Totals prev = totals(w.prevFromEpoch(), w.fromEpoch());
        Totals cur = truncated ? totals(w.fromEpoch(), w.toEpoch() + 1)
                : new Totals(agg.success, agg.failed, agg.uniqueUsers.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("granularity", w.hourly() ? "hour" : "day");
        out.put("from", iso(w.fromEpoch()));
        out.put("to", iso(w.toEpoch()));
        out.put("generated_at", ISO.format(now));
        out.put("truncated", truncated);
        out.put("row_count", agg.rows);
        out.put("row_cap", rowCap);
        out.put("estimated", agg.estimated);
        Map<String, Object> totals = cur.view();
        totals.put("unknown_user_failures", agg.unknownFailures);
        totals.put("unattributed_success", agg.unknownSuccess);
        totals.put("delivery_failures", agg.deliveryFailures);
        out.put("totals", totals);
        out.put("previous", prev.view());
        out.put("channels", channelViews(agg));
        out.put("series", seriesView(agg));
        out.put("failure_reasons", reasonViews(agg));

        List<UserAgg> users = new ArrayList<>(agg.users.values());
        users.sort(byLogins());
        return new Snapshot(clock.millis(), out, users);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Kesin toplamlar (gruplu sayım): başarılı, başarısız, tekil başarılı kullanıcı. */
    record Totals(long success, long failed, long uniqueUsers) {
        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            long attempts = success + failed;
            m.put("attempts", attempts);
            m.put("success", success);
            m.put("failed", failed);
            m.put("success_rate", attempts == 0 ? null : round4((double) success / attempts));
            m.put("unique_users", uniqueUsers);
            return m;
        }
    }

    private Totals totals(long fromEpoch, long toEpoch) {
        long success = 0;
        long failed = 0;
        long unique = 0;
        try {
            for (Object[] r : auditRepo.countLoginStatTypes(LoginEventClassifier.EVENT_TYPES, iso(fromEpoch), iso(toEpoch))) {
                String type = str(r[0]);
                long n = r[1] instanceof Number x ? x.longValue() : 0L;
                if (LoginEventClassifier.LOGIN.equals(type)) {
                    success += n;
                    unique = r[2] instanceof Number x ? x.longValue() : 0L;
                } else if (LoginEventClassifier.FAILURE_TYPES.contains(type)) {
                    failed += n;
                }
            }
        } catch (Exception e) {
            log.warn("Giriş istatistikleri: dönem sayımı okunamadı — {}", e.toString());
        }
        return new Totals(success, failed, unique);
    }

    private static Double round4(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }

    private static Double rate(long ok, long bad) {
        long a = ok + bad;
        return a == 0 ? null : round4((double) ok / a);
    }

    private static List<Map<String, Object>> channelViews(Agg agg) {
        long allSuccess = 0;
        for (ChannelAgg c : agg.channels) allSuccess += c.success;
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoginChannel ch : CHANNELS) {
            ChannelAgg c = agg.channels[ch.ordinal()];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", ch.name());
            m.put("success", c.success);
            m.put("failed", c.failed);
            m.put("attempts", c.success + c.failed);
            m.put("success_rate", rate(c.success, c.failed));
            m.put("unique_users", c.users.size());
            m.put("share", allSuccess == 0 ? null : round4((double) c.success / allSuccess));
            m.put("estimated", c.estimated);
            if (ch.otp()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("requested", c.requested);
                f.put("sent", c.sent);
                f.put("verified", c.success);
                f.put("suppressed", c.suppressed);
                f.put("rate_limited", c.rejected);
                f.put("delivery_failed", c.deliveryFailed);
                f.put("wrong_code", c.wrongCode);
                f.put("expired", c.expired);
                f.put("locked", c.locked);
                f.put("conversion", c.sent == 0 ? null : round4(Math.min(1d, (double) c.success / c.sent)));
                List<Map<String, Object>> reasons = new ArrayList<>();
                c.suppressedReasons.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .forEach(e -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("reason", e.getKey());
                            r.put("count", e.getValue());
                            reasons.add(r);
                        });
                f.put("suppressed_reasons", reasons);
                m.put("otp", f);
            }
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> seriesView(Agg agg) {
        List<Map<String, Object>> out = new ArrayList<>(agg.w.buckets());
        for (int i = 0; i < agg.w.buckets(); i++) {
            long[] row = agg.series[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", iso(agg.w.bucketStart(i)));
            for (LoginChannel ch : CHANNELS) m.put(ch.name(), row[ch.ordinal()]);
            m.put("OTHER", row[COL_OTHER]);
            m.put("failed", row[COL_FAILED]);
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> reasonViews(Agg agg) {
        List<Map<String, Object>> out = new ArrayList<>();
        agg.reasons.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Long.compare(b.getValue()[0], a.getValue()[0]);
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .forEach(e -> {
                    long[] v = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reason", e.getKey());
                    m.put("count", v[0]);
                    Map<String, Object> by = new LinkedHashMap<>();
                    for (LoginChannel ch : CHANNELS) if (v[1 + ch.ordinal()] > 0) by.put(ch.name(), v[1 + ch.ordinal()]);
                    if (v[CHANNELS.length + 1] > 0) by.put("UNKNOWN", v[CHANNELS.length + 1]);
                    m.put("channels", by);
                    out.add(m);
                });
        return out;
    }

    // ── Uçlar ────────────────────────────────────────────────────────────────────────────────

    /** Kurum geneli özet (önbellekli). */
    public Map<String, Object> summary(int days, boolean fresh) {
        return snapshot(normalizeDays(days), fresh).summary();
    }

    /**
     * Kullanıcı satırları — anlık görüntüden: arama (kullanıcı adı / görünen ad, harf duyarsız), kanal süzgeci (o kanalda
     * başarılı ya da başarısız denemesi olan), sıralama ({@code logins} / {@code failures} / {@code last} / {@code name}),
     * 1-tabanlı sayfa.
     */
    public Map<String, Object> users(int days, String q, String channel, String sort, int page, int size, boolean fresh) {
        return users(days, q, channel, sort, page, size, fresh, false);
    }

    /** {@code export=true}: CSV dışa aktarımı — süzülmüş TÜM satırlar tek sayfada (en çok {@value #EXPORT_MAX}). */
    public Map<String, Object> users(int days, String q, String channel, String sort, int page, int size, boolean fresh,
                                     boolean export) {
        Snapshot s = snapshot(normalizeDays(days), fresh);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.forLanguageTag("tr"));
        LoginChannel ch = LoginChannel.fromMethod(channel);
        List<UserAgg> list = new ArrayList<>();
        for (UserAgg u : s.users()) {
            if (ch != null && !u.touches(ch)) continue;
            if (!needle.isEmpty()) {
                String hay = (u.info.username() + " " + (u.info.displayName() == null ? "" : u.info.displayName()))
                        .toLowerCase(Locale.forLanguageTag("tr"));
                if (!hay.contains(needle)) continue;
            }
            list.add(u);
        }
        String sortKey = sort != null && SORTS.contains(sort) ? sort : "logins";
        list.sort(switch (sortKey) {
            case "failures" -> Comparator.comparingLong((UserAgg u) -> u.failed).reversed().thenComparing(byName());
            case "last" -> Comparator.comparing((UserAgg u) -> u.lastSuccessAt == null ? "" : u.lastSuccessAt).reversed()
                    .thenComparing(byName());
            case "name" -> byName();
            default -> byLogins();
        });
        int sz = export ? EXPORT_MAX : Math.max(1, Math.min(PAGE_SIZE_MAX, size <= 0 ? PAGE_SIZE_DEFAULT : size));
        int total = list.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) sz));
        int p = Math.max(1, Math.min(page <= 0 ? 1 : page, pages));
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserAgg u : list.subList(Math.min(total, (p - 1) * sz), Math.min(total, p * sz))) items.add(userRow(u));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", p);
        out.put("size", sz);
        out.put("total_pages", pages);
        out.put("days", normalizeDays(days));
        out.put("sort", sortKey);
        out.put("generated_at", s.summary().get("generated_at"));
        return out;
    }

    private static Comparator<UserAgg> byName() {
        return Comparator.comparing((UserAgg u) -> lc(u.info.username()));
    }

    private static Comparator<UserAgg> byLogins() {
        return Comparator.comparingLong(UserAgg::successTotal).reversed().thenComparing(byName());
    }

    private static Map<String, Object> userRow(UserAgg u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.info.username());
        m.put("display_name", u.info.displayName());
        m.put("team_id", u.info.teamId());
        m.put("team_name", u.info.teamName());
        m.put("source", u.info.source());
        m.put("active", u.info.active());
        Map<String, Object> by = new LinkedHashMap<>();
        for (LoginChannel ch : CHANNELS) by.put(ch.name(), u.success[ch.ordinal()]);
        m.put("success", by);
        Map<String, Object> failedBy = new LinkedHashMap<>();
        for (LoginChannel ch : CHANNELS) if (u.failedBy[ch.ordinal()] > 0) failedBy.put(ch.name(), u.failedBy[ch.ordinal()]);
        m.put("failed_by", failedBy);
        long ok = u.successTotal();
        m.put("success_total", ok);
        m.put("failed", u.failed);
        m.put("attempts", ok + u.failed);
        m.put("success_rate", rate(ok, u.failed));
        m.put("estimated", u.estimated);
        m.put("last_success", u.lastSuccessAt == null ? null
                : Map.of("at", u.lastSuccessAt, "channel", u.lastSuccessChannel.name()));
        if (u.lastFailureAt == null) {
            m.put("last_failure", null);
        } else {
            Map<String, Object> lf = new LinkedHashMap<>();
            lf.put("at", u.lastFailureAt);
            lf.put("reason", u.lastFailureReason);
            m.put("last_failure", lf);
        }
        return m;
    }

    /**
     * TEK kullanıcının ayrıntısı (önbelleksiz, tek sorgu): özet, kanal kırılımı, nedenler, küçük trend ve son
     * {@value #RECENT_MAX} olay (zaman, olay, kanal, sonuç, neden, IP, konum, cihaz, anomali). Yük {@link IdentityMask}'ten
     * geçer: {@code identityVisible=false} görüntüleyicide IP / konum / cihaz düşer (kişinin kendi satırları hariç).
     */
    public Map<String, Object> user(String username, int days, boolean identityVisible, String viewer) {
        int d = normalizeDays(days);
        Instant now = clock.instant();
        Window w = window(d, now);
        String key = lc(username);
        UserInfo info = null;
        try {
            AppUser u = key.isEmpty() ? null : userRepo.findByUsername(username.trim()).orElse(null);
            if (u != null) {
                String team = null;
                if (u.getTeamId() != null) team = teamRepo.findById(u.getTeamId()).map(Team::getName).orElse(null);
                info = new UserInfo(u.getUsername(), u.getDisplayName(), u.getTeamId(), team, sourceOf(u.getAuthSource()),
                        !Boolean.FALSE.equals(u.getActive()));
            }
        } catch (Exception e) {
            log.debug("Giriş istatistikleri: kullanıcı okunamadı — {}", e.toString());
        }
        List<AuditLog> rows = key.isEmpty() ? List.of() : auditRepo.findLoginStatEventsForActor(
                LoginEventClassifier.EVENT_TYPES, iso(w.fromEpoch()), key, PageRequest.of(0, USER_ROW_CAP + 1));
        boolean truncated = rows.size() > USER_ROW_CAP;
        if (truncated) rows = rows.subList(0, USER_ROW_CAP);

        final UserInfo self = info;
        Map<String, UserInfo> dir = self == null ? Map.of() : Map.of(key, self);
        Function<String, String> src = sourceLookup(dir);
        Agg agg = new Agg(w);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (AuditLog a : rows) {
            LoginEventClassifier.Result r = agg.add(a.getEventType(), a.getEventTime(), a.getActor(), a.getFailureReason(),
                    a.getDetail(), src, dir);
            if (recent.size() < RECENT_MAX) recent.add(eventRow(a, r));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", self != null ? self.username() : username);
        user.put("display_name", self == null ? null : self.displayName());
        user.put("team_id", self == null ? null : self.teamId());
        user.put("team_name", self == null ? null : self.teamName());
        user.put("source", self == null ? null : self.source());
        user.put("active", self == null ? null : self.active());
        out.put("user", user);
        out.put("found", self != null);
        out.put("days", d);
        out.put("granularity", w.hourly() ? "hour" : "day");
        out.put("from", iso(w.fromEpoch()));
        out.put("to", iso(w.toEpoch()));
        out.put("truncated", truncated);
        out.put("estimated", agg.estimated);
        out.put("totals", new Totals(agg.success, agg.failed, agg.uniqueUsers.size()).view());
        List<Map<String, Object>> channels = new ArrayList<>();
        for (LoginChannel ch : CHANNELS) {
            ChannelAgg c = agg.channels[ch.ordinal()];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", ch.name());
            m.put("success", c.success);
            m.put("failed", c.failed);
            m.put("attempts", c.success + c.failed);
            m.put("success_rate", rate(c.success, c.failed));
            channels.add(m);
        }
        out.put("channels", channels);
        out.put("failure_reasons", reasonViews(agg));
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < w.buckets(); i++) {
            long ok = 0;
            for (int c = 0; c <= COL_OTHER; c++) ok += agg.series[i][c];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", iso(w.bucketStart(i)));
            m.put("success", ok);
            m.put("failed", agg.series[i][COL_FAILED]);
            series.add(m);
        }
        out.put("series", series);
        out.put("recent", recent);
        return IdentityMask.apply(out, identityVisible, viewer);
    }

    private static Map<String, Object> eventRow(AuditLog a, LoginEventClassifier.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("time", a.getEventTime());
        m.put("event", a.getEventType());
        m.put("actor", a.getActor());
        LoginChannel ch = r.channel() != null ? r.channel() : r.otpChannel();
        m.put("channel", ch == null ? null : ch.name());
        m.put("channel_estimated", r.estimated());
        m.put("outcome", a.getOutcome());
        m.put("reason", r.reason() != null ? r.reason() : r.otpReason());
        if (r.otpResult() != null) m.put("otp_result", r.otpResult());
        m.put("ip", a.getIpAddress());
        m.put("country", a.getIpCountry());
        m.put("city", a.getIpCity());
        m.put("ua_summary", UserAgentSummary.labelOf(a.getUserAgent()));
        m.put("flags", a.getAnomalyFlags());
        return m;
    }
}
