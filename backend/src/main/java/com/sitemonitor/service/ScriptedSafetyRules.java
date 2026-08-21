package com.sitemonitor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentetik izleme script'i GÜVENLİK denetimi — kaydetme anında (L1).
 *
 * <p><b>Tehdit.</b> Bu script'ler ÜRETİM sistemlerine istek atıyor. {@code --vus 1 --iterations 1}
 * yalnız <i>iterasyon</i> sayısını sabitler; tek iterasyonun İÇİNDE
 * {@code for (let i=0;i<10000;i++) http.get(URL)} yazmak 2026-08-21'e kadar tamamen serbestti.
 * Yani yük testi yolu kapalıydı ama yük <i>üretme</i> yolu açıktı. Aynı boşluk CPU (sonsuz döngü)
 * ve bellek (devasa allocation) için de geçerliydi ve üretim TEK pod'da koştuğu için bir OOM
 * doğrudan kesinti demek.
 *
 * <p><b>BLOCK / WARN ayrımı {@link ScriptedTemplateRules} ile aynı felsefeyi izler:</b> yalnız
 * yanlış-pozitif riski SIFIR olan kalıplar engeller. Statik analiz {@code for (i=0;i<n;i++)}
 * gibi değişken sınırlı bir döngüyü KANITLAYAMAZ; onu engellemek meşru senaryoları kilitlerdi.
 * Kanıtlanamayanı çalışma anındaki sert tavanlar kapatır (L2: {@code --rps}, {@code GOMAXPROCS},
 * {@code GOMEMLIMIT}, koşum başına istek tavanı) ve koşum sonrası anomali guard'ı (L3) yakalar.
 * Üç katman birlikte anlamlıdır; bu sınıf tek başına bir güvenlik sınırı DEĞİLDİR.
 *
 * <p>Saf/statik: Spring bağımlılığı yok → sıfır bağlamla test edilir.
 */
public final class ScriptedSafetyRules {

    private ScriptedSafetyRules() {}

    /** Tek koşumda statik olarak sayılabilen istek sayısı bu tavanı aşarsa kayıt engellenir. */
    static final int MAX_STATIC_REQUESTS = 50;
    /** Sabit sınırlı bir döngü bu kadar dönüyorsa ve içinde istek varsa: yük üretimi. */
    static final int MAX_LOOP_BOUND = 50;
    /** Bu sınırın üstünde, içinde `sleep` OLMAYAN döngü "ani istek seli" sayılır. */
    static final int BURST_LOOP_BOUND = 10;
    /** Bu boyutun üstünde tek seferlik ayırma bellek patlaması sayılır. */
    static final long MAX_ALLOCATION = 1_000_000L;

    private static final Pattern HTTP_CALL =
            Pattern.compile("\\bhttp\\s*\\.\\s*(get|post|put|del|patch|head|options|request|batch|asyncRequest)\\s*\\(");
    /** `for (let i = 0; i < 5000; i++)` — sabit üst sınırı yakalar (grup 1). */
    private static final Pattern FOR_LITERAL_BOUND =
            Pattern.compile("for\\s*\\([^;]*;[^;<>]*[<>]=?\\s*(\\d+)\\s*;[^)]*\\)");
    /** Sonsuz döngü: `while (true)`, `while(1)`, `for (;;)`, `do { } while (true)`. */
    private static final Pattern INFINITE_LOOP =
            Pattern.compile("(?:while\\s*\\(\\s*(?:true|1)\\s*\\))|(?:for\\s*\\(\\s*;\\s*;\\s*\\))");
    private static final Pattern SLEEP_CALL = Pattern.compile("\\bsleep\\s*\\(");
    /** `new Array(1e8)`, `Array(50000000)`, `'x'.repeat(9999999)`, `Buffer.alloc(...)`. */
    private static final Pattern BIG_ALLOC = Pattern.compile(
            "(?:new\\s+Array\\s*\\(|\\bArray\\s*\\(|\\.repeat\\s*\\(|\\.padStart\\s*\\(|\\.padEnd\\s*\\()"
            + "\\s*(\\d+(?:\\.\\d+)?(?:[eE]\\+?\\d+)?)");
    /** `http.batch([ ... ])` — dizideki eleman sayısı kabaca virgülle sayılır. */
    private static final Pattern BATCH_CALL = Pattern.compile("http\\s*\\.\\s*batch\\s*\\(");

    /** {@link ScriptedTemplateRules.TemplateDiagnostics} ile aynı sözleşme. */
    public record SafetyDiagnostics(String blocking, List<String> warnings) {
        public boolean blocked() { return blocking != null; }
    }

    /**
     * Script'i güvenlik açısından denetler.
     *
     * @param script ham script (yorumlar burada temizlenir)
     * @return ilk engelleyici bulgu + engellemeyen uyarılar
     */
    public static SafetyDiagnostics check(String script) {
        List<String> warnings = new ArrayList<>();
        if (script == null || script.isBlank()) return new SafetyDiagnostics(null, warnings);

        // Yorumlar önce silinir: yorum içindeki örnek kod ("// while(true) YAPMAYIN") kaydı
        // engellemesin. Aynı temizlik ScriptedCheckerService'in timeout denetimlerinde de yapılıyor.
        String src = ScriptedCheckerService.stripComments(script);

        // ── BLOCK 1: sonsuz döngü ────────────────────────────────────────────────────────────
        // Yanlış-pozitif riski sıfır: k6'nın default fonksiyonu DÖNMEK ZORUNDA. Sonsuz döngü
        // içinde istek varsa hedefe kesintisiz sel, istek yoksa çekirdeği doyuran CPU yanması —
        // ikisi de süreç bütçesi dolana kadar (180 sn) sürer ve tek pod'u etkiler.
        Matcher inf = INFINITE_LOOP.matcher(src);
        if (inf.find()) {
            boolean withRequest = HTTP_CALL.matcher(src).find();
            return block(withRequest
                    ? "Script sonsuz döngü (`" + inf.group().trim() + "`) içinde istek atıyor. Bu, hedefe "
                      + "kesintisiz istek seli demektir ve üretim sistemine zarar verir. Sentetik izleme "
                      + "bir senaryoyu BİR KEZ koşar; döngüyü kaldırın."
                    : "Script sonsuz döngü (`" + inf.group().trim() + "`) içeriyor. k6'nın default "
                      + "fonksiyonu dönmek zorundadır; bu script süreç bütçesi dolana kadar CPU yakar.",
                    warnings);
        }

        // ── BLOCK 2: sabit sınırlı AŞIRI döngü + istek ───────────────────────────────────────
        // Sınır literal olduğu için sayı KESİN biliniyor; tahmin yok.
        Matcher loop = FOR_LITERAL_BOUND.matcher(src);
        while (loop.find()) {
            long bound = parseLong(loop.group(1));
            if (bound <= MAX_LOOP_BOUND) continue;
            if (!loopBodyHasRequest(src, loop.end())) continue;
            return block("Döngü " + bound + " kez dönüyor ve içinde istek var (tavan: " + MAX_LOOP_BOUND
                    + "). Sentetik izleme bir senaryoyu doğrular, yük üretmez — bu kalıp üretim "
                    + "sistemine yük testi uygular.", warnings);
        }

        // ── BLOCK 3: statik istek sayısı tavanı ──────────────────────────────────────────────
        long requests = HTTP_CALL.matcher(src).results().count();
        if (requests > MAX_STATIC_REQUESTS) {
            return block(requests + " istek çağrısı var (tavan: " + MAX_STATIC_REQUESTS + "). Tek bir "
                    + "kontrol koşumunda bu kadar istek üretim sistemine yük bindirir; senaryoyu "
                    + "bölün ya da ayrı izlemeler tanımlayın.", warnings);
        }

        // ── BLOCK 4: devasa bellek ayırma ────────────────────────────────────────────────────
        // Üretim TEK pod; k6 alt sürecinin OOM'u pod'u götürür (GOMEMLIMIT yumuşak tavandır,
        // kesin kill değil) — bu yüzden kaynakta engelleniyor.
        Matcher alloc = BIG_ALLOC.matcher(src);
        while (alloc.find()) {
            long n = parseLong(alloc.group(1));
            if (n > MAX_ALLOCATION) {
                return block("Script tek seferde " + n + " elemanlık bir ayırma yapıyor (tavan: "
                        + MAX_ALLOCATION + "). Bu, izleme sürecinin belleğini şişirir ve aynı pod'daki "
                        + "diğer kontrolleri riske atar.", warnings);
            }
        }

        // ── BLOCK 5: sleep'siz BÜYÜK döngüde istek (ani istek seli) ──────────────────────────
        // Küçük döngü (≤10) BLOKLANMAZ: `for (const u of [a,b,c]) http.get(u)` tamamen meşrudur
        // ve onu engellemek yanlış-pozitif olurdu. Yalnız sınırı LİTERAL ve büyük olan döngüler.
        Matcher burst = FOR_LITERAL_BOUND.matcher(src);
        while (burst.find()) {
            long bound = parseLong(burst.group(1));
            if (bound <= BURST_LOOP_BOUND) continue;
            String body = loopBody(src, burst.end());
            if (body == null || !HTTP_CALL.matcher(body).find()) continue;
            if (SLEEP_CALL.matcher(body).find()) continue;
            return block("Döngü " + bound + " kez dönüyor, içinde istek var ve `sleep()` YOK: istekler "
                    + "aralıksız, tek seferde gider. Gerçek kullanıcı davranışını taklit etmek için "
                    + "döngüye `sleep(1)` ekleyin ya da tekrar sayısını düşürün.", warnings);
        }

        // ── WARN: kanıtlanamayan gri alan (L2/L3 kapatır) ───────────────────────────────────
        if (variableBoundLoopWithRequest(src)) {
            warnings.add("Döngü sınırı değişken ve içinde istek var — kaç istek atılacağı kayıt anında "
                    + "bilinemiyor. Koşumda istek/sn tavanı ve koşum başına istek tavanı uygulanır; "
                    + "tavan aşılırsa izleme otomatik devre dışı bırakılır.");
        }
        int batchSize = maxBatchSize(src);
        if (batchSize > BURST_LOOP_BOUND) {
            warnings.add("`http.batch` " + batchSize + " isteği AYNI ANDA gönderiyor — hedefe eşzamanlı "
                    + "yük bindirir. İstek/sn tavanı bunu yayar ama senaryoyu bölmek daha doğrudur.");
        }
        if (requests > MAX_STATIC_REQUESTS / 2) {
            warnings.add(requests + " istek çağrısı var. Tavan " + MAX_STATIC_REQUESTS
                    + "; senaryo büyümeye devam ederse kayıt engellenecek.");
        }

        return new SafetyDiagnostics(null, warnings);
    }

    /** Döngü gövdesinde istek var mı? Gövde çıkarılamıyorsa VAR SAYILMAZ (engelleme tahmine dayanmaz). */
    private static boolean loopBodyHasRequest(String src, int fromIndex) {
        String body = loopBody(src, fromIndex);
        return body != null && HTTP_CALL.matcher(body).find();
    }

    /**
     * {@code for (...)} başlığından sonraki gövdeyi döner.
     *
     * <p>Süslü parantezli gövdede parantezler DENGELENİR (iç içe blok/nesne literali doğru
     * kapanır); süssüz tek deyimli gövdede ilk {@code ;}'ye kadar okunur. Ayrıştırılamayan bir
     * şey görülürse {@code null} — çağıran o zaman engellemez. Bu bir JS ayrıştırıcısı değil;
     * yalnız ENGELLEME kararını dar tutmaya yeter.
     */
    static String loopBody(String src, int fromIndex) {
        int i = fromIndex;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        if (i >= src.length()) return null;

        if (src.charAt(i) != '{') {
            int end = src.indexOf(';', i);
            return end < 0 ? src.substring(i) : src.substring(i, end);
        }
        int depth = 0;
        for (int j = i; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(i + 1, j);
            }
        }
        return null;   // kapanmayan blok → ayrıştırılamadı
    }

    /** `for (... ; i < n ; ...)` / `while (i < n)` gibi sınırı literal OLMAYAN, istekli döngü. */
    private static boolean variableBoundLoopWithRequest(String src) {
        Matcher m = Pattern.compile("(?:for|while)\\s*\\(").matcher(src);
        while (m.find()) {
            int close = matchingParen(src, m.end() - 1);
            if (close < 0) continue;
            String header = src.substring(m.end(), close);
            if (FOR_LITERAL_BOUND.matcher(m.group() + header + ")").find()) continue;   // sınır literal
            if (header.contains(";;") || header.isBlank()) continue;                    // sonsuz: zaten bloklandı
            String body = loopBody(src, close + 1);
            if (body != null && HTTP_CALL.matcher(body).find()) return true;
        }
        return false;
    }

    /** En büyük `http.batch([...])` dizisinin kaba eleman sayısı (virgül sayımı). */
    private static int maxBatchSize(String src) {
        int max = 0;
        Matcher m = BATCH_CALL.matcher(src);
        while (m.find()) {
            int close = matchingParen(src, m.end() - 1);
            if (close < 0) continue;
            String arg = src.substring(m.end(), close);
            int commas = 0;
            int depth = 0;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '[' || c == '{' || c == '(') depth++;
                else if (c == ']' || c == '}' || c == ')') depth--;
                else if (c == ',' && depth == 1) commas++;   // yalnız dış dizinin virgülleri
            }
            if (commas > 0) max = Math.max(max, commas + 1);
        }
        return max;
    }

    /** {@code openIndex} konumundaki '(' için eşleşen ')' — bulunamazsa -1. */
    private static int matchingParen(String src, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** `1e8` gibi üstel gösterimi de okur; taşarsa {@link Long#MAX_VALUE}. */
    private static long parseLong(String raw) {
        try {
            double d = Double.parseDouble(raw);
            return d >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) d;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static SafetyDiagnostics block(String msg, List<String> warnings) {
        return new SafetyDiagnostics(msg, warnings);
    }
}
