package com.sitemonitor.service;

import com.sitemonitor.util.Msg;
import com.sitemonitor.model.AppSetting;
import com.sitemonitor.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Çalışma anında düzenlenebilir config deposu. DB'deki override'ları (app_settings)
 * application.properties/@Value varsayılanlarının ÜSTÜNE bindirir ve bellek cache'inde
 * tutar. Tüketici kodlar getX(key, fallback) ile OKUMA ANINDA çağırır → kaydedince
 * (cache anında tazelenir) değişiklik restart'sız yansır.
 *
 * Yalnız {@link AppSettingsCatalog} key'leri kaydedilebilir (tipli doğrulama).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String LOG_LEVEL_KEY = "logging.level.com.sitemonitor";
    private static final String MAIL_LOG_LEVEL_KEY = "logging.level.com.sitemonitor.mail";

    private final AppSettingRepository repo;
    private final Environment environment;
    // Durum taşıyan tüketiciler (executor havuzu) için değişiklik olayı — okuma-anı getter'lar
    // olaysız da canlı; olay yalnız "kendine uygulaması gerekenler" içindir.
    private final org.springframework.context.ApplicationEventPublisher publisher;

    /** key → override değeri (yalnız set edilmişler). Immutable snapshot; save'de yenisiyle değişir. */
    private volatile Map<String, String> overrides = Map.of();

    @PostConstruct
    void load() {
        try {
            Map<String, String> m = new HashMap<>();
            for (AppSetting s : repo.findAll()) {
                if (s.getValue() != null) m.put(s.getSettingKey(), s.getValue());
            }
            this.overrides = Map.copyOf(m);
            log.info("AppSettings loaded: {} override(s)", m.size());
            applyLogLevel(); // boot'ta saklı log seviyesi override'ını uygula
        } catch (Exception e) {
            log.warn("AppSettings load skipped (table not ready?): {}", e.getMessage());
        }
    }

    /** Çok-pod tutarlılığı: başka bir instance app_settings'i değiştirdiyse override haritasını DB'den tazele. */
    @Scheduled(fixedDelayString = "${site.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    void refreshFromDb() {
        try {
            Map<String, String> m = new HashMap<>();
            for (AppSetting s : repo.findAll()) {
                if (s.getValue() != null) m.put(s.getSettingKey(), s.getValue());
            }
            Map<String, String> next = Map.copyOf(m);
            if (!next.equals(overrides)) {
                Map<String, String> prev = overrides;
                this.overrides = next;
                applyLogLevel();
                log.info("AppSettings cache refreshed from DB (updated by another instance): {} override(s)", next.size());
                publish(diffKeys(prev, next), "refresh");
            }
        } catch (Exception e) {
            log.debug("AppSettings refresh skipped: {}", e.getMessage());
        }
    }

    // ── Çalışma anı tipli getter'lar (tüketiciler bunları çağırır) ─────────────

    public String getString(String key, String fallback) {
        String v = resolve(key);
        return v != null ? v : fallback;
    }

    public int getInt(String key, int fallback) {
        String v = resolve(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = resolve(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : fallback;
    }

    public double getDouble(String key, double fallback) {
        String v = resolve(key);
        if (v == null) return fallback;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return fallback; }
    }

    public List<String> getCsv(String key, String fallbackCsv) {
        String v = resolve(key);
        String raw = v != null ? v : fallbackCsv;
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Override varsa onu, yoksa Environment'taki property (varsayılan) değerini döner. */
    private String resolve(String key) {
        String o = overrides.get(key);
        if (o != null) return o;
        return environment.getProperty(key);
    }

    // ── Page beslemesi: katalog + efektif değerler ─────────────────────────────

    public List<Map<String, Object>> getCatalogForClient() {
        List<Map<String, Object>> out = new ArrayList<>();
        // Kapsamlı müdür (AD ADMIN) için GLOBAL_ONLY kalemler salt-okunur işaretlenir; UI kilitler,
        // save() yine de sunucu tarafında reddeder (UI'ya güvenilmez).
        boolean scoped = com.sitemonitor.controller.SessionScope.isScopedAdminInRequest();
        for (AppSettingsCatalog.Setting s : AppSettingsCatalog.ALL) {
            String def = environment.getProperty(s.key());
            boolean globalOnly = AppSettingsCatalog.isGlobalOnly(s.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("group", s.group());
            m.put("type", s.type().name());
            m.put("value", overrides.getOrDefault(s.key(), def));
            m.put("default", def);
            m.put("overridden", overrides.containsKey(s.key()));
            m.put("global_only", globalOnly);
            m.put("read_only", globalOnly && scoped);
            if (!s.enumOptions().isEmpty()) m.put("options", s.enumOptions());
            out.add(m);
        }
        return out;
    }

    /** body: { "values": { key: value, ... } } (boş değer = override'ı kaldır). */
    @Transactional
    public void save(Map<String, Object> body, String actor) {
        Object raw = body.get("values");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (raw instanceof Map) ? (Map<String, Object>) raw : body;

        // 1) Önce HEPSİNİ doğrula (tip + alanlar-arası kural), sonra yaz: yarım kayıt yok.
        Map<String, String> next = new HashMap<>(overrides);
        Map<String, String> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String key = e.getKey();
            AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(key);
            if (s == null) throw new IllegalArgumentException(Msg.t("Bilinmeyen ayar: ", "Unknown setting: ") + key);
            String val = e.getValue() == null ? null : e.getValue().toString().trim();
            validate(s, val);
            // GLOBAL_ONLY anahtarını YALNIZ global yönetici yazar — hangi denetleyiciden gelirse
            // gelsin tek kapı. Şart eskiden isScopedAdminInRequest() idi; o yalnız "systemRole=ADMIN
            // ama takım-kapsamlı" durumunu yakalıyor, TEAM_ADMIN ve USER için FALSE dönüyordu.
            // İzin matrisinden settings.general/edit verilen bir role (matris UI bunu normal bir
            // tık olarak sunuyor) monitoring.allow-loopback-targets (SsrfGuard'ın loopback kapısı),
            // trust.ca-bundle-pem (giden TLS güvenine kendi CA'sı) ve userpush.url + headers
            // (çözülmüş sırlarla push trafiğini kendi sunucusuna çevirme) yazma yolu açılıyordu.
            // Anahtar kümesinin adı ve AppSettingsCatalog yorumu zaten "yalnız global yönetici"
            // diyordu; kontrol bunu uygulamıyordu.
            if (AppSettingsCatalog.isGlobalOnly(key)
                    && !com.sitemonitor.controller.SessionScope.isGlobalAdminInRequest()) {
                throw new SecurityException(com.sitemonitor.util.Msg.t(
                        "Bu ayar yalnız global yönetici tarafından değiştirilebilir: ",
                        "Only a global administrator can change this setting: ") + key);
            }
            normalized.put(key, val);
            if (val == null || val.isEmpty()) next.remove(key);
            else next.put(key, val);
        }
        validateCrossField(next);

        for (Map.Entry<String, String> e : normalized.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            AppSetting row = repo.findBySettingKey(key).orElseGet(() -> new AppSetting(key, null, null, null));
            row.setSettingKey(key);
            row.setValue((val == null || val.isEmpty()) ? null : val);
            row.setUpdatedAt(now());
            row.setUpdatedBy(actor);
            repo.save(row);
        }
        this.overrides = Map.copyOf(next); // cache ANINDA tazelenir → tüketiciler yeni değeri okur
        applyLogLevel();
        publish(normalized.keySet(), "save");
    }

    /**
     * Tek anahtar tipi doğru olsa da BİRLİKTE geçersiz olabilen ayarlar (executor core &gt; max).
     * Efektif değer = yeni override haritası, yoksa Environment varsayılanı.
     */
    private void validateCrossField(Map<String, String> next) {
        Integer core  = effectiveInt(next, ExecutorTuningService.CORE_KEY);
        Integer max   = effectiveInt(next, ExecutorTuningService.MAX_KEY);
        Integer queue = effectiveInt(next, ExecutorTuningService.QUEUE_KEY);
        // Üçü de çözülemiyorsa (properties yüklenmeyen slice bağlamı) kural uygulanamaz; atla.
        if (core == null || max == null || queue == null) return;
        String problem = ExecutorTuningService.validate(core, max, queue);
        if (problem != null) throw new IllegalArgumentException(Msg.t("Görev havuzu: ", "Task pool: ") + problem);
    }

    /** Override → Environment sırasıyla tam sayı; hiçbiri yoksa ya da sayı değilse null. */
    private Integer effectiveInt(Map<String, String> next, String key) {
        String v = next.get(key);
        if (v == null) v = environment.getProperty(key);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return null; }
    }

    private static java.util.Set<String> diffKeys(Map<String, String> a, Map<String, String> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        keys.removeIf(k -> java.util.Objects.equals(a.get(k), b.get(k)));
        return keys;
    }

    private void publish(java.util.Set<String> keys, String source) {
        if (publisher == null) return;
        try {
            publisher.publishEvent(new AppSettingsChangedEvent(java.util.Set.copyOf(keys), source));
        } catch (Exception e) {
            // Dinleyici hatası kaydı GERİ ALMAMALI: değer DB'de ve cache'te; uygulanamayan tüketici loglar.
            log.warn("AppSettingsChangedEvent listener failed ({}): {}", source, e.toString());
        }
    }

    private void validate(AppSettingsCatalog.Setting s, String val) {
        if (val == null || val.isEmpty()) return; // boş = override kaldır (varsayılana dön)
        switch (s.type()) {
            case INT -> {
                try { Integer.parseInt(val); }
                catch (Exception e) { throw new IllegalArgumentException(s.key() + Msg.t(": tam sayı olmalı", ": must be a whole number")); }
            }
            case DOUBLE -> {
                try { Double.parseDouble(val); }
                catch (Exception e) { throw new IllegalArgumentException(s.key() + Msg.t(": sayı olmalı", ": must be a number")); }
            }
            case BOOL -> {
                if (!val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false"))
                    throw new IllegalArgumentException(s.key() + Msg.t(": true/false olmalı", ": must be true/false"));
            }
            case ENUM -> {
                if (!s.enumOptions().contains(val))
                    throw new IllegalArgumentException(s.key() + Msg.t(": geçersiz değer (", ": invalid value (") + s.enumOptions() + ")");
            }
            default -> { /* STRING, CSV — serbest */ }
        }
    }

    /** logging.level.* override'larını Logback'e canlı uygular (com.sitemonitor + .mail). */
    private void applyLogLevel() {
        try {
            String lvl = resolve(LOG_LEVEL_KEY);
            if (lvl != null && !lvl.isBlank()) {
                ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.sitemonitor"))
                        .setLevel(ch.qos.logback.classic.Level.valueOf(lvl.trim().toUpperCase()));
            }
            // Mail logger: override varsa uygula, yoksa null → parent'tan (com.sitemonitor) miras al.
            // Böylece ekrandan mail TRACE'i aç/kapat uygulama-geneli TRACE'e geçmeden canlı yapılır.
            String mailLvl = resolve(MAIL_LOG_LEVEL_KEY);
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.sitemonitor.mail"))
                    .setLevel((mailLvl == null || mailLvl.isBlank())
                            ? null
                            : ch.qos.logback.classic.Level.valueOf(mailLvl.trim().toUpperCase()));
        } catch (Exception e) {
            log.debug("applyLogLevel skipped: {}", e.getMessage());
        }
    }

    private String now() { return ISO.format(Instant.now()); }
}
