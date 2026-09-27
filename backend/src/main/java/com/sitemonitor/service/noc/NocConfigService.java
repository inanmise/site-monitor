package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocSettings;
import com.sitemonitor.repository.NocSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 7/24 İzleme Ekibi yapılandırması — tür anahtarları, en düşük seviye, çözüldü e-postası, arama talimatı.
 * Tek satır ({@link NocSettings}); satır yoksa varsayılanlar (tüm türler açık, CRITICAL, çözüldü açık).
 */
@Service
@RequiredArgsConstructor
public class NocConfigService {

    public static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");
    public static final String DEFAULT_MIN_LEVEL = "CRITICAL";
    /** Arama talimatı tavanı — e-postada okunur kalsın, tabloyu şişirmesin. */
    public static final int MAX_INSTRUCTIONS = 2000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocSettingsRepository repo;

    /** Değişmez yapılandırma görüntüsü. */
    public record Config(Set<NocType> disabledTypes, String minLevel, boolean sendResolve, String callInstructions,
                         String updatedAt, String updatedByName) {

        public boolean typeEnabled(NocType t) {
            return t != null && !disabledTypes.contains(t);
        }

        /** {@code level} en düşük seviyeye ULAŞIYOR mu (WARNING &lt; HIGH &lt; CRITICAL; bilinmeyen = WARNING). */
        public boolean meetsMinLevel(String level) {
            return rank(level) >= rank(minLevel);
        }

        public List<String> disabledTypeKeys() {
            return disabledTypes.stream().map(Enum::name).sorted().toList();
        }
    }

    public static int rank(String level) {
        if (level == null) return 1;
        return switch (level.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }

    public Config get() {
        return toConfig(repo.findById(NocSettings.SINGLETON_ID).orElse(null));
    }

    static Config toConfig(NocSettings s) {
        if (s == null) return new Config(Set.of(), DEFAULT_MIN_LEVEL, true, "", null, null);
        Set<NocType> disabled = EnumSet.noneOf(NocType.class);
        if (s.getDisabledTypes() != null) {
            for (String part : s.getDisabledTypes().split(",")) {
                NocType t = NocType.parse(part);
                if (t != null) disabled.add(t);
            }
        }
        String lvl = normalizeLevel(s.getMinLevel());
        return new Config(Set.copyOf(disabled), lvl == null ? DEFAULT_MIN_LEVEL : lvl,
                !Boolean.FALSE.equals(s.getSendResolve()),
                s.getCallInstructions() == null ? "" : s.getCallInstructions(),
                s.getUpdatedAt(), s.getUpdatedByName());
    }

    static String normalizeLevel(Object raw) {
        if (raw == null) return null;
        String v = raw.toString().trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(v) ? v : null;
    }

    /**
     * Gövdeyi (camelCase: enabledTypes, minLevel, sendResolve, callInstructions) uygular. Gelmeyen alan korunur.
     * Geçersiz seviye / tür anahtarı / aşırı uzun talimat → 400 (IllegalArgumentException).
     */
    public Config save(Map<String, Object> body, String actor, String actorName) {
        NocSettings s = repo.findById(NocSettings.SINGLETON_ID).orElseGet(NocSettings::new);
        s.setId(NocSettings.SINGLETON_ID);
        Config current = toConfig(s);   // boş (yeni) satır da varsayılanlara çözülür

        if (body.containsKey("enabledTypes")) {
            Object raw = body.get("enabledTypes");
            if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("enabledTypes bir nesne olmalı: { PING: true, … }");
            Set<NocType> disabled = EnumSet.noneOf(NocType.class);
            disabled.addAll(current.disabledTypes());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                NocType t = NocType.parse(String.valueOf(e.getKey()));
                if (t == null) throw new IllegalArgumentException("Bilinmeyen izleme türü: " + e.getKey());
                if (!(e.getValue() instanceof Boolean on)) throw new IllegalArgumentException("Tür anahtarı true/false olmalı: " + e.getKey());
                if (on) disabled.remove(t); else disabled.add(t);
            }
            s.setDisabledTypes(disabled.isEmpty() ? null
                    : String.join(",", disabled.stream().map(Enum::name).sorted().toList()));
        }
        if (body.containsKey("minLevel")) {
            String lvl = normalizeLevel(body.get("minLevel"));
            if (lvl == null) throw new IllegalArgumentException("minLevel WARNING, HIGH ya da CRITICAL olmalı");
            s.setMinLevel(lvl);
        }
        if (body.containsKey("sendResolve")) {
            if (!(body.get("sendResolve") instanceof Boolean b)) throw new IllegalArgumentException("sendResolve true/false olmalı");
            s.setSendResolve(b);
        }
        if (body.containsKey("callInstructions")) {
            Object raw = body.get("callInstructions");
            String v = raw == null ? "" : raw.toString().replace("\r\n", "\n").strip();
            if (v.length() > MAX_INSTRUCTIONS)
                throw new IllegalArgumentException("Arama talimatı en fazla " + MAX_INSTRUCTIONS + " karakter olabilir");
            s.setCallInstructions(v.isEmpty() ? null : v);
        }
        s.setUpdatedAt(ISO.format(Instant.now()));
        s.setUpdatedBy(actor);
        s.setUpdatedByName(actorName);
        return toConfig(repo.save(s));
    }

    /** API biçimi (snake_case) — sözleşme: enabled_types on türün TAMAMINI taşır. */
    public static Map<String, Object> toDto(Config c) {
        Map<String, Boolean> types = new LinkedHashMap<>();
        for (NocType t : NocType.values()) types.put(t.name(), c.typeEnabled(t));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled_types", types);
        m.put("min_level", c.minLevel());
        m.put("send_resolve", c.sendResolve());
        m.put("call_instructions", c.callInstructions());
        m.put("updated_at", c.updatedAt());
        m.put("updated_by_name", c.updatedByName());
        return m;
    }
}
