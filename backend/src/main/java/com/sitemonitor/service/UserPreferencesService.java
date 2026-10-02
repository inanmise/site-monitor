package com.sitemonitor.service;

import com.sitemonitor.model.UserPreference;
import com.sitemonitor.repository.UserPreferenceRepository;
import com.sitemonitor.util.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kişisel arayüz tercihleri (2026-10-02, onaylı öneri 23): kullanıcı başına TEK JSON belgesi ({@code user_preferences}).
 *
 * <p><b>Belge şeması — üst düzey anahtarlar BEYAZ LİSTELİ ({@link #TOP_KEYS}); bilinmeyen anahtar 400:</b>
 * <ul>
 *   <li>{@code favorites}: {@code [{type, id, name?}]} — dokuz izleme türünden favoriler (en çok {@value #MAX_FAVORITES}).
 *       Tür + kimlik tekilleşir; {@code name} yalnız komut paletindeki etiket içindir (bayatlayabilir).</li>
 *   <li>{@code landingTab}: girişte açılacak sekme kimliği (biçim denetlenir; görünürlüğü istemci App.jsx'in
 *       VALID_TABS + görünürlük kurallarıyla denetler — bilinmeyen/yasak değer yok sayılır, Pano açılır).</li>
 *   <li>{@code savedViews}: {@code {listeAnahtarı: [{name, params}]}} — liste başına en çok {@value #MAX_VIEWS_PER_LIST}
 *       görünüm; {@code params} o sekmenin sayfa-durumu URL parametreleri (metin değerler).</li>
 *   <li>{@code local}: tarayıcıdaki beyaz listeli localStorage tercihlerinin aynası ({@link #LOCAL_KEYS} +
 *       {@link #LOCAL_PREFIXES} — ön yüz {@code hooks/userPrefsModel.js} ile AYNI liste; sapmayı
 *       {@code userPrefsWhitelistSync.test.js} yakalar).</li>
 * </ul>
 *
 * <p><b>Birleştirme:</b> PUT KISMİ bir nesnedir; verilen üst düzey anahtar belgedekinin YERİNE geçer, {@code null} anahtarı
 * siler. Tek istisna {@code local}: GİRDİ BAZINDA birleşir (değer {@code null} = o girdiyi sil) — iki cihazın farklı
 * tercihleri birbirini ezmesin (anahtar başına son yazan kazanır).
 *
 * <p><b>Sınırlar:</b> belge en çok {@value #MAX_BYTES} bayt (UTF-8 JSON); aşımı 400. Hata metinleri arayüz dilinde
 * ({@link Msg#t}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_FAVORITES = 200;
    public static final int MAX_VIEWS_PER_LIST = 50;
    public static final int MAX_VIEW_LISTS = 40;
    public static final int MAX_VIEW_NAME = 60;
    public static final int MAX_VIEW_PARAMS = 30;
    public static final int MAX_PARAM_VALUE = 500;
    public static final int MAX_FAVORITE_NAME = 300;
    public static final int MAX_LOCAL_VALUE = 16 * 1024;

    public static final String FAVORITES = "favorites";
    public static final String LANDING_TAB = "landingTab";
    public static final String SAVED_VIEWS = "savedViews";
    public static final String LOCAL = "local";

    /** Üst düzey anahtar beyaz listesi. */
    public static final Set<String> TOP_KEYS = Set.of(FAVORITES, LANDING_TAB, SAVED_VIEWS, LOCAL);

    /** Favori olabilen izleme türleri — sekme kimlikleriyle aynı (GlobalSearchService.MONITOR_KINDS). */
    public static final Set<String> MONITOR_TYPES =
            Set.of("http", "ping", "port", "dns", "domain", "keyword", "page", "pagespeed", "scripted");

    /**
     * Sunucuda aynalanan localStorage anahtarları (TAM ad). Ön yüz {@code LOCAL_PREF_KEYS} ile birebir aynı — oturum,
     * taslak, önbellek, tur durumu, dil/tema ve kişisel "son kullanılanlar" BİLİNÇLİ OLARAK yok.
     */
    public static final List<String> LOCAL_KEYS = List.of(
            "sidebar-open", "today-panel-open",
            "certtable-view", "certtable-presets",
            "sm.audit.savedViews", "sm.audit.insightsOpen",
            "inventory-view", "inventory-saved-views", "inv-hygiene-open", "inv-stats-open",
            "cfg-health-open",
            "wr-thisweek-open", "wr-completion-open", "wr-help-open",
            "sm.userpush.sections",
            "renewal-view", "renewal-guide-platform",
            "sm.incidents.view", "incidents-banner-dismissed",
            "sm.warnings.view", "uptime-scope",
            "sm.checkRun.teams");

    /** Önekli aileler (liste başına sayfa boyutu, izleme türü başına "Şimdi Kontrol Et" takım seçimi). */
    public static final List<String> LOCAL_PREFIXES = List.of("sm.pageSize.", "sm.checkRun.teams.");

    private static final Pattern TAB_ID = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");
    private static final Pattern LIST_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");
    private static final Pattern PARAM_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern LOCAL_SUFFIX = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");
    private static final Pattern MONITOR_ID = Pattern.compile("^[0-9]{1,18}$");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UserPreferenceRepository repo;

    /** Birleştirme sonucu: güncel belge, değişen üst düzey anahtarlar, yazım anı. */
    public record Result(Map<String, Object> prefs, Set<String> changed, String updatedAt) { }

    /** Okunan belge: satır yoksa boş belge + {@code updatedAt = null}. */
    public record Snapshot(Map<String, Object> prefs, String updatedAt) { }

    /** localStorage anahtarı aynalanabilir mi (tam ad ya da önekli aile + güvenli sonek). */
    public static boolean isLocalKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (LOCAL_KEYS.contains(key)) return true;
        for (String p : LOCAL_PREFIXES) {
            if (key.startsWith(p) && LOCAL_SUFFIX.matcher(key.substring(p.length())).matches()) return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Snapshot get(long userId) {
        return repo.findById(userId)
                .map(r -> new Snapshot(parse(r.getPrefs()), r.getUpdatedAt()))
                .orElseGet(() -> new Snapshot(new LinkedHashMap<>(), null));
    }

    /**
     * Kısmi yamayı kullanıcının belgesine uygular ve kaydeder. Doğrulama hatası {@link IllegalArgumentException}
     * (GlobalExceptionHandler → 400) — belge DEĞİŞMEZ. Yalnız değişen anahtar varsa yazar.
     */
    @Transactional
    public Result merge(long userId, Map<String, Object> patch) {
        if (patch == null) throw new IllegalArgumentException(Msg.t("Tercih gövdesi boş.", "Preferences body is empty."));
        for (String k : patch.keySet()) {
            if (!TOP_KEYS.contains(k)) {
                throw new IllegalArgumentException(Msg.t("Bilinmeyen tercih anahtarı: " + k, "Unknown preference key: " + k));
            }
        }
        UserPreference row = repo.findById(userId).orElse(null);
        Map<String, Object> before = row == null ? new LinkedHashMap<>() : parse(row.getPrefs());
        Map<String, Object> next = new LinkedHashMap<>(before);

        if (patch.containsKey(FAVORITES)) putOrRemove(next, FAVORITES, normalizeFavorites(patch.get(FAVORITES)));
        if (patch.containsKey(LANDING_TAB)) putOrRemove(next, LANDING_TAB, normalizeLandingTab(patch.get(LANDING_TAB)));
        if (patch.containsKey(SAVED_VIEWS)) putOrRemove(next, SAVED_VIEWS, normalizeSavedViews(patch.get(SAVED_VIEWS)));
        if (patch.containsKey(LOCAL)) putOrRemove(next, LOCAL, mergeLocal(before.get(LOCAL), patch.get(LOCAL)));

        // JSON metniyle karşılaştır: okunan belgede kimlik Integer, normalleşmişte Long — değer aynıyken "değişti" denmesin.
        Set<String> changed = new LinkedHashSet<>();
        for (String k : TOP_KEYS) if (!Objects.equals(writeValue(before.get(k)), writeValue(next.get(k)))) changed.add(k);

        String json = write(next);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(Msg.t(
                    "Tercih belgesi çok büyük (en çok " + (MAX_BYTES / 1024) + " KB).",
                    "Preferences document is too large (max " + (MAX_BYTES / 1024) + " KB)."));
        }
        if (changed.isEmpty() && row != null) return new Result(next, changed, row.getUpdatedAt());

        if (row == null) {
            row = new UserPreference();
            row.setUserId(userId);
        }
        String now = ISO.format(Instant.now());
        row.setPrefs(json);
        row.setUpdatedAt(now);
        repo.save(row);
        return new Result(next, changed, now);
    }

    // ── Normalleştirme / doğrulama ───────────────────────────────────────────────────────────────

    private static void putOrRemove(Map<String, Object> doc, String key, Object value) {
        if (value == null) doc.remove(key); else doc.put(key, value);
    }

    /** {@code [{type, id, name?}]} → tekil, sınırlı liste; null = anahtarı sil. */
    static List<Map<String, Object>> normalizeFavorites(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) throw bad("favorites bir dizi olmalı.", "favorites must be an array.");
        if (list.size() > MAX_FAVORITES) {
            throw bad("En çok " + MAX_FAVORITES + " favori izleme eklenebilir.", "At most " + MAX_FAVORITES + " favourite monitors are allowed.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw bad("Geçersiz favori kaydı.", "Invalid favourite entry.");
            String type = m.get("type") == null ? "" : String.valueOf(m.get("type"));
            if (!MONITOR_TYPES.contains(type)) throw bad("Geçersiz favori türü: " + type, "Invalid favourite type: " + type);
            long id = monitorId(m.get("id"));
            if (!seen.add(type + ":" + id)) continue;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", type);
            f.put("id", id);
            Object name = m.get("name");
            if (name != null && !String.valueOf(name).isBlank()) f.put("name", trunc(String.valueOf(name).trim(), MAX_FAVORITE_NAME));
            out.add(f);
        }
        return out;
    }

    private static long monitorId(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (raw instanceof Number n && !(raw instanceof Double) && !(raw instanceof Float)) s = String.valueOf(n.longValue());
        if (!MONITOR_ID.matcher(s).matches()) throw bad("Geçersiz izleme kimliği.", "Invalid monitor id.");
        long id = Long.parseLong(s);
        if (id < 1) throw bad("Geçersiz izleme kimliği.", "Invalid monitor id.");
        return id;
    }

    /** Sekme kimliği biçimi; boş/null = anahtarı sil. */
    static String normalizeLandingTab(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) throw bad("landingTab metin olmalı.", "landingTab must be a string.");
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (!TAB_ID.matcher(v).matches()) throw bad("Geçersiz açılış sekmesi.", "Invalid landing tab.");
        return v;
    }

    /** {@code {liste: [{name, params}]}}; boş liste o anahtarı düşürür, null tüm görünümleri siler. */
    static Map<String, Object> normalizeSavedViews(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> lists)) throw bad("savedViews bir nesne olmalı.", "savedViews must be an object.");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : lists.entrySet()) {
            String listKey = String.valueOf(e.getKey());
            if (!LIST_KEY.matcher(listKey).matches()) throw bad("Geçersiz liste anahtarı: " + listKey, "Invalid list key: " + listKey);
            if (e.getValue() == null) continue;
            if (!(e.getValue() instanceof List<?> views)) throw bad("Görünüm listesi bir dizi olmalı.", "A view list must be an array.");
            if (views.size() > MAX_VIEWS_PER_LIST) {
                throw bad("Bir listede en çok " + MAX_VIEWS_PER_LIST + " görünüm kaydedilebilir.",
                        "At most " + MAX_VIEWS_PER_LIST + " views can be saved per list.");
            }
            List<Map<String, Object>> norm = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (Object v : views) {
                if (!(v instanceof Map<?, ?> view)) throw bad("Geçersiz görünüm kaydı.", "Invalid view entry.");
                String name = view.get("name") == null ? "" : String.valueOf(view.get("name")).trim();
                if (name.isEmpty()) throw bad("Görünüm adı boş olamaz.", "A view name cannot be empty.");
                if (name.length() > MAX_VIEW_NAME) {
                    throw bad("Görünüm adı en çok " + MAX_VIEW_NAME + " karakter olabilir.", "A view name can be at most " + MAX_VIEW_NAME + " characters.");
                }
                if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) continue;
                Map<String, Object> view2 = new LinkedHashMap<>();
                view2.put("name", name);
                view2.put("params", normalizeParams(view.get("params")));
                norm.add(view2);
            }
            if (!norm.isEmpty()) out.put(listKey, norm);
        }
        if (out.size() > MAX_VIEW_LISTS) throw bad("Çok fazla görünüm listesi.", "Too many view lists.");
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> normalizeParams(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        if (!(raw instanceof Map<?, ?> m)) throw bad("Görünüm parametreleri bir nesne olmalı.", "View parameters must be an object.");
        if (m.size() > MAX_VIEW_PARAMS) throw bad("Görünümde çok fazla parametre var.", "Too many parameters in a view.");
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!PARAM_KEY.matcher(k).matches()) throw bad("Geçersiz görünüm parametresi: " + k, "Invalid view parameter: " + k);
            Object v = e.getValue();
            if (v == null) continue;
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw bad("Görünüm parametresi metin olmalı: " + k, "A view parameter must be text: " + k);
            }
            String s = String.valueOf(v);
            if (s.length() > MAX_PARAM_VALUE) throw bad("Görünüm parametresi çok uzun: " + k, "View parameter is too long: " + k);
            if (!s.isEmpty()) out.put(k, s);
        }
        return out;
    }

    /** {@code local} GİRDİ BAZINDA birleşir: değer null = girdiyi sil; anahtar beyaz listede olmalı. */
    static Map<String, Object> mergeLocal(Object current, Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> patch)) throw bad("local bir nesne olmalı.", "local must be an object.");
        Map<String, Object> out = new LinkedHashMap<>();
        if (current instanceof Map<?, ?> cur) cur.forEach((k, v) -> { if (v instanceof String s) out.put(String.valueOf(k), s); });
        for (Map.Entry<?, ?> e : patch.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!isLocalKey(k)) throw bad("Bilinmeyen tarayıcı tercihi: " + k, "Unknown browser preference: " + k);
            Object v = e.getValue();
            if (v == null) { out.remove(k); continue; }
            if (!(v instanceof String s)) throw bad("Tarayıcı tercihi metin olmalı: " + k, "A browser preference must be text: " + k);
            if (s.length() > MAX_LOCAL_VALUE) throw bad("Tarayıcı tercihi çok büyük: " + k, "Browser preference is too large: " + k);
            out.put(k, s);
        }
        return out;
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────────────────────

    /** JSON → harita (bozuk/boş → boş belge; istemci "hiç tercih yok" sayar). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = JSON.readValue(json, Object.class);
            if (!(o instanceof Map<?, ?> m)) return new LinkedHashMap<>();
            Map<String, Object> out = new LinkedHashMap<>();
            // Bilinmeyen (eski sürümden kalma) anahtarlar okunurken de düşer — belge yalnız beyaz listeyi taşır.
            ((Map<String, Object>) m).forEach((k, v) -> { if (TOP_KEYS.contains(k) && v != null) out.put(k, v); });
            return out;
        } catch (Exception e) {
            log.debug("user_preferences çözülemedi: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static String write(Map<String, Object> doc) {
        try {
            return JSON.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalArgumentException(Msg.t("Tercihler yazılamadı.", "Preferences could not be written."));
        }
    }

    private static String writeValue(Object v) {
        if (v == null) return null;
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String trunc(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private static IllegalArgumentException bad(String tr, String en) { return new IllegalArgumentException(Msg.t(tr, en)); }
}
