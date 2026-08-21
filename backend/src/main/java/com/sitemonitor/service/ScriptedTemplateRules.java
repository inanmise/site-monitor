package com.sitemonitor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Şablon yazım kuralları — HER şablon kaydında çalışır.
 *
 * <p><b>Nereden geldi.</b> 11 yerleşik şablon 2026-08-20'ye kadar frontend'de statik bir dizideydi
 * ve yazım kuralları {@code scriptedTemplates.test.js} ile DERLEME ANINDA korunuyordu. Şablonlar
 * DB'ye taşınıp kullanıcı katkısına açılınca o kapı yetmez oldu: katalog testi yalnız küratörlü
 * seti korur, kullanıcının yazdığı şablonu korumaz. Bu sınıf aynı kuralları ÇALIŞTIRMA ANINA
 * taşır ki kütüphaneye giren her şablon aynı garantileri taşısın.
 *
 * <p><b>BLOCK / WARN ayrımı bilinçlidir.</b> Yalnız yanlış-pozitif riski SIFIR olan ve script'i
 * gerçekten çalışamaz kılan kurallar engeller; regex tabanlı, bağlam bilmeyen kontroller uyarır.
 * Sebebi tek cümleyle: kullanıcıyı kendi kütüphanesinden kilitleyen bir yanlış-pozitif, kaçırılan
 * bir uyarıdan daha pahalıdır — ilki aracı terk ettirir, ikincisi düzeltilir.
 *
 * <p>Saf/statik: Spring bağımlılığı yok, k6 sürümü parametre olarak gelir → sıfır bağlamla test edilir.
 */
public final class ScriptedTemplateRules {

    private ScriptedTemplateRules() {}

    /** k6 bu sürümden İTİBAREN modern sözdizimini (?. ?? spread) ayrıştırabiliyor. */
    private static final int[] MODERN_SYNTAX_SINCE = { 0, 53, 0 };

    private static final Pattern VERSION   = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern SLUG      = Pattern.compile("^[a-z][a-z0-9._-]*$");
    private static final Pattern REQUEST   = Pattern.compile("http\\.(get|post|put|del|patch)\\(");
    private static final Pattern TIMEOUT   = Pattern.compile("timeout:\\s*'(\\d+)s'");
    private static final Pattern OBJ_SPREAD = Pattern.compile("\\{\\s*\\.\\.\\.");

    /** Aynı sözleşme: {@code ScriptedCheckerService.ScriptDiagnostics}. */
    public record TemplateDiagnostics(String blocking, List<String> warnings) {
        public boolean blocked() { return blocking != null; }
    }

    /**
     * @param builtinKey seed edilen küratörlü şablonun anahtarı; kullanıcı şablonunda null
     * @param envDefs    env TANIMLARI — {@code value} anahtarı taşıyan girdi REDDEDİLİR
     * @param k6Version  dağıtımdaki k6 sürümü ("v0.49.0"); okunamıyorsa null
     */
    public static TemplateDiagnostics check(String builtinKey, String name, String description,
                                            String whenToUse, String script,
                                            List<Map<String, Object>> envDefs, String k6Version) {
        List<String> warnings = new ArrayList<>();

        // ── BLOCK: script'i çalışamaz kılan, yanlış-pozitifi olmayan kurallar ──────────────
        if (isBlank(name))   return block("Şablon adı zorunludur.", warnings);
        if (isBlank(script)) return block("Şablon script'i boş olamaz.", warnings);
        if (!script.contains("export default function")) {
            return block("Script'te `export default function` yok — k6 bu script'i hiç çalıştıramaz.", warnings);
        }
        if (builtinKey != null && !SLUG.matcher(builtinKey).matches()) {
            // KRİTİK: seçici `tpl:<token>` üretiyor ve token ya builtinKey ya sayısal id.
            // Tamamen rakamdan oluşan bir builtinKey iki dalı çakıştırır ve eski monitörler
            // yanlış şablona çözülür. Slug şartı iki dalı provably ayrık tutar.
            return block("Yerleşik şablon anahtarı harfle başlamalı ve yalnız [a-z0-9._-] içermeli: " + builtinKey, warnings);
        }

        List<String> envNames = new ArrayList<>();
        if (envDefs != null) {
            for (Map<String, Object> e : envDefs) {
                if (e == null) continue;
                if (e.containsKey("value")) {
                    // Sessizce ayıklamak yerine GÜRÜLTÜLÜ reddet: istemci hatası, sızmış bir
                    // kimlik bilgisiyle değil ilk testte görünsün.
                    return block("Şablonlar gizli değer taşıyamaz; yalnız ortam değişkeni TANIMI "
                            + "(ad/açıklama/örnek) kaydedilir.", warnings);
                }
                Object n = e.get("name");
                if (n != null && !n.toString().isBlank()) envNames.add(n.toString().trim());
            }
        }

        // ── Sözdizimi: dağıtımdaki motor ayrıştıramıyorsa ENGELLE ─────────────────────────
        String modern = modernSyntaxHit(script);
        if (modern != null) {
            String level = syntaxLevel(k6Version);
            String msg = "Script `" + modern + "` içeriyor; dağıtımdaki k6 (" + (k6Version == null ? "sürüm okunamadı" : k6Version)
                    + ") gömülü Babel 6 ile bunu AYRIŞTIRAMAZ. Klasik `||`/`&&` ve `Object.assign` kullanın.";
            if ("legacy".equals(level)) return block(msg, warnings);
            // Sürüm modern ya da okunamıyor → bilgisizlik üzerine BLOKLAMA yok.
            warnings.add(msg);
        }

        // ── WARN: bağlam bilmeyen, yanlış-pozitifi olan kurallar ─────────────────────────
        if (isBlank(description)) warnings.add("Açıklama boş — şablonu seçen kişi ne işe yaradığını göremez.");
        if (isBlank(whenToUse))   warnings.add("\"Ne zaman kullanılır\" boş — seçicide en çok bakılan alan budur.");

        // __ENV denetimini YENİDEN YAZMA: ScriptedCheckerService'inki zaten bu işi yapıyor ve
        // Javadoc'unda "ASLA engellemez (regex tabanlı, string literal'de yanlış-pozitif verir)"
        // diye belgelenmiş bir karar taşıyor. Burada bloklamak o kararla çelişirdi.
        warnings.addAll(ScriptedCheckerService.auditEnvReferences(script, envNames));

        if (Pattern.compile("(?s).*\\bvus\\s*:.*").matcher(script).matches()
                || Pattern.compile("(?s).*\\biterations\\s*:.*").matcher(script).matches()
                || Pattern.compile("(?s).*\\bstages\\s*:.*").matcher(script).matches()) {
            warnings.add("`vus`/`iterations`/`stages` tanımlanmış — motor her kontrolü "
                    + "`--vus 1 --iterations 1` ile koşar ve bu ayarları ezer (script çalışır, ayar ölüdür).");
        }

        long requests = REQUEST.matcher(script).results().count();
        long timeouts = TIMEOUT.matcher(script).results().count();
        if (requests > timeouts) {
            // Paylaşılan bir params nesnesi (const P = { timeout: '20s' }) beş istekte
            // kullanıldığında bu sayım yanlış-pozitif verir — o yüzden uyarı, engel değil.
            warnings.add(requests + " istek var ama " + timeouts + " açık `timeout` bulundu. "
                    + "Timeout'suz istek k6'nın 60 sn varsayılanına düşer ve süreç zaman aşımından "
                    + "önce düşemez; ekranda sebepsiz \"Süre aşımı\" kalır.");
        }
        Matcher to = TIMEOUT.matcher(script);
        while (to.find()) {
            if (Integer.parseInt(to.group(1)) >= 60) {
                warnings.add("`timeout: '" + to.group(1) + "s'` — k6'nın kendi varsayılanı 60 sn; "
                        + "istek timeout'u süreç bütçesinden KISA olmalı.");
                break;
            }
        }

        return new TemplateDiagnostics(null, warnings);
    }

    /** Script'te k6 0.49'un ayrıştıramadığı bir sözdizimi var mı? Bulunan ilk kalıbı döner. */
    static String modernSyntaxHit(String script) {
        if (script == null) return null;
        if (script.contains("?.")) return "?.";
        if (script.contains("??")) return "??";
        if (OBJ_SPREAD.matcher(script).find()) return "{...}";
        return null;
    }

    /**
     * "v0.49.0" → {@code legacy} (modern sözdizimini ayrıştıramaz), "v0.53.0"+ → {@code modern}.
     * Ayrıştırılamayan/eksik sürüm → {@code unknown}: bilgisizlik üzerine BLOKLAMA yapılmaz.
     * Eşik frontend'deki {@code K6_MODERN_SYNTAX_SINCE} ile aynı olmalıdır.
     */
    static String syntaxLevel(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        Matcher m = VERSION.matcher(raw);
        if (!m.find()) return "unknown";
        int[] v = { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) };
        for (int i = 0; i < 3; i++) {
            if (v[i] < MODERN_SYNTAX_SINCE[i]) return "legacy";
            if (v[i] > MODERN_SYNTAX_SINCE[i]) return "modern";
        }
        return "modern";   // tam eşik sürümü modern sayılır
    }

    private static TemplateDiagnostics block(String msg, List<String> warnings) {
        return new TemplateDiagnostics(msg, warnings);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Küçük yardımcı — çağıranların Locale'siz toLowerCase kullanmaması için. */
    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
