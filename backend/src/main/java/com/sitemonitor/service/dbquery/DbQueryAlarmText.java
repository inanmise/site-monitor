package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Veritabanı sorgu izlemesinin alarm / kontrol satırı METİNLERİ ve kural sonucu biçimi (2026-10-31, tarama + alarm katmanı).
 * Saf, durumsuz; asla fırlatmaz.
 *
 * <p><b>Kural sonucu JSON'u</b> ({@code dbquery_checks.rule_results_json}) API biçimindedir (sözleşme §4 RuleResult):
 * {@code [{id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared}]}. Motor bir sonraki
 * çalıştırmanın değişim kuralı için önceki zamanlanmış satırın {@code measure} değerini {@code id} ile okur — biçim
 * kalıcıdır, yalnız EKLENEBİLİR.
 *
 * <p><b>Hata kodu öneki.</b> Tarama öğesinin hata metni {@code [DB_TIMEOUT] …} biçimindedir: toplu ağ kesintisi kuralı
 * ({@code MonitoringOutageService.classifyError}) hatayı sürücü metninden değil KODDAN sınıflandırır — bağlantı / DNS / zaman
 * aşımı ağ sınıfı, kimlik / SQL / güvenlik reddi yapılandırma sınıfı.
 */
public final class DbQueryAlarmText {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Özet metninin üst sınırı (alarm ayrıntısı / tarama öğesi detayı). */
    public static final int SUMMARY_MAX = 500;

    private DbQueryAlarmText() {}

    /** Kural sonuçlarını API / JSON biçimindeki haritalara çevirir (sıra korunur). */
    public static List<Map<String, Object>> toApi(List<DbQueryEvaluator.RuleResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (results == null) return out;
        for (DbQueryEvaluator.RuleResult r : results) {
            if (r == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("label", r.label());
            m.put("kind", r.kind());
            m.put("actual", r.actual());
            m.put("expected", r.expected());
            m.put("level", r.level());
            m.put("passed", r.passed());
            m.put("evaluated", r.evaluated());
            m.put("note", r.note());
            m.put("measure", r.measure());
            m.put("compared", r.compared());
            out.add(m);
        }
        return out;
    }

    /** Kontrol satırının {@code rule_results_json}'u; boş liste → "[]", yazılamazsa null. */
    public static String toJson(List<DbQueryEvaluator.RuleResult> results) {
        try {
            return JSON.writeValueAsString(toApi(results));
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code rule_results_json}'u gevşek okur (bozuk → boş liste). */
    public static List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> l = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** İhlal edilen (değerlendirilmiş, sağlanmamış) kurallar. */
    public static List<Map<String, Object>> breached(List<Map<String, Object>> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (results == null) return out;
        for (Map<String, Object> r : results) {
            if (r == null) continue;
            if (Boolean.FALSE.equals(r.get("evaluated"))) continue;
            if (Boolean.FALSE.equals(r.get("passed"))) out.add(r);
        }
        return out;
    }

    /**
     * İhlal özeti — "Bekleyen sipariş: gelen 1.250 · beklenen ≤ 100 (kritik)"; birden çok kural " · " ile, ≤
     * {@link #SUMMARY_MAX}. İhlal yoksa "kurallar sağlandı". Tarama öğesinin detayı ve alarm iletisi bunu kullanır.
     */
    public static String breachSummary(List<Map<String, Object>> results) {
        List<Map<String, Object>> b = breached(results);
        if (b.isEmpty()) return "kurallar sağlandı";
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> r : b) {
            String label = text(r.get("label"));
            if (label == null) label = kindLabel(text(r.get("kind")));
            StringBuilder s = new StringBuilder(label);
            String actual = text(r.get("actual"));
            String expected = text(r.get("expected"));
            if (actual != null) s.append(": gelen ").append(actual);
            if (expected != null) s.append(actual != null ? " · beklenen " : ": beklenen ").append(expected);
            String lvl = levelText(text(r.get("level")));
            if (lvl != null) s.append(" (").append(lvl.toLowerCase(java.util.Locale.forLanguageTag("tr"))).append(')');
            parts.add(s.toString());
        }
        String joined = String.join(" · ", parts);
        return joined.length() > SUMMARY_MAX ? joined.substring(0, SUMMARY_MAX - 1) + "…" : joined;
    }

    /** Kural türü etiketi (kural adı boşsa). */
    public static String kindLabel(String kind) {
        if (kind == null) return "Kural";
        return switch (kind) {
            case "ROW_COUNT" -> "Satır sayısı";
            case "COLUMN_VALUE" -> "Kolon değeri";
            case "TEXT_MATCH" -> "Metin eşleşmesi";
            case "CHANGE" -> "Değişim";
            default -> "Kural";
        };
    }

    /**
     * Kural ihlalinin seviye METNİ — bilinçli olarak büyük harfli seviye sözcüğü ("KRİTİK" / "UYARI") DEĞİL: aynı e-postada
     * alarmın kendi seviye rozeti var; kural tablosundaki satırlar onunla çelişmesin (ALL birleşiminde alarm en düşük
     * seviyeyle açılır). null = ihlal yok.
     */
    public static String levelText(String level) {
        if (level == null) return null;
        return switch (level) {
            case "CRITICAL" -> "Kritik eşik";
            case "WARNING" -> "Uyarı eşiği";
            default -> null;
        };
    }

    /** Kural satırının durum metni (mail kural tablosu "Seviye" kolonu). */
    public static String ruleStateText(Map<String, Object> r) {
        if (r == null) return "—";
        if (Boolean.FALSE.equals(r.get("evaluated"))) return "Değerlendirilemedi";
        if (Boolean.TRUE.equals(r.get("passed"))) return "Sağlandı";
        String lvl = levelText(text(r.get("level")));
        return lvl != null ? lvl + " ihlali" : "İhlal";
    }

    /** Veritabanı türü etiketi. */
    public static String dbTypeLabel(String dbType) {
        if (dbType == null) return null;
        return switch (dbType.toUpperCase(java.util.Locale.ROOT)) {
            case "ORACLE" -> "Oracle";
            case "MSSQL" -> "Microsoft SQL Server";
            case "POSTGRESQL" -> "PostgreSQL";
            default -> dbType;
        };
    }

    /** Hata kodunun Türkçe etiketi (mail "Hata türü" satırı). Bilinmeyen kod → kodun kendisi. */
    public static String failureLabel(String code) {
        if (code == null || code.isBlank()) return null;
        return switch (code) {
            case "DB_UNREACHABLE" -> "Veritabanı sunucusuna ulaşılamadı";
            case "DB_DNS" -> "Sunucu adı çözümlenemedi";
            case "DB_TIMEOUT" -> "Zaman aşımı";
            case "DB_AUTH" -> "Kimlik doğrulama hatası";
            case "DB_SQL_ERROR" -> "Sorgu veritabanında hata verdi";
            case "DB_DRIVER" -> "Sürücü / protokol hatası";
            case "DB_BLOCKED" -> "Hedef güvenlik politikasıyla engellendi";
            case "DB_GUARD_REJECTED" -> "Sorgu güvenlik denetiminden geçmedi";
            case "DB_PROFILE_MISSING" -> "Bağlantı tanımı bulunamadı";
            case "DB_PROFILE_OTHER_TEAM" -> "Bağlantı başka bir takıma ait";
            case "DB_SECRET_UNREADABLE" -> "Bağlantı parolası çözülemedi";
            case "DB_BUSY" -> "Eşzamanlı çalıştırma sınırı dolu";
            case "DB_UNKNOWN" -> "Bilinmeyen hata";
            default -> code;
        };
    }

    /**
     * Tarama öğesinin hata metni: {@code [KOD] maskeli hata}. Kod yoksa DB_UNKNOWN, hata yoksa "sorgu çalıştırılamadı".
     * {@link #codeOf} ile kod yeniden okunur.
     */
    public static String itemError(String failureReason, String error) {
        String e = error == null || error.isBlank() ? "sorgu çalıştırılamadı" : error.trim();
        // Kod HER ZAMAN yazılır (yoksa DB_UNKNOWN): sınıf sürücü metninden tahmin edilmesin ({@link #outageClassOf}).
        String code = failureReason == null || failureReason.isBlank() ? "DB_UNKNOWN" : failureReason.trim();
        return "[" + code + "] " + e;
    }

    /** {@link #itemError} önekindeki kod (büyük harf) ya da null. */
    public static String codeOf(String itemError) {
        if (itemError == null || itemError.length() < 5 || itemError.charAt(0) != '[') return null;
        int close = itemError.indexOf(']');
        if (close < 4 || close > 40) return null;
        String code = itemError.substring(1, close).trim().toUpperCase(java.util.Locale.ROOT);
        return code.startsWith("DB_") ? code : null;
    }

    /**
     * Hata kodunun kesinti sınıfı ({@code MonitoringOutageService} sabitleriyle aynı adlar). Bilinçli olarak HİÇBİR kod
     * NETWORK / DNS değildir: toplu kesinti bastırması ("izlemelerin yarısı ağ hatasıyla düştüyse bu bizim ağımızdır, tek tek
     * alarm açma") sorgu izlemesinde YANLIŞ sonuç verir — izlemeler birkaç veritabanı sunucusunda kümelenir, tek bir
     * veritabanının çökmesi izlemelerin çoğunu aynı anda düşürür ve tam yakalanması gereken kesinti bastırılırdı. Bağlantı /
     * zaman aşımı / ad çözümleme → OTHER (hedef tarafı), diğer her kod (kimlik, SQL, sürücü, güvenlik reddi, bağlantı tanımı)
     * → CONFIG. Kod değilse null.
     */
    public static String outageClassOf(String code) {
        if (code == null) return null;
        return switch (code) {
            case "DB_UNREACHABLE", "DB_TIMEOUT", "DB_DNS", "DB_UNKNOWN" -> "OTHER";
            default -> code.startsWith("DB_") ? "CONFIG" : null;
        };
    }

    private static String text(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
