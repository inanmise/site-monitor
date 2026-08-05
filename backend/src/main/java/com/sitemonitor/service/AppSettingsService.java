package com.sitemonitor.service;

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
                this.overrides = next;
                applyLogLevel();
                log.info("AppSettings cache refreshed from DB (updated by another instance): {} override(s)", next.size());
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
        for (AppSettingsCatalog.Setting s : AppSettingsCatalog.ALL) {
            String def = environment.getProperty(s.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("group", s.group());
            m.put("type", s.type().name());
            m.put("value", overrides.getOrDefault(s.key(), def));
            m.put("default", def);
            m.put("overridden", overrides.containsKey(s.key()));
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

        Map<String, String> next = new HashMap<>(overrides);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String key = e.getKey();
            AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(key);
            if (s == null) throw new IllegalArgumentException("Bilinmeyen ayar: " + key);
            String val = e.getValue() == null ? null : e.getValue().toString().trim();
            validate(s, val);
            AppSetting row = repo.findBySettingKey(key).orElseGet(() -> new AppSetting(key, null, null, null));
            row.setSettingKey(key);
            row.setValue((val == null || val.isEmpty()) ? null : val);
            row.setUpdatedAt(now());
            row.setUpdatedBy(actor);
            repo.save(row);
            if (val == null || val.isEmpty()) next.remove(key);
            else next.put(key, val);
        }
        this.overrides = Map.copyOf(next); // cache ANINDA tazelenir → tüketiciler yeni değeri okur
        applyLogLevel();
    }

    private void validate(AppSettingsCatalog.Setting s, String val) {
        if (val == null || val.isEmpty()) return; // boş = override kaldır (varsayılana dön)
        switch (s.type()) {
            case INT -> {
                try { Integer.parseInt(val); }
                catch (Exception e) { throw new IllegalArgumentException(s.key() + ": tam sayı olmalı"); }
            }
            case DOUBLE -> {
                try { Double.parseDouble(val); }
                catch (Exception e) { throw new IllegalArgumentException(s.key() + ": sayı olmalı"); }
            }
            case BOOL -> {
                if (!val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false"))
                    throw new IllegalArgumentException(s.key() + ": true/false olmalı");
            }
            case ENUM -> {
                if (!s.enumOptions().contains(val))
                    throw new IllegalArgumentException(s.key() + ": geçersiz değer (" + s.enumOptions() + ")");
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
