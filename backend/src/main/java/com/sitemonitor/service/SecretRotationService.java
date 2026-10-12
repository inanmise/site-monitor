package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gizli anahtar döndürme (2026-10-11, kullanıcı isteği: "Gizli anahtar döndürme bunu da yapalım").
 *
 * <p>{@link SecretCipher} şifrelemeyi her zaman güncel anahtarla ({@code SITE_MONITOR_SECRET_KEY}) yapar; çözmede önceki
 * anahtarı ({@code SITE_MONITOR_SECRET_KEY_PREVIOUS}) da dener. Bu servis veritabanındaki TÜM şifreli değerleri
 * ({@link #LOCATIONS}) sayar (güncel / önceki / okunamayan) ve önceki anahtarla duranları güncel anahtarla yeniden yazar.
 * Bittiğinde önceki anahtar ortamdan kaldırılabilir.
 *
 * <ul>
 *   <li><b>Okuma:</b> konum başına id sırasıyla 500'lük sayfalar, en çok {@link #MAX_ROWS} satır (sınırsız döngü yok).</li>
 *   <li><b>Yazma:</b> koşullu {@code UPDATE … WHERE id = ? AND kolon = <okunan değer>} — arada biri değeri değiştirdiyse
 *       satır ezilmez ("arada değişti" sayılır). Varlık kaydı (entity save) kullanılmaz; ayar önbellekleri {@code updated_at}
 *       damgasıyla diğer pod'larda da tazelenir.</li>
 *   <li><b>Tekil çalışma:</b> {@code scheduler_lock} anahtarı {@value #LOCK} — iki tık / iki pod aynı anda yazmaz.</li>
 *   <li><b>Gizlilik:</b> çözülen değer ve anahtar hiçbir yere yazılmaz (log, denetim, yanıt); yalnız sayılar ve kayıt adları.</li>
 * </ul>
 * Kısa ömürlü giriş kodu özetleri (OTP) yeniden şifrelenmez: süresi en çok 5 dk, doğrulama önceki alt anahtarı da kabul eder.
 */
@Slf4j
@Service
public class SecretRotationService {

    public static final String LOCK = "secret-reencrypt";
    static final int PAGE = 500;
    static final int MAX_ROWS = 100_000;
    static final int MAX_SAMPLES = 20;
    /** Bir yeniden şifreleme çalıştırmasının süre bütçesi — kilit süresinin (10 dk) altında (2026-10-12). */
    static final long RUN_BUDGET_MS = 8 * 60_000L;

    /** Sayım ve çalıştırma başına konum tavanı (test kancası; varsayılan {@link #MAX_ROWS}). */
    int maxRows = MAX_ROWS;

    /**
     * Konum → kaldığı yer (son işlenen id). Tavana / süre bütçesine takılan konum bir SONRAKİ çalıştırmada buradan sürer
     * (2026-10-12: eskiden her çalıştırma baştan başlayıp ilk 100 bin satırı yeniden okuyor, sonrasına hiç ulaşamıyordu).
     * Pod belleği — pod yeniden başlarsa baştan; ilerleme yine sağlanır (her çalıştırma en az bir tavan kadar ilerler).
     */
    private final Map<String, Long> resumeAfter = new java.util.concurrent.ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Değer biçimi: tek şifreli değer ya da {@code [{name, value, secret}]} JSON dizisindeki secret değerler. */
    public enum Kind { VALUE, JSON_SECRETS }

    /**
     * Şifreli değer konumu. {@code nameColumn} = okunamayan kayıtları adıyla göstermek için (yoksa null → "#id");
     * {@code where} = ek süzgeç (sabit SQL, kullanıcı girdisi DEĞİL); {@code touchUpdatedAt} = önbelleği
     * {@code updated_at} farkıyla tazelenen tablolar.
     */
    public record Location(String id, String table, String column, Kind kind, String nameColumn, String where,
                           boolean touchUpdatedAt, String labelTr, String labelEn) { }

    /** Tek kaynak: SecretCipher ile yazılan her kalıcı değer (yeni bir şifreli alan eklenirse buraya da eklenir). */
    public static final List<Location> LOCATIONS = List.of(
            new Location("ldap_bind_password", "ldap_settings", "bind_password_enc", Kind.VALUE, null, null, true,
                    "LDAP bind parolası", "LDAP bind password"),
            new Location("smtp_password", "smtp_settings", "password_enc", Kind.VALUE, null, null, true,
                    "SMTP parolası", "SMTP password"),
            new Location("http_basic_auth", "http_monitors", "basic_auth_pass_enc", Kind.VALUE, "name", null, false,
                    "HTTP izleme — Basic auth parolası", "HTTP monitor — basic auth password"),
            new Location("http_headers", "http_monitors", "custom_headers_enc", Kind.VALUE, "name", null, false,
                    "HTTP izleme — özel başlıklar", "HTTP monitor — custom headers"),
            new Location("keyword_headers", "keyword_monitors", "custom_headers_enc", Kind.VALUE, "name", null, false,
                    "Anahtar kelime izleme — özel başlıklar", "Keyword monitor — custom headers"),
            new Location("pagespeed_basic_auth", "pagespeed_monitors", "basic_auth_pass_enc", Kind.VALUE, "name", null, false,
                    "Sayfa hızı — Basic auth parolası", "PageSpeed — basic auth password"),
            new Location("pagespeed_headers", "pagespeed_monitors", "custom_headers_enc", Kind.VALUE, "name", null, false,
                    "Sayfa hızı — özel başlıklar", "PageSpeed — custom headers"),
            new Location("scripted_env", "scripted_monitors", "env_json", Kind.JSON_SECRETS, "name", null, false,
                    "Senaryolu izleme — gizli ortam değişkenleri", "Scripted monitor — secret environment variables"),
            new Location("scripted_versions", "scripted_script_versions", "env_json", Kind.JSON_SECRETS, "version", null, false,
                    "Senaryo sürüm geçmişi — gizli ortam değişkenleri", "Script version history — secret environment variables"),
            new Location("push_headers", "app_settings", "setting_value", Kind.JSON_SECRETS, null,
                    "setting_key = 'site.monitor.userpush.headers'", true,
                    "Kişisel push — gizli istek başlıkları", "Personal push — secret request headers"));

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private SchedulerService scheduler;
    private AppSettingsService appSettings;

    public SecretRotationService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Autowired(required = false)
    @Lazy
    public void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Autowired(required = false)
    @Lazy
    public void setAppSettings(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    // ── Durum ─────────────────────────────────────────────────────────────────────────────────

    /** Konum sayımı: değer sayıları + okunamayan kayıt örnekleri. */
    public record LocationCount(Location loc, int rows, int current, int previous, int unreadable,
                                List<String> unreadableSamples, boolean truncated, String error) { }

    /** Anahtar durumu + konum sayıları. Hiçbir şey yazmaz. */
    public Map<String, Object> status() {
        List<LocationCount> counts = new ArrayList<>();
        for (Location loc : LOCATIONS) counts.add(count(loc));
        return statusJson(counts);
    }

    Map<String, Object> statusJson(List<LocationCount> counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", keyJson());
        int cur = 0, prev = 0, bad = 0;
        boolean anyError = false, anyTruncated = false;
        List<Map<String, Object>> locs = new ArrayList<>();
        for (LocationCount c : counts) {
            cur += c.current();
            prev += c.previous();
            bad += c.unreadable();
            // Sayım tavanda kesildiyse (konumda MAX_ROWS'tan fazla satır) tavan sonrası satırlar hiç okunmadı: önceki anahtar
            // onlarda olabilir → "kaldırılabilir" denmez (2026-10-12).
            anyError |= c.error() != null || c.truncated();
            anyTruncated |= c.truncated();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.loc().id());
            m.put("label", Msg.t(c.loc().labelTr(), c.loc().labelEn()));
            m.put("column", c.loc().table() + "." + c.loc().column());
            m.put("rows", c.rows());
            m.put("current", c.current());
            m.put("previous", c.previous());
            m.put("unreadable", c.unreadable());
            if (!c.unreadableSamples().isEmpty()) m.put("unreadable_records", c.unreadableSamples());
            if (c.truncated()) m.put("truncated", true);
            if (c.error() != null) m.put("error", c.error());
            locs.add(m);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("values", cur + prev + bad);
        totals.put("current", cur);
        totals.put("previous", prev);
        totals.put("unreadable", bad);
        out.put("totals", totals);
        out.put("locations", locs);
        // Önceki anahtar ancak hiçbir değer ona bağlı değilken (ve sayım hatasız + TAM iken) güvenle kaldırılır
        out.put("previous_removable", cipher.isPreviousKeyConfigured() && prev == 0 && !anyError);
        // Tavanda kesilen konumda önceki anahtar sayılmayan satırlarda olabilir → yeniden şifreleme açık kalır (2026-10-12)
        out.put("can_reencrypt", cipher.isKeyConfigured() && cipher.isPreviousKeyConfigured() && (prev > 0 || anyTruncated));
        return out;
    }

    private Map<String, Object> keyJson() {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("current_configured", cipher.isKeyConfigured());
        k.put("previous_configured", cipher.isPreviousKeyConfigured());
        k.put("previous_same_as_current", cipher.isPreviousSameAsCurrent());
        return k;
    }

    LocationCount count(Location loc) {
        int rows = 0, cur = 0, prev = 0, bad = 0;
        List<String> samples = new ArrayList<>();
        boolean truncated = false;
        try {
            long after = Long.MIN_VALUE;
            while (true) {
                List<Row> page = page(loc, after);
                for (Row r : page) {
                    rows++;
                    int rowBad = 0;
                    for (SecretCipher.KeySlot slot : slots(loc, r.value())) {
                        if (slot == SecretCipher.KeySlot.CURRENT) cur++;
                        else if (slot == SecretCipher.KeySlot.PREVIOUS) prev++;
                        else { bad++; rowBad++; }
                    }
                    if (rowBad > 0 && samples.size() < MAX_SAMPLES) samples.add(r.label());
                    after = r.id();
                }
                if (page.size() < PAGE) break;
                if (rows >= maxRows) { truncated = true; break; }
            }
            return new LocationCount(loc, rows, cur, prev, bad, samples, truncated, null);
        } catch (Exception e) {
            // Tablo yoksa (eski kurulum / test bağlamı) ya da okuma hatası: sayım eksik — "kaldırılabilir" denmez
            log.warn("Gizli anahtar durumu: {} okunamadı: {}", loc.id(), e.toString());
            return new LocationCount(loc, rows, cur, prev, bad, samples, truncated,
                    Msg.t("Bu konum okunamadı; sayım eksik.", "This location could not be read; the count is incomplete."));
        }
    }

    /** Bir satırdaki şifreli değerlerin anahtar yuvaları (JSON'da her secret değer ayrı). */
    List<SecretCipher.KeySlot> slots(Location loc, String value) {
        List<SecretCipher.KeySlot> out = new ArrayList<>();
        if (loc.kind() == Kind.VALUE) {
            SecretCipher.KeySlot s = cipher.classify(value);
            if (s != null) out.add(s);
            return out;
        }
        JsonNode arr = parseArray(value);
        if (arr == null) {
            out.add(SecretCipher.KeySlot.UNREADABLE);   // bozuk JSON — içindeki sırlar kullanılamaz
            return out;
        }
        for (JsonNode n : arr) {
            String v = n.path("value").asText("");
            SecretCipher.KeySlot s = n.path("secret").asBoolean(false) ? cipher.classify(v) : null;
            if (s != null) out.add(s);
        }
        return out;
    }

    // ── Yeniden şifreleme ──────────────────────────────────────────────────────────────────────

    /** Konum sonucu: yeniden yazılan değer/satır, arada değişen, okunamayan; {@code partial} = tavan / süre doldu, sürecek. */
    public record LocationResult(Location loc, int valuesRewritten, int rowsRewritten, int changedMeanwhile, int unreadable,
                                 String error, boolean partial) {
        public LocationResult(Location loc, int valuesRewritten, int rowsRewritten, int changedMeanwhile, int unreadable,
                              String error) {
            this(loc, valuesRewritten, rowsRewritten, changedMeanwhile, unreadable, error, false);
        }
    }

    /** Yeniden şifreleme reddi (409): anahtar ayarlı değil / önceki yok / iş başka yerde sürüyor. */
    public static class RotationRefused extends RuntimeException {
        private final String code;

        public RotationRefused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Önceki anahtarla duran tüm değerleri güncel anahtarla yeniden yazar. Kilit başka yerdeyse {@link RotationRefused}
     * ({@code REENCRYPT_RUNNING}). Dönen harita: konum sonuçları + toplamlar + yeni durum.
     */
    public Map<String, Object> reencrypt(String actor) {
        if (!cipher.isKeyConfigured()) {
            throw new RotationRefused("SECRET_KEY_NOT_CONFIGURED", Msg.t(
                    "Güncel gizli anahtar ayarlı değil (gömülü geliştirme anahtarı kullanılıyor). Yeniden şifreleme bu anahtara "
                            + "taşımaz; önce SITE_MONITOR_SECRET_KEY ayarlayıp uygulamayı yeniden başlatın.",
                    "The current secret key is not set (the built-in development key is in use). Re-encryption won't move "
                            + "values to it; set SITE_MONITOR_SECRET_KEY first and restart the application."));
        }
        if (!cipher.isPreviousKeyConfigured()) {
            throw new RotationRefused("PREVIOUS_KEY_NOT_CONFIGURED", Msg.t(
                    "Önceki anahtar ayarlı değil: eski değerleri çözmek için SITE_MONITOR_SECRET_KEY_PREVIOUS'a eski anahtarı "
                            + "verip uygulamayı yeniden başlatın, sonra yeniden deneyin.",
                    "No previous key is set: put the old key in SITE_MONITOR_SECRET_KEY_PREVIOUS so old values can be "
                            + "decrypted, restart the application, then try again."));
        }
        if (scheduler == null) return runReencrypt(actor);
        Optional<Map<String, Object>> r = scheduler.callWithSchedulerLock(LOCK, () -> runReencrypt(actor));
        return r.orElseThrow(() -> new RotationRefused("REENCRYPT_RUNNING", Msg.t(
                "Yeniden şifreleme şu anda başka bir oturumda ya da pod'da sürüyor. Birkaç saniye sonra durumu yenileyin.",
                "Re-encryption is already running in another session or pod. Refresh the status in a few seconds.")));
    }

    Map<String, Object> runReencrypt(String actor) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + RUN_BUDGET_MS;
        List<LocationResult> results = new ArrayList<>();
        boolean touchedSettings = false;
        for (Location loc : LOCATIONS) {
            LocationResult r = reencrypt(loc, deadline);
            results.add(r);
            if (r.rowsRewritten() > 0 && "app_settings".equals(loc.table())) touchedSettings = true;
        }
        if (touchedSettings && appSettings != null) {
            try { appSettings.refreshFromDb(); } catch (Exception e) { log.debug("ayar önbelleği tazelenemedi: {}", e.toString()); }
        }
        int values = 0, rows = 0, changed = 0, bad = 0;
        boolean partial = false;
        List<Map<String, Object>> locs = new ArrayList<>();
        for (LocationResult r : results) {
            partial |= r.partial();
            values += r.valuesRewritten();
            rows += r.rowsRewritten();
            changed += r.changedMeanwhile();
            bad += r.unreadable();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.loc().id());
            m.put("label", Msg.t(r.loc().labelTr(), r.loc().labelEn()));
            m.put("values_rewritten", r.valuesRewritten());
            m.put("rows_rewritten", r.rowsRewritten());
            m.put("changed_meanwhile", r.changedMeanwhile());
            m.put("unreadable", r.unreadable());
            if (r.partial()) m.put("partial", true);
            if (r.error() != null) m.put("error", r.error());
            locs.add(m);
        }
        log.info("Gizli anahtar yeniden şifreleme ({}): {} değer / {} satır yeniden yazıldı, {} arada değişti, {} okunamadı ({} ms){}",
                actor, values, rows, changed, bad, System.currentTimeMillis() - t0, partial ? " — yarım, yeniden çalıştırınca sürer" : "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values_rewritten", values);
        out.put("rows_rewritten", rows);
        out.put("changed_meanwhile", changed);
        out.put("unreadable", bad);
        if (partial) out.put("partial", true);
        out.put("locations", locs);
        out.put("duration_ms", System.currentTimeMillis() - t0);
        out.put("status", status());
        return out;
    }

    LocationResult reencrypt(Location loc) {
        return reencrypt(loc, Long.MAX_VALUE);
    }

    /**
     * Konumu kaldığı yerden yeniden şifreler; tavan ({@link #maxRows}) ya da {@code deadline} dolarsa kaldığı yeri saklar
     * ve {@code partial} döner. Konum sonuna gelince kaldığı yer silinir.
     *
     * <p>Tavan YAZMA girişimlerini sayar, okunan satırı değil (inceleme 4, 2026-10-12): kaldığı yer pod başınadır; sonraki
     * çalıştırma başka poda düşerse baştan okur ve eskiden tavanı zaten güncel anahtardaki satırlarla tüketip hiç ilerlemeden
     * "yarım" dönerdi (çok podda büyük tabloda sonsuza dek). Güncel satırlar ucuzca geçilir; okuma süre bütçesiyle sınırlı.
     */
    LocationResult reencrypt(Location loc, long deadline) {
        int values = 0, rows = 0, changed = 0, bad = 0, work = 0;
        try {
            long after = resumeAfter.getOrDefault(loc.id(), Long.MIN_VALUE);
            while (true) {
                List<Row> page = page(loc, after);
                for (Row r : page) {
                    after = r.id();
                    Rewrite w = rewrite(loc, r.value());
                    bad += w.unreadable();
                    if (w.newValue() == null) continue;
                    work++;
                    if (write(loc, r.id(), r.value(), w.newValue())) {
                        values += w.values();
                        rows++;
                    } else {
                        changed++;
                    }
                }
                if (page.size() < PAGE) {
                    resumeAfter.remove(loc.id());
                    return new LocationResult(loc, values, rows, changed, bad, null, false);
                }
                if (work >= maxRows || System.currentTimeMillis() >= deadline) {
                    resumeAfter.put(loc.id(), after);
                    return new LocationResult(loc, values, rows, changed, bad, null, true);
                }
            }
        } catch (Exception e) {
            log.warn("Gizli anahtar yeniden şifreleme: {} tamamlanamadı: {}", loc.id(), e.toString());
            return new LocationResult(loc, values, rows, changed, bad,
                    Msg.t("Bu konum tamamlanamadı; durumu yenileyip yeniden deneyin.",
                            "This location could not be completed; refresh the status and try again."));
        }
    }

    /** Satırın yeni değeri (yalnız önceki anahtarla duran değerler değişir; null = yazılacak bir şey yok). */
    record Rewrite(String newValue, int values, int unreadable) { }

    Rewrite rewrite(Location loc, String value) {
        if (loc.kind() == Kind.VALUE) {
            SecretCipher.KeySlot slot = cipher.classify(value);
            if (slot == SecretCipher.KeySlot.UNREADABLE) return new Rewrite(null, 0, 1);
            if (slot != SecretCipher.KeySlot.PREVIOUS) return new Rewrite(null, 0, 0);
            String next = cipher.reencryptIfPrevious(value);
            return next == null ? new Rewrite(null, 0, 1) : new Rewrite(next, 1, 0);
        }
        JsonNode arr = parseArray(value);
        if (arr == null) return new Rewrite(null, 0, 1);
        int n = 0, bad = 0;
        for (JsonNode node : arr) {
            if (!(node instanceof ObjectNode o) || !o.path("secret").asBoolean(false)) continue;
            String v = o.path("value").asText("");
            SecretCipher.KeySlot slot = cipher.classify(v);
            if (slot == SecretCipher.KeySlot.UNREADABLE) { bad++; continue; }
            if (slot != SecretCipher.KeySlot.PREVIOUS) continue;
            String next = cipher.reencryptIfPrevious(v);
            if (next == null) { bad++; continue; }
            o.put("value", next);   // yalnız değer değişir; ad, bayrak ve alan sırası korunur
            n++;
        }
        if (n == 0) return new Rewrite(null, 0, bad);
        try {
            return new Rewrite(MAPPER.writeValueAsString(arr), n, bad);
        } catch (Exception e) {
            return new Rewrite(null, 0, bad + n);
        }
    }

    // ── SQL ───────────────────────────────────────────────────────────────────────────────────

    record Row(long id, String value, String label) { }

    List<Row> page(Location loc, long afterId) {
        String like = loc.kind() == Kind.VALUE ? "enc:v1:%" : "%enc:v1:%";
        String name = loc.nameColumn() == null ? "" : ", " + loc.nameColumn();
        String sql = "SELECT id, " + loc.column() + name + " FROM " + loc.table()
                + " WHERE id > ? AND " + loc.column() + " LIKE ?"
                + (loc.where() == null ? "" : " AND " + loc.where())
                + " ORDER BY id LIMIT " + PAGE;
        return jdbc.query(sql, (rs, i) -> {
            long id = rs.getLong(1);
            String nm = loc.nameColumn() == null ? null : rs.getString(3);
            return new Row(id, rs.getString(2), nm == null || nm.isBlank() ? "#" + id : nm + " (#" + id + ")");
        }, afterId, like);
    }

    boolean write(Location loc, long id, String oldValue, String newValue) {
        String sql = "UPDATE " + loc.table() + " SET " + loc.column() + " = ?"
                + (loc.touchUpdatedAt() ? ", updated_at = ?" : "")
                + " WHERE id = ? AND " + loc.column() + " = ?";
        int n = loc.touchUpdatedAt()
                ? jdbc.update(sql, newValue, ISO.format(Instant.now()), id, oldValue)
                : jdbc.update(sql, newValue, id, oldValue);
        return n > 0;
    }

    private static JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n != null && n.isArray() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
