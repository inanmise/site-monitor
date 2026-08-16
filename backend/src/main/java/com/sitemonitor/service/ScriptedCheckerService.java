package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Senaryo İzleme motoru — bir k6 scriptini kısa ömürlü, sıkı sandboxlu bir ALT SÜREÇ olarak çalıştırır
 * (Grafana Synthetic Monitoring deseni). k6 kütüphane olarak GÖMÜLMEZ; imaja eklenmiş binary çağrılır.
 *
 * <p>Güvenlik: script geçici dosyaya yazılır (env değişkenleri yalnız süreç ortamına); {@code --vus 1
 * --iterations 1} ile tek iterasyona zorlanır; {@link SsrfGuard#blacklistCidrs()} → {@code --blacklist-ip}
 * (iç ağ/loopback/metadata engellenir); timeout'ta SIGTERM→SIGKILL (zombie yok); temp dosyalar her durumda silinir;
 * secret env DEĞERLERİ çıktıda {@link SecretMask#maskValues} ile maskelenir. Eşzamanlılık {@link Semaphore} ile
 * sınırlıdır (diğer sweep'leri bloklamaz) ve Micrometer gauge'ları ile yayınlanır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptedCheckerService {

    private final SsrfGuard ssrfGuard;
    private final SecretCipher cipher;
    private final AppSettingsService appSettings;
    private final MeterRegistry registry;
    private final ProxySettings proxySettings;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int ABS_MAX_TIMEOUT = 180;   // mutlak tavan (sn)

    private Semaphore permits;
    private ExecutorService execPool;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean k6Available = false;
    private volatile String k6Version = null;
    /** Permit alamadığı / k6 bulunamadığı için YÜRÜTÜLEMEYEN kontrol sayısı — kapasite darlığının
     *  tek erken uyarısı. Bu sayaç olmadan "havuz dolu" ile "hedef çöktü" aynı ERROR satırıydı. */
    private io.micrometer.core.instrument.Counter skipped;

    // ── Sonuç + env tipleri ──────────────────────────────────────────────────
    public record EnvVar(String name, String value, boolean secret) {}
    // durationMs Long (nullable): k6 hiç KOŞAMADIYSA (binary yok / havuz dolu / interrupt) süre yoktur —
    // null yazılır ki UI "0 ms" gibi yanıltıcı gerçek-süre göstermesin ("—" gösterir). 2026-08 hizalaması.
    public record ScriptedResult(String status, boolean ok, Long durationMs, Integer exitCode,
                                 Integer checksPassed, Integer checksFailed, Long iterationMs,
                                 Long httpReqAvgMs, Long httpReqP95Ms, String checksJson,
                                 String outputTail, String error, boolean viaProxy, Phases phases) {}

    /**
     * İsteğin faz kırılımı (ms) + taşınan byte. Ayrı record: {@code ScriptedResult} zaten 13 bileşenli,
     * dokuz alan daha eklemek çağrı yerlerini okunmaz hâle getirirdi.
     *
     * <p>Alan {@code null} ise o faz HİÇ ÖLÇÜLMEDİ — yani koşum oraya varamadı. Takılma noktası,
     * sırayla bakıldığında ilk null olan fazdır; teşhis tam olarak bu bilgiye dayanır.
     */
    public record Phases(Long blockedMs, Long connectingMs, Long tlsMs, Long sendingMs,
                         Long waitingMs, Long receivingMs, Long dataSent, Long dataReceived,
                         Integer httpReqFailed) {
        public static final Phases EMPTY = new Phases(null, null, null, null, null, null, null, null, null);

        static Phases of(Summary s) {
            return new Phases(s.blockedMs, s.connectingMs, s.tlsMs, s.sendingMs, s.waitingMs,
                    s.receivingMs, s.dataSent, s.dataReceived, s.httpReqFailed);
        }
    }

    @PostConstruct
    public void init() {
        int pool = Math.max(1, appSettings.getInt("site.monitor.scripted.pool-size", 2));
        permits = new Semaphore(pool);
        execPool = Executors.newVirtualThreadPerTaskExecutor();
        Gauge.builder("scripted.k6.active", active, AtomicInteger::get)
                .description("Şu an çalışan k6 alt süreç sayısı").register(registry);
        Gauge.builder("scripted.k6.queued", queued, AtomicInteger::get)
                .description("k6 havuzunda bekleyen kontrol sayısı").register(registry);
        Gauge.builder("scripted.k6.available", this, s -> s.k6Available ? 1 : 0)
                .description("k6 binary'si kullanılabilir mi (1/0) — 0 ise sentetik izleme tamamen ölüdür")
                .register(registry);
        skipped = io.micrometer.core.instrument.Counter.builder("scripted.k6.skipped")
                .description("Yürütülemeyen kontrol sayısı (havuz dolu / k6 yok) — kapasite darlığı sinyali")
                .register(registry);
        probeK6();
    }

    @PreDestroy
    public void shutdown() {
        if (execPool != null) execPool.shutdownNow();
    }

    // ── Kaydetme öncesi doğrulama ────────────────────────────────────────────

    /**
     * Doğrulama için AYRI semafor. İzleme havuzunu ({@code pool-size}, varsayılan 2) asla
     * tüketmez — yoksa bir kaydetme rafalı sweep'i aç bırakırdı.
     */
    private final Semaphore validatePermits = new Semaphore(2);

    /**
     * Doğrulama koşumunun çıktı tavanı.
     *
     * <p>4 KiB YETMİYOR ve yetmemesi sessizce zarar veriyordu: {@code ProcessProbe} çıktının SON
     * N byte'ını tutar, k6 ise tek bir sözdizimi hatası için ~4,3 KiB'lık logfmt satırı basar
     * (kod çerçevesi + 65 babel yığın satırı). Baştan kırpılınca {@code level=error} ve
     * {@code msg="} açılışı gidiyor; ayıklayıcı satırı tanıyamıyor, kaçışlar çözülmüyor ve
     * kullanıcı 400 yanıtında ham blob görüyordu (2026-08'de ölçüldü).
     *
     * <p>Bu çıktı hiçbir yere KAYDEDİLMEZ — yalnız ayrıştırılıp atılır ve eşzamanlılığı ayrı
     * semaforla 2 ile sınırlıdır; cömert davranmanın maliyeti yok.
     */
    private static final int VALIDATE_OUTPUT_BYTES = 32 * 1024;

    /** Ayıklama tutmadığında ham çıktı — 400 yanıtına 32 KiB blob koymamak için tavanlı. */
    private static String rawFallback(String output) {
        if (output == null) return "";
        String s = output.strip();
        return s.length() <= ERR_MAX_TOTAL_CHARS ? s : s.substring(0, ERR_MAX_TOTAL_CHARS) + "…";
    }

    /**
     * Kaydetme öncesi script doğrulaması sonucu.
     *
     * @param blocking kaydetmeyi ENGELLEYEN kesin hata (satır/sütun çıkarılabildi); null ise engel yok
     * @param warnings engellemeyen uyarılar (belirsiz doğrulama, eksik/kullanılmayan __ENV)
     */
    public record ScriptDiagnostics(String blocking, List<String> warnings) {
        public boolean blocked() { return blocking != null; }
    }

    /** Satır/sütun taşıyan k6 derleme hatası — "kesin hata" kararının kanıtı. */
    private static final Pattern K6_LINE_COL = Pattern.compile("\\((\\d+):(\\d+)\\)|script:(\\d+):(\\d+)");

    /**
     * Script'i {@code k6 archive} ile derleyerek doğrular ve {@code __ENV} referanslarını denetler.
     *
     * <p><b>Bu KISITLI KOD YÜRÜTMEDİR:</b> {@code k6 archive} uzaktan {@code import} edilen
     * kütüphaneleri indirmeye çalışır ve init context'i (modül üst seviyesi + {@code export const
     * options}) çalıştırır. Bu yüzden {@code run}'daki SSRF korumasının AYNISI uygulanır
     * ({@code --blacklist-ip}); env değişkenleri VERİLMEZ, yani secret'lar bu yola hiç girmez.
     *
     * <p>Karar: satır/sütun çıkarılabiliyorsa kesin sözdizimi hatasıdır → engelle. Timeout,
     * uzak import indirilememesi veya yorumlanamayan çıktı → engelleme, uyar. Gerekçe: ağ
     * dalgalanmasında geçerli bir script "hatalı" görünüp adminleri monitörü düzenleyemez
     * hâle getirmemeli.
     */
    public ScriptDiagnostics validateScript(String script, List<String> envNames) {
        return validateScript(script, envNames, null);
    }

    /**
     * @param timeoutSeconds monitörün KAYDEDİLMEK ÜZERE olan süreç bütçesi (ham, kısıtlanmamış).
     *                       null ⇒ bütçe-bağımlı denetimler atlanır (eski iki argümanlı çağrı).
     */
    public ScriptDiagnostics validateScript(String script, List<String> envNames, Integer timeoutSeconds) {
        List<String> warnings = new ArrayList<>(auditEnvReferences(script, envNames));
        warnings.addAll(auditRequestTimeouts(script));
        // Kısıtlama SESSİZ olmasın: kullanıcı 300 yazdıysa monitör 180'de kesilecek ve bunu hiçbir
        // yerde görmüyordu — sonra "180. saniyede neden öldü" sorusu cevapsız kalıyordu.
        if (timeoutSeconds != null) {
            int effective = clampTimeout(timeoutSeconds);
            if (effective != timeoutSeconds) {
                warnings.add(String.format(
                        "Süreç bütçesi %d sn olarak kaydedilecek (girdiğiniz %d sn sınırlara kısıldı).",
                        effective, timeoutSeconds));
            }
            warnings.addAll(auditTimeoutBudget(script, effective));
        }
        String policy = appSettings.getString("site.monitor.scripted.syntax-check-policy", "WARN");
        if ("OFF".equalsIgnoreCase(policy) || !k6Available || script == null || script.isBlank()) {
            return new ScriptDiagnostics(null, warnings);
        }
        boolean acquired = false;
        Path scriptFile = null, archiveFile = null, caFile = null;
        try {
            acquired = validatePermits.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                warnings.add("Sözdizimi doğrulaması atlandı (doğrulayıcı meşgul).");
                return new ScriptDiagnostics(null, warnings);
            }
            scriptFile = Files.createTempFile("k6-script-", ".js");
            archiveFile = Files.createTempFile("k6-archive-", ".tar");
            Files.writeString(scriptFile, script);

            // DİKKAT: `--no-usage-report` YALNIZ `k6 run`'da var; `archive`'a verilirse komut
            // "unknown flag" ile düşer ve doğrulama sessizce sonuçsuz kalır (2026-08'de oldu).
            List<String> args = new ArrayList<>(List.of(
                    k6Bin(), "archive", "--quiet",
                    "-O", archiveFile.toAbsolutePath().toString()));
            for (String cidr : ssrfGuard.blacklistCidrs()) { args.add("--blacklist-ip"); args.add(cidr); }
            args.add(scriptFile.toAbsolutePath().toString());

            int timeout = Math.max(3, appSettings.getInt("site.monitor.scripted.validate-timeout-seconds", 10));
            // Doğrulama da uzak import indirebiliyor ⇒ koşumla AYNI ağ duruşu (vekil + kurumsal CA);
            // aksi halde geçerli bir script vekil ardında "derlenemedi" görünürdü. env VERİLMEZ:
            // secret'lar bu yola hiç girmez (sınıf sözleşmesi) — ortam da izole edilir.
            caFile = writeCaBundle();
            // Monitörün env'i VERİLMEZ (secret'lar bu yola hiç girmez), AMA vekil kimliği env'e
            // giriyor ve k6 hata metninde vekil URL'ini basabiliyor ("proxyconnect tcp: ...").
            // secretValues bu yüzden GERÇEK bir listeye toplanır — çöpe atılırsa aşağıdaki
            // maskeleme yapacak bir şey bulamaz ve parola 400 yanıtının gövdesine düşer.
            List<String> secretValues = new ArrayList<>();
            Map<String, String> env = buildProcessEnv(null, proxyUseFor(null), caFile, secretValues);
            ProcessProbe.Result r = ProcessProbe.run(args, env, scriptFile.getParent().toFile(), timeout, true,
                    VALIDATE_OUTPUT_BYTES, true);
            if (r.exitCode() == 0 && !r.timedOut()) return new ScriptDiagnostics(null, warnings);

            String detail = validationDetail(r.output(), secretValues);
            if (r.timedOut()) {
                warnings.add("Sözdizimi doğrulaması zaman aşımına uğradı (uzak import yavaş olabilir) — kaydedildi.");
                return new ScriptDiagnostics(null, warnings);
            }
            // Kesin hata KANITI: satır/sütun çıkarılabiliyor mu?
            if (K6_LINE_COL.matcher(detail).find() && !"WARN".equalsIgnoreCase(policy)) {
                return new ScriptDiagnostics("Script derlenemedi: " + detail, warnings);
            }
            if (K6_LINE_COL.matcher(detail).find()) {
                warnings.add("Script derlenemedi: " + detail);
            } else {
                warnings.add("Sözdizimi doğrulaması sonuçsuz kaldı (ağ/uzak import olabilir) — kaydedildi.");
            }
            return new ScriptDiagnostics(null, warnings);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScriptDiagnostics(null, warnings);
        } catch (Exception e) {
            log.warn("Script doğrulaması çalıştırılamadı: {}", e.toString());
            return new ScriptDiagnostics(null, warnings);
        } finally {
            if (acquired) validatePermits.release();
            deleteQuiet(scriptFile);
            deleteQuiet(archiveFile);
            deleteQuiet(caFile);
        }
    }

    private static final Pattern ENV_DOT = Pattern.compile("__ENV\\s*\\.\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern ENV_IDX = Pattern.compile("__ENV\\s*\\[\\s*([\"'])([A-Za-z_$][\\w$]*)\\1\\s*]");
    /** `__ENV` sonrası `.` veya `["`/`['` GELMEYEN her geçiş → statik çözülemeyen dinamik erişim. */
    private static final Pattern ENV_DYNAMIC = Pattern.compile("__ENV\\s*(?![.\\s]*[.\\[])|__ENV\\s*\\[\\s*(?![\"'])");

    /** Script gövdesindeki mutlak URL'ler — tırnak/backtick/parantez/boşlukta biter. */
    private static final Pattern URL_LITERAL = Pattern.compile("https?://[^\\s'\"`)<>\\\\]+");
    /** Şablon literali içindeki `${__ENV.NAME}` — env değeriyle yerine konur. */
    private static final Pattern ENV_TEMPLATE = Pattern.compile("\\$\\{\\s*__ENV\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*}");

    /**
     * Script'in gittiği HEDEF URL'leri çıkarır — "Bağlantı Teşhisi" hangi adrese sonda atacağını
     * bilsin diye.
     *
     * <p>Neden gerekli: sentetik monitörün tek bir "host" alanı yoktur (diğer 9 türün aksine);
     * hedef, script gövdesinin içindedir. Teşhis için kullanıcıya "adresi elle yaz" demek, tam da
     * hata ayıklamaya çalıştığı anda ondan bilgi istemek olurdu.
     *
     * <p>Önce {@code ${__ENV.NAME}} geçişleri env DEĞERLERİYLE doldurulur (BASE_URL deseni çok
     * yaygın), sonra mutlak URL'ler toplanır. Statik olarak çözülemeyen kurgular (string
     * birleştirme, dinamik {@code __ENV[x]}) bilinçli olarak kaçırılır — uç, kullanıcının elle
     * URL vermesine izin verir, yani çıkarım bir KOLAYLIKTIR, tek yol değil.
     *
     * <p>Secret env değerleri de yerine konur; dönen liste yalnız sonda hedefi seçmek için
     * kullanılır ve API'ye giden değer çağıran tarafından maskelenmelidir.
     *
     * @return tekilleştirilmiş URL listesi (sırayla; en fazla 10)
     */
    public static List<String> extractTargetUrls(String script, List<EnvVar> env) {
        if (script == null || script.isBlank()) return List.of();
        String src = stripComments(script);
        if (env != null && !env.isEmpty()) {
            Map<String, String> byName = new LinkedHashMap<>();
            for (EnvVar v : env) if (v.name() != null) byName.put(v.name(), v.value() == null ? "" : v.value());
            StringBuilder sb = new StringBuilder();
            var m = ENV_TEMPLATE.matcher(src);
            while (m.find()) {
                String val = byName.get(m.group(1));
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val == null ? m.group() : val));
            }
            m.appendTail(sb);
            src = sb.toString();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        var m = URL_LITERAL.matcher(src);
        while (m.find() && out.size() < 10) out.add(m.group());
        return List.copyOf(out);
    }

    /**
     * Script'teki {@code __ENV.X} referanslarını tanımlı ortam değişkenleriyle karşılaştırır.
     *
     * <p>ASLA engellemez (WARN) — regex tabanlıdır ve string literal içindeki bir {@code __ENV.FOO}
     * geçişini yanlış-pozitif olarak yakalayabilir (bilinen sınır). Yorum satırları taranmadan önce
     * silinir. Dinamik erişim ({@code __ENV[name]}, {@code const {A} = __ENV}, {@code Object.keys(__ENV)})
     * görülürse "tanımlı ama kullanılmıyor" yarısı SUSTURULUR — statik olarak çözülemez.
     */
    public static List<String> auditEnvReferences(String script, List<String> envNames) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) return out;
        String src = stripComments(script);

        java.util.LinkedHashSet<String> referenced = new java.util.LinkedHashSet<>();
        var m1 = ENV_DOT.matcher(src);
        while (m1.find()) referenced.add(m1.group(1));
        var m2 = ENV_IDX.matcher(src);
        while (m2.find()) referenced.add(m2.group(2));
        boolean dynamic = ENV_DYNAMIC.matcher(src).find();

        var defined = new java.util.LinkedHashSet<String>();
        if (envNames != null) for (String n : envNames) if (n != null && !n.isBlank()) defined.add(n.trim());

        for (String r : referenced) {
            if (!defined.contains(r)) {
                out.add("`" + r + "` script'te kullanılıyor ama tanımlı bir ortam değişkeni yok.");
            }
        }
        if (!dynamic) {
            for (String d : defined) {
                if (!referenced.contains(d)) out.add("`" + d + "` tanımlı ama script kullanmıyor.");
            }
        }
        return out;
    }

    private static final Pattern HTTP_CALL = Pattern.compile("http\\s*\\.\\s*(get|post|put|del|patch|head|options|request)\\s*\\(");
    private static final Pattern REQUEST_TIMEOUT_OPT = Pattern.compile("timeout\\s*:");

    /**
     * Statik tarayıcılar için yorum ayıklama.
     *
     * <p>{@code ://} KORUNUR: naif bir {@code //…} kuralı {@code 'https://x'} URL'sini satır yorumu
     * sanıp satırın GERİ KALANINI siler. Gerçek etkisi: aynı satırdaki {@code timeout: '20s'}
     * görünmez olur ve "açık timeout yok" uyarısı YANLIŞ yere basılır; aynı şekilde
     * {@code http.get(base + '/x', …)} satırındaki {@code __ENV.X} kaybolup "tanımlı ama
     * kullanılmıyor" uyarısı üretilir. (Bu testte yakalandı, sahada değil.)
     *
     * <p>Bilinen sınır: string literal içindeki gerçek {@code //} (URL dışı) yine yorum sanılır —
     * uyarı üreten bir sezgisel için kabul edilebilir.
     */
    static String stripComments(String script) {
        return script
                .replaceAll("/\\*[\\s\\S]*?\\*/", " ")     // blok yorum
                .replaceAll("(?m)(?<!:)//[^\n]*", " ");    // satır yorumu — `://` hariç
    }

    /**
     * Script isteklerinde AÇIK timeout var mı? Yoksa engellemeyen uyarı.
     *
     * <p>Neden: k6'nın kendi varsayılan istek timeout'u 60 sn'dir ve monitörün süreç timeout'undan
     * BAĞIMSIZDIR. Açık timeout verilmeyen bir script, monitör timeout'u 180 sn olsa bile isteği
     * 60 sn'de düşürür; hedef vekil/güvenlik duvarı yüzünden yutuluyorsa her koşum aynı duvara
     * çarpar (2026-08 saha vakası: 288 koşumun 288'i). Açık timeout ayrıca sebebin yazılabilmesini
     * sağlar — süreç öldürülmeden k6 kendi hatasını basar.
     *
     * <p>Yorumlar taranmadan önce silinir; string literal içindeki {@code timeout:} yanlış-negatif
     * üretebilir (bilinen sınır, uyarı olduğu için zararsız).
     */
    static List<String> auditRequestTimeouts(String script) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) return out;
        String src = stripComments(script);
        if (HTTP_CALL.matcher(src).find() && !REQUEST_TIMEOUT_OPT.matcher(src).find()) {
            out.add("İsteklerde açık timeout yok. k6'nın varsayılanı 60 sn'dir ve monitörün süreç "
                    + "timeout'undan bağımsızdır — ör. `{ timeout: '20s' }` verin.");
        }
        return out;
    }

    /** {@code timeout: '20s'} / {@code timeout: "500ms"} / {@code timeout: 20000} — değeriyle birlikte. */
    private static final Pattern TIMEOUT_VALUE = Pattern.compile(
            "timeout\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|(\\d+))");

    /** k6 süre bileşeni — bileşik ifadeler de geçerli: {@code '1m30s'}. */
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)");

    /** k6 süre gösterimi → ms. Çıplak sayı k6'da MİLİSANİYEDİR. Ayrıştırılamazsa null. */
    static Long parseK6DurationMs(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.matches("\\d+")) return Long.parseLong(s);          // çıplak sayı = ms (k6 sözleşmesi)
        long total = 0;
        boolean any = false;
        var m = DURATION_PART.matcher(s);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            long mult = switch (m.group(2)) {
                case "ms" -> 1L;
                case "s"  -> 1_000L;
                case "m"  -> 60_000L;
                default   -> 3_600_000L;                          // "h"
            };
            total += (long) (v * mult);
            any = true;
        }
        return any ? total : null;
    }

    /**
     * Script'teki EN BÜYÜK açık istek timeout'u (saniye, yukarı yuvarlanır); açık timeout yoksa null.
     *
     * <p>En büyüğü alınır çünkü süreci öldüren şey en uzun bekleyen istektir.
     */
    static Integer maxRequestTimeoutSeconds(String script) {
        if (script == null || script.isBlank()) return null;
        Long maxMs = null;
        var m = TIMEOUT_VALUE.matcher(stripComments(script));
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            Long ms = parseK6DurationMs(raw);
            if (ms != null && (maxMs == null || ms > maxMs)) maxMs = ms;
        }
        return maxMs == null ? null : (int) Math.ceil(maxMs / 1000.0);
    }

    /**
     * TERS BÜTÇE — istek timeout'u süreç bütçesinden büyük/eşitse sebep ASLA yazılamaz.
     *
     * <p>Saha vakası (2026-08, 289 koşumun 289'u): script'te {@code timeout: '20s'} vardı, monitörün
     * süreç bütçesi 10 sn'ydi. k6 kendi isteğini düşürmeye fırsat bulamadan süreci 10. saniyede
     * öldürüyorduk; ekranda her seferinde sebepsiz "Süre aşımı" kaldı. {@link #auditRequestTimeouts}
     * bunun TERSİNİ (açık timeout yokluğunu) denetlediği için hiç uyarı çıkmadı — üstelik arayüz
     * "script'e açık timeout ekleyin" diye, kullanıcının çoktan yaptığı şeyi tavsiye ediyordu.
     *
     * <p>Eşitlik de hatadır: iki bütçe aynı anda dolar, yarışı hangisinin kazanacağı belirsizdir.
     */
    static List<String> auditTimeoutBudget(String script, Integer processBudgetSec) {
        List<String> out = new ArrayList<>();
        if (processBudgetSec == null || processBudgetSec <= 0) return out;
        Integer reqSec = maxRequestTimeoutSeconds(script);
        if (reqSec == null || reqSec < processBudgetSec) return out;   // açık timeout yok ya da sıra doğru
        out.add(String.format(
                "İstek timeout'u (%d sn) monitörün süreç bütçesine (%d sn) eşit ya da ondan büyük: "
                + "k6 isteği kendi düşüremeden süreci öldürüyoruz ve başarısızlığın SEBEBİ hiç yazılamıyor. "
                + "Süreç bütçesini ≥ %d sn yapın ya da istek timeout'unu ≤ %d sn'ye çekin.",
                reqSec, processBudgetSec, reqSec + TIMEOUT_HEADROOM_SEC,
                Math.max(1, processBudgetSec - TIMEOUT_HEADROOM_MIN_SEC)));
        return out;
    }

    /** k6'nın başlangıç + kapanış payı: süreç bütçesi, istek timeout'unun bu kadar üstünde olmalı. */
    private static final int TIMEOUT_HEADROOM_SEC = 15;
    /** Ters yönde öneri için asgari pay (istek timeout'u bütçenin bu kadar altına çekilmeli). */
    private static final int TIMEOUT_HEADROOM_MIN_SEC = 3;

    /** Açılışta (ve yeniden) k6 varlığını + sürümünü doğrular. */
    public final void probeK6() {
        try {
            ProcessProbe.Result r = ProcessProbe.run(List.of(k6Bin(), "version"), null, 5);
            if (!r.timedOut() && r.exitCode() == 0 && r.output() != null && r.output().toLowerCase(Locale.ROOT).contains("k6")) {
                k6Available = true;
                java.util.regex.Matcher m = Pattern.compile("v?\\d+\\.\\d+\\.\\d+").matcher(r.output());
                k6Version = m.find() ? m.group() : r.output().strip();
                log.info("[K6] ✅ k6 bulundu — sürüm {} ({})", k6Version, k6Bin());
            } else {
                k6Available = false; k6Version = null;
                log.warn("[K6] ⚠ k6 bulunamadı ({}). Sentetik İzleme türü devre dışı — kontrol yürütülmez.", k6Bin());
            }
        } catch (Exception e) {
            k6Available = false; k6Version = null;
            log.warn("[K6] ⚠ k6 sürüm kontrolü başarısız: {}", e.toString());
        }
    }

    /** Son yeniden-sonda anı — {@link #ensureProbed()} bunu kullanır. */
    private volatile long lastProbeAtMs = 0;
    private static final long REPROBE_INTERVAL_MS = 5 * 60_000L;

    /**
     * k6 yoksa PERİYODİK olarak yeniden dener (5 dk'da bir).
     *
     * <p>Neden: {@code probeK6()} yalnız {@code @PostConstruct}'ta çağrılıyordu. Pod, k6'yı taşıyan
     * volume/sidecar hazır olmadan başlarsa {@code k6Available} sonsuza kadar false kalıyor ve
     * sweep her turda SESSİZCE dönüyordu — hiçbir kontrol koşmuyor, hiçbir alarm çıkmıyor, hiçbir
     * kayıt yazılmıyor. Tek sinyal sentetik sayfasındaki banner'dı; oraya kimse bakmazsa sentetik
     * izleme haftalarca ölü kalabilirdi.
     *
     * @return k6 şu an kullanılabilir mi
     */
    public boolean ensureProbed() {
        if (k6Available) return true;
        long now = System.currentTimeMillis();
        if (now - lastProbeAtMs < REPROBE_INTERVAL_MS) return false;
        lastProbeAtMs = now;
        probeK6();
        return k6Available;
    }

    /**
     * Bir koşumun kuyrukta + çalışırken alabileceği MAKSİMUM süre (sn).
     *
     * <p>Çağıran ({@code SchedulerService}) sonucu beklerken bundan KISA bir süre kullanırsa koşum
     * arka planda tamamlanır ama sonucu hiçbir yere yazılmaz: {@code scripted_checks}'e satır
     * girmez, {@code checked_at} eskir, alarm da çıkmaz — monitör ekranda sessizce donar.
     * (2026-08: sweep 200 sn beklerken permit beklemesi 210 sn'ye çıkabiliyordu.)
     */
    public static int maxWaitSeconds() {
        return ABS_MAX_TIMEOUT + PERMIT_WAIT_MARGIN_SEC;
    }

    /** Permit beklemesine eklenen pay — {@link #maxWaitSeconds()} ile TEK kaynaktan türer. */
    private static final int PERMIT_WAIT_MARGIN_SEC = 30;

    public boolean isAvailable() { return k6Available; }
    public String version() { return k6Version; }
    public int activeProcesses() { return active.get(); }
    public int queuedChecks() { return queued.get(); }

    private String k6Bin() { return appSettings.getString("site.monitor.scripted.k6-bin", "k6"); }

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** Kaydedilmiş monitör (scheduler/manuel). */
    public ScriptedResult run(ScriptedMonitor m) {
        return runGuarded(m.getScript(), parseEnv(m.getEnvJson(), true), clampTimeout(m.getTimeoutSeconds()),
                proxyUseFor(m.getUseProxy()));
    }

    /** Scheduler fan-out: ayrı executor'a submit → scheduler thread'i bloklanmaz. */
    public Future<ScriptedResult> submit(ScriptedMonitor m) {
        queued.incrementAndGet();
        ProxyUse viaProxy = proxyUseFor(m.getUseProxy());
        return execPool.submit(() -> runGuardedAfterQueue(m.getScript(), parseEnv(m.getEnvJson(), true),
                clampTimeout(m.getTimeoutSeconds()), viaProxy));
    }

    /**
     * Monitörün script'inden çıkarılan hedef adresler — "Bağlantı Teşhisi" varsayılan hedefi.
     * Secret env değerleri çözülür (BASE_URL bir secret olabilir); çağıran API'ye verirken maskeler.
     */
    public List<String> targetUrls(ScriptedMonitor m) {
        return extractTargetUrls(m.getScript(), parseEnv(m.getEnvJson(), true));
    }

    /** Kaydetmeden tek seferlik test — env JSON ham (secret değerleri düz gelir, henüz şifreli değil). */
    public ScriptedResult test(String script, String envJson, Integer timeoutSeconds, String useProxy) {
        return runGuarded(script, parseEnv(envJson, false), clampTimeout(timeoutSeconds), proxyUseFor(useProxy));
    }

    /**
     * Bir koşumun vekil kararı. Boolean YETMEZ: "vekil değişkenleri verilsin mi" ile "NO_PROXY
     * eşleşmesi bu kararı bozabilsin mi" ayrı sorular.
     */
    enum ProxyUse {
        /** Vekil değişkeni HİÇ verilmez (kapalı ya da yapılandırılmamış). */
        DIRECT,
        /** Vekil değişkenleri + NO_PROXY verilir: listeye uyan hedefler yine doğrudan çıkar. */
        AUTO,
        /** Vekil değişkenleri verilir, NO_PROXY VERİLMEZ: hedef listeye uysa bile vekilden çıkar. */
        FORCED;

        boolean on() { return this != DIRECT; }
    }

    /**
     * Bu koşum kurumsal çıkış vekilinden geçecek mi?
     *
     * <p>{@code AUTO} (varsayılan; null/boş da AUTO) → vekil yapılandırılmışsa evet. Java tarafındaki
     * sertifika/RDAP çıkışlarıyla aynı davranış: vekil zorunlu ağda k6'nın doğrudan çıkması, güvenlik
     * cihazınca TCP'de kabul edilip yutulduğu için her koşumu {@code request timeout}'a düşürüyordu.
     * {@code OFF} → iç hedefler için doğrudan.
     *
     * <p>{@code ON} ("her zaman vekil üzerinden") uzun süre AUTO ile AYNI şeyi yapıyordu: Go,
     * NO_PROXY girdilerini SONEK olarak uygular ({@code akbank.com} ⇒ tüm alt alanlar), yani
     * kullanıcı ON seçse de eşleşen hedef doğrudan çıkıyor, ekran ise "vekil üzerinden" diyordu.
     * Artık ON ⇒ {@link ProxyUse#FORCED}: NO_PROXY hiç verilmez, seçim gerçekten uygulanır.
     */
    ProxyUse proxyUseFor(String mode) {
        if (!proxySettings.enabled()) return ProxyUse.DIRECT;
        String m = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if ("OFF".equals(m)) return ProxyUse.DIRECT;
        return "ON".equals(m) ? ProxyUse.FORCED : ProxyUse.AUTO;
    }

    int clampTimeout(Integer t) {
        int v = (t == null) ? appSettings.getInt("site.monitor.scripted.default-timeout-seconds", 60) : t;
        // Fallback ABS_MAX_TIMEOUT ile hizalı: ayar okunamazsa tavan sessizce 60'a düşüyordu —
        // kullanıcının 180'e ayarladığı monitör sebepsiz yere 60 sn'de kesilirdi.
        int max = Math.min(ABS_MAX_TIMEOUT,
                appSettings.getInt("site.monitor.scripted.max-timeout-seconds", ABS_MAX_TIMEOUT));
        return Math.max(5, Math.min(max, v));
    }

    // ── Alt süreç ortamı ─────────────────────────────────────────────────────

    /** Dağıtımlara göre sistem CA paketi konumları — kurumsal PEM bunlarla BİRLEŞTİRİLİR. */
    private static final List<String> SYSTEM_CA_FILES = List.of(
            "/etc/ssl/certs/ca-certificates.crt",   // Alpine / Debian / Ubuntu (bizim imaj)
            "/etc/pki/tls/certs/ca-bundle.crt",     // RHEL / UBI
            "/etc/ssl/ca-bundle.pem");              // SUSE

    /**
     * Kurumsal CA paketi ayarlıysa Go/k6'nın okuyabileceği geçici bir PEM dosyası yazar.
     *
     * <p>Neden gerekli: Java tarafı kurumsal kökü {@link TrustEvaluator} üzerinden tanıyor
     * ({@code site.monitor.trust.ca-bundle-pem}), k6 ise tanımıyordu — TLS'i araya giren bir
     * kurumsal cihaz varsa aynı hedefe Java'nın sertifikası doğrulanırken k6'nınki doğrulanmıyor.
     *
     * <p><b>Birleştirme şart:</b> Go'da {@code SSL_CERT_FILE} sistem kök havuzuna EKLEMEZ, onun
     * YERİNE geçer. Yalnız kurumsal kökü yazsaydık bu kez public CA'lı hedefler doğrulanamazdı.
     *
     * @return yazılan dosya; ayar boşsa ya da yazılamazsa {@code null} (çağıran değişkeni koymaz)
     */
    private Path writeCaBundle() {
        String pem = appSettings.getString(TrustEvaluator.CA_BUNDLE_KEY, "");
        if (pem == null || pem.isBlank()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            String merged = SYSTEM_CA_FILES.stream().map(Path::of).filter(Files::isReadable).findFirst()
                    .map(p -> { try { return Files.readString(p); } catch (java.io.IOException e) { return null; } })
                    .orElse(null);
            if (merged != null) sb.append(merged).append('\n');
            else log.warn("[K6] Sistem CA paketi bulunamadı; SSL_CERT_FILE yalnız kurumsal kökü taşıyacak "
                    + "(public CA'lı hedefler doğrulanamayabilir).");
            sb.append(pem.strip()).append('\n');
            Path f = Files.createTempFile("k6-ca-", ".pem");
            Files.writeString(f, sb.toString());
            return f;
        } catch (java.io.IOException e) {
            log.warn("[K6] Kurumsal CA paketi geçici dosyaya yazılamadı: {}", e.toString());
            return null;
        }
    }

    /**
     * k6 alt sürecine geçecek ortam değişkenleri. Süreç ortamı İZOLEDİR
     * ({@code ProcessProbe.run(..., isolatedEnv=true)}), yani burada dönen harita + küçük bir
     * sistem beyaz-listesi dışında k6 hiçbir şey görmez.
     *
     * <p>Sıra bilinçli: önce kullanıcının kendi env'i, sonra {@code putIfAbsent} ile vekil ve CA —
     * script kendi {@code HTTPS_PROXY}'sini tanımlamışsa ezilmez.
     *
     * @param envVars      monitörün env değişkenleri (null ⇒ hiçbiri; doğrulama yolu secret geçirmez)
     * @param secretValues maskelenecek değerler bu listeye EKLENİR (çıkış parametresi)
     */
    Map<String, String> buildProcessEnv(List<EnvVar> envVars, ProxyUse viaProxy, Path caFile,
                                        List<String> secretValues) {
        Map<String, String> env = new LinkedHashMap<>();
        if (envVars != null) {
            for (EnvVar v : envVars) {
                if (v.name() == null || v.name().isBlank()) continue;
                env.put(v.name(), v.value() == null ? "" : v.value());
                if (v.secret() && v.value() != null && !v.value().isBlank()) secretValues.add(v.value());
            }
        }
        // Kurumsal çıkış vekili: Go/k6 bu üç değişkeni okur.
        if (viaProxy.on()) {
            String url = proxySettings.proxyUrl();
            if (url != null) {
                env.putIfAbsent("HTTPS_PROXY", url);
                env.putIfAbsent("HTTP_PROXY", url);
                // FORCED ⇒ NO_PROXY hiç konmaz. Go bu listeyi SONEK olarak uygular
                // (`akbank.com` ⇒ tüm alt alanlar), yani liste verildiği sürece "her zaman vekil
                // üzerinden" seçimi eşleşen hedeflerde sessizce doğrudan çıkışa dönüşüyordu.
                String noProxy = proxySettings.noProxyList();
                if (viaProxy != ProxyUse.FORCED && !noProxy.isBlank()) env.putIfAbsent("NO_PROXY", noProxy);
                // Parola URL'in içinde: k6 hata mesajında vekil URL'ini basabiliyor
                // (ör. "proxyconnect tcp: ..."). Maskelenmezse çıktı → error → alarm e-postası
                // zincirinden sızardı. SecretMask kodlanmış biçimleri de kapsar.
                String pass = proxySettings.secretValue();
                if (pass != null) secretValues.add(pass);
            }
        }
        if (caFile != null) env.putIfAbsent("SSL_CERT_FILE", caFile.toAbsolutePath().toString());
        return env;
    }

    // ── Bağlantı teşhisi sondası ─────────────────────────────────────────────

    /**
     * Teşhis sondaları için AYRI semafor (1). İzleme havuzunu ({@code pool-size}) tüketmez —
     * {@code validatePermits} ile aynı gerekçe: kullanıcı tetikli bir işlem, zamanlanmış izlemeyi
     * aç bırakmamalı. Tek permit: teşhis üç bacağı SIRAYLA koşar, paralel teşhis isteği beklemez.
     */
    private final Semaphore diagPermits = new Semaphore(1);

    /** Sonda script'i — tek istek, açık timeout, çıktıya durum kodu. Kullanıcı script'i KOŞMAZ. */
    private static final String PROBE_SCRIPT = """
            import http from 'k6/http';
            export default function () {
              const r = http.get(__ENV.SM_DIAG_URL, { timeout: '15s' });
              console.log('sm_diag_status=' + r.status);
            }
            """;

    /**
     * Tek bir URL'e k6 ile sonda atar — "Java çekebiliyor ama k6 çekemiyor" ayrımını ÖLÇER.
     *
     * <p>Sahadaki teşhis tıkanıklığı tam buradaydı: aynı pod'dan Java sertifikayı alabiliyor, k6
     * {@code request timeout} veriyordu ve farkın nerede oluştuğu (vekil kararı mı, kurumsal CA mı,
     * TLS'in kendisi mi) hiçbir ekrandan görülemiyordu. Bu sonda üç değişkeni TEK TEK oynatır.
     *
     * @param url       hedef (kullanıcı script'i değil, üretilen sonda script'i koşar)
     * @param viaProxy  kurumsal vekil kullanılsın mı — true ⇒ {@link ProxyUse#FORCED}: NO_PROXY
     *                  verilmez. Teşhis "vekilli/vekilsiz" ayrımını ölçer; NO_PROXY konsaydı iki
     *                  bacak da doğrudan çıkıp AYNI sonucu verir, sonda hiçbir şey ayırt etmezdi.
     * @param withCa    kurumsal CA paketi k6'ya verilsin mi (false ⇒ Go yalnız kendi köklerine bakar)
     */
    public ScriptedResult probe(String url, boolean viaProxy, boolean withCa) {
        if (!k6Available) return err("k6 bulunamadı — Sentetik İzleme devre dışı");
        boolean acquired = false;
        try {
            acquired = diagPermits.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) return err("teşhis sondası meşgul — birazdan tekrar deneyin");
            List<EnvVar> env = List.of(new EnvVar("SM_DIAG_URL", url, false));
            return execute(PROBE_SCRIPT, env, 25, viaProxy ? ProxyUse.FORCED : ProxyUse.DIRECT, withCa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("kesintiye uğradı");
        } finally {
            if (acquired) diagPermits.release();
        }
    }

    private ScriptedResult runGuarded(String script, List<EnvVar> env, int timeoutSec, ProxyUse viaProxy) {
        queued.incrementAndGet();
        return runGuardedAfterQueue(script, env, timeoutSec, viaProxy);
    }

    private ScriptedResult runGuardedAfterQueue(String script, List<EnvVar> env, int timeoutSec, ProxyUse viaProxy) {
        if (!k6Available) { queued.decrementAndGet(); return err("k6 bulunamadı — Sentetik İzleme devre dışı"); }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeoutSec + PERMIT_WAIT_MARGIN_SEC, TimeUnit.SECONDS);
            queued.decrementAndGet();
            if (!acquired) return err("k6 havuzu dolu — kontrol atlandı (sıra beklemesi aşıldı)");
            active.incrementAndGet();
            return execute(script, env, timeoutSec, viaProxy);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queued.decrementAndGet();
            return err("kesintiye uğradı");
        } finally {
            if (acquired) { active.decrementAndGet(); permits.release(); }
        }
    }

    // ── Çalıştırma ───────────────────────────────────────────────────────────

    /**
     * k6'yı çalıştırır ve sonucu yorumlar.
     *
     * <p><b>Tanı asla kaybolmaz.</b> 2026-08'de bir monitör 289 koşumun 289'unda
     * {@code Cannot invoke "Integer.intValue()" because "s.checksFailed" is null} ile düştü:
     * yorumlama adımındaki bir NPE dıştaki catch'e düşüyor, {@code err()} ise elde olan HER ŞEYİ
     * (gerçek çıkış kodu, ölçülen süre, k6'nın stdout/stderr'i) çöpe atıyordu. Kullanıcı ekranda
     * ham bir JVM istisnası görüyor, hatanın yazdığı tek yeri — k6 çıktısını — hiç göremiyordu.
     *
     * <p>Bu yüzden {@code r}, {@code output}, {@code durationMs} try bloğunun DIŞINDA tutulur ve
     * iki katmanlı guard vardır: (1) dış catch bağlamı koruyarak sonuç üretir, (2) yorumlama
     * adımının kendi catch'i vardır — orada patlayan bir şey koşumun tanısını götürmez.
     */
    private ScriptedResult execute(String script, List<EnvVar> envVars, int timeoutSec, ProxyUse viaProxy) {
        return execute(script, envVars, timeoutSec, viaProxy, true);
    }

    /** @param withCa false ⇒ kurumsal CA paketi VERİLMEZ; teşhis sondası "CA mı sebep?" sorusunu böyle ayırır. */
    private ScriptedResult execute(String script, List<EnvVar> envVars, int timeoutSec, ProxyUse viaProxy,
                                   boolean withCa) {
        Path scriptFile = null, summaryFile = null, caFile = null;
        // Bağlam DEĞİŞKENLERİ try dışında: catch bloğu bunlara erişebilsin.
        List<String> secretValues = new ArrayList<>();
        ProcessProbe.Result r = null;   // null ⇒ süreç hiç başlamadı
        String output = null;           // DAİMA maskeli — asla ham r.output() değil
        Long durationMs = null;         // null ⇒ koşum gerçekleşmedi (record sözleşmesi, satır 62-63)
        try {
            scriptFile = Files.createTempFile("k6-script-", ".js");
            summaryFile = Files.createTempFile("k6-summary-", ".json");
            Files.writeString(scriptFile, script == null ? "" : script);

            caFile = withCa ? writeCaBundle() : null;
            Map<String, String> env = buildProcessEnv(envVars, viaProxy, caFile, secretValues);

            List<String> args = new ArrayList<>(List.of(
                    k6Bin(), "run", "--quiet", "--no-usage-report",
                    "--summary-export=" + summaryFile.toAbsolutePath(),
                    "--vus", "1", "--iterations", "1"));
            for (String cidr : blacklistFor(viaProxy.on())) { args.add("--blacklist-ip"); args.add(cidr); }
            args.add(scriptFile.toAbsolutePath().toString());

            int tailBytes = appSettings.getInt("site.monitor.scripted.output-tail-bytes", 8192);
            long t0 = System.currentTimeMillis();
            r = ProcessProbe.run(args, env, scriptFile.getParent().toFile(), timeoutSec, true, tailBytes, true);
            // durationMs YALNIZ süreç gerçekten koştuysa set edilir; aksi halde "temp dosya
            // yazılamadı, 3 ms" gibi anlamsız bir süre uptime grafiğine girerdi.
            durationMs = System.currentTimeMillis() - t0;
            output = SecretMask.maskValues(r.output(), secretValues);

            Summary s = safeParseSummary(summaryFile);

            // ── Katman 2: yorumlama guard'ı ──
            String status;
            String error;
            try {
                // Hata satırı ayıklaması iki işe yarıyor: mesajı zenginleştirmek VE "script patladı mı"
                // sinyalini üretmek (k6 iterasyon istisnasında yine 0 ile çıkıyor).
                boolean scriptErrored = extractErrorLines(output) != null;
                status = decideStatus(r.exitCode(), r.timedOut(), s.checksPassed, s.checksFailed,
                        s.hasThresholds, scriptErrored);
                status = applyNoChecksPolicy(status);
                error = "PASS".equals(status) ? null : summarizeError(status, r, output);
            } catch (RuntimeException ie) {
                log.error("Senaryo sonucu yorumlanamadı (exit={} timedOut={})", r.exitCode(), r.timedOut(), ie);
                status = r.timedOut() ? "TIMEOUT" : "ERROR";
                error = "sonuç yorumlanamadı: " + safeMsg(ie, secretValues);
            }
            // TIMEOUT DIŞINDA: süreci biz öldürdüğümüzde k6 `--summary-export`u zaten yazamaz —
            // "k6 özeti okunamadı" demek her zaman aşımına, sebebin YANINA, yanlış bir iz ekliyordu
            // ("Süre aşımı … · k6 özeti okunamadı (metrik yok)"). Özetin yokluğu orada bulgu değil,
            // sonlandırmanın doğal sonucudur.
            if (!s.parsed && !"PASS".equals(status) && !"TIMEOUT".equals(status)) {
                error = (error == null ? "" : error + " · ") + "k6 özeti okunamadı (metrik yok)";
            }
            // Koşum bağlamı: "nereden timeout aldık" sorusunun cevabı burada. Hangi bütçe doldu,
            // script isteğine ne verilmiş, hangi çıkıştan gidildi, kurumsal CA verildi mi.
            // Bunlar hiçbir yerde kayıtlı değildi; kullanıcı 289 koşum boyunca yalnız "Süre aşımı"
            // görüp sebebi tahmin etmeye çalıştı.
            if (!"PASS".equals(status)) {
                String ctx = runContextNote(script, timeoutSec, viaProxy, caFile != null);
                error = (error == null ? "" : error + "\n") + ctx;
            }
            return buildResult(status, durationMs, r, s, output, error, viaProxy.on());
        } catch (Exception e) {
            // ── Katman 1: dış guard — r/output/durationMs artık KAPSAMDA ──
            log.error("Senaryo koşumu beklenmeyen istisnayla bitti", e);
            return errWithContext(safeMsg(e, secretValues), r, output, durationMs, secretValues, viaProxy.on());
        } finally {
            deleteQuiet(scriptFile);
            deleteQuiet(summaryFile);
            deleteQuiet(caFile);
        }
    }

    /**
     * Başarısız koşumun ALTINA yazılan bağlam satırı — teşhisin başladığı yer.
     *
     * <p>Ters bütçe varsa (istek timeout'u ≥ süreç bütçesi) önce O anlatılır: sebebin neden hiç
     * yazılamadığını açıklayan tek şey odur ve kullanıcı bunu ekrandan başka hiçbir yerden göremez.
     */
    private String runContextNote(String script, int timeoutSec, ProxyUse viaProxy, boolean withCa) {
        return runContextNote(script, timeoutSec, viaProxy.on(), safeProxyTarget(), withCa);
    }

    /** Saf biçimlendirme — vekil adresi parametre olarak alınır ki birim testte örnek gerekmesin. */
    static String runContextNote(String script, int timeoutSec, boolean viaProxy,
                                 String proxyTarget, boolean withCa) {
        StringBuilder sb = new StringBuilder();
        for (String w : auditTimeoutBudget(script, timeoutSec)) sb.append(w).append('\n');

        Integer reqSec = maxRequestTimeoutSeconds(script);
        sb.append("süreç bütçesi=").append(timeoutSec).append("s · script istek timeout'u=")
          .append(reqSec == null ? "verilmemiş (k6 varsayılanı 60s)" : reqSec + "s")
          .append(" · çıkış=");
        if (viaProxy) {
            sb.append("vekil").append(proxyTarget == null || proxyTarget.isBlank() ? "" : " (" + proxyTarget + ")");
        } else {
            sb.append("doğrudan");
        }
        sb.append(" · kurumsal CA=").append(withCa ? "verildi" : "verilmedi");
        return sb.toString();
    }

    /** Vekil adresi teşhis için değerli ama çözümlemesi patlarsa koşum raporunu düşürmemeli. */
    private String safeProxyTarget() {
        try {
            String t = proxySettings.displayTarget();
            return (t == null || t.isBlank()) ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * k6'ya verilecek {@code --blacklist-ip} listesi — vekil kullanılıyorsa vekili kapsayan aralık düşürülür.
     *
     * <p>Neden şart: {@code --blacklist-ip} k6'nın dialer'ında uygulanır ve vekil kullanılırken k6
     * HEDEFE değil VEKİLE bağlanır. Kurumsal vekil iç ağdadır; {@code allow-internal-targets}
     * kapatılmış bir kurulumda kara liste vekili de keser ve "vekili açtım ama yine çalışmıyor"
     * durumu doğar — üstelik hata mesajı hedefi işaret ettiği için teşhisi zor.
     *
     * <p>Muafiyet YALNIZ vekilin kendi adresini kapsayan aralığa uygulanır; hedeflere yönelik SSRF
     * koruması aynen sürer (script hâlâ 169.254.169.254'e gidemez).
     */
    List<String> blacklistFor(boolean viaProxy) {
        List<String> cidrs = ssrfGuard.blacklistCidrs();
        if (!viaProxy) return cidrs;
        InetAddress[] proxyAddrs = proxySettings.resolveProxyAddresses();
        if (proxyAddrs.length == 0) return cidrs;
        List<String> out = new ArrayList<>();
        for (String cidr : cidrs) {
            boolean coversProxy = false;
            for (InetAddress a : proxyAddrs) {
                if (cidrContains(cidr, a)) { coversProxy = true; break; }
            }
            if (coversProxy) {
                log.info("[K6] Vekil ({}) kara liste aralığı {} içinde — bu aralık koşum için düşürüldü",
                        proxySettings.displayTarget(), cidr);
            } else {
                out.add(cidr);
            }
        }
        return out;
    }

    /** {@code 10.0.0.0/8} gibi bir CIDR verilen adresi kapsıyor mu? Ayrıştırılamayan CIDR → false (kapsamaz). */
    static boolean cidrContains(String cidr, InetAddress ip) {
        try {
            int slash = cidr.indexOf('/');
            if (slash < 0) return false;
            InetAddress net = InetAddress.getByName(cidr.substring(0, slash));
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            byte[] a = net.getAddress();
            byte[] b = ip.getAddress();
            if (a.length != b.length) return false;          // IPv4/IPv6 karışımı
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) if (a[i] != b[i]) return false;
            int rem = prefix % 8;
            if (rem == 0) return true;
            int mask = 0xFF << (8 - rem);
            return (a[fullBytes] & mask) == (b[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }

    /** Özet dosyasını güvenle ayrıştırır; okunamazsa {@code parsed=false} ile boş özet döner (sessiz DEĞİL). */
    private Summary safeParseSummary(Path summaryFile) {
        try {
            if (Files.size(summaryFile) > 0) return parseSummary(Files.readString(summaryFile), mapper);
            log.debug("k6 özet dosyası boş: {}", summaryFile);
        } catch (Exception e) {
            log.warn("k6 özet dosyası okunamadı ({}): {}", summaryFile, e.toString());
        }
        return new Summary();
    }

    /**
     * İstisnayı loglanabilir/gösterilebilir metne çevirir.
     * {@code getMessage()} null olabilir (eskiden "çalıştırma hatası: null" üretiyordu) ve istisna
     * mesajı bir env DEĞERİ gömebilir (bozuk URI vb.) — bu yüzden maskeleme burada da uygulanır.
     */
    private static String safeMsg(Throwable e, List<String> secretValues) {
        String msg = e.getMessage();
        String text = e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
        return SecretMask.maskValues(text, secretValues);
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { /* temp temizliği asla hata fırlatmaz */ }
    }

    /**
     * ALTYAPI ATLAMASI — kontrol hiç YÜRÜTÜLEMEDİ (k6 yok / havuz dolu / interrupt).
     *
     * <p>Bu, hedefin arızası DEĞİLDİR ve {@code ERROR} yazmak sahada pahalı bir yanlış üretiyordu:
     * {@code pool-size=2} olan bir podda 100 monitörlük filoda bir sweep'te ~94 monitör permit
     * alamayıp {@code ERROR} yazıyor, {@code up=false} olup teyit zincirine giriyor ve hedeflerin
     * hiçbirinde sorun yokken alarm üretiyordu (ya da toplu bastırma tetiklenip GERÇEK kesinti de
     * dâhil hiç alarm çıkmıyordu). Ayrıca {@code ok=false} satırı uptime rollup'ını kirletiyordu.
     *
     * <p>Sözleşme: {@code SKIPPED} statüsü olan sonuç ne kaydedilir ne alarm zincirine girer —
     * çağıran (scheduler) onu "bu turda koşmadı" olarak ele alır; {@code checked_at} eskimiş kalır,
     * ki bu da doğrudur: gerçekten kontrol edilmedi.
     *
     * <p>{@code durationMs=null, exitCode=-1} DOĞRU cevaptır: 0 ms "çok hızlı koştu" değil
     * "koşamadı" demek.
     */
    static final String STATUS_SKIPPED = "SKIPPED";

    private ScriptedResult err(String message) {
        skipped.increment();
        return new ScriptedResult(STATUS_SKIPPED, false, null, -1,
                null, null, null, null, null, null, null, message, false, Phases.EMPTY);
    }

    /** Sonuç "kontrol yürütülemedi" mi? (Kaydetme/alarm/rollup yollarının hepsi buna bakar.) */
    public static boolean isSkipped(ScriptedResult r) {
        return r != null && STATUS_SKIPPED.equals(r.status());
    }

    /**
     * Beklenmeyen istisnada elde olan HER ŞEYİ koruyarak sonuç üretir. {@code r} null ise davranış
     * {@link #err(String)} ile birebir aynıdır (regresyon yok); değilse gerçek çıkış kodu, ölçülen
     * süre ve k6 çıktısı kayda geçer — 289 koşumda kaybedilen tam olarak buydu.
     *
     * <p>{@code maskedOutput} çağrı yerinde zaten maskelenmiştir; buradaki ikinci {@code maskValues}
     * kasıtlı bir kemer+askıdır (idempotent, ucuz): bu yola ileride biri ham {@code r.output()}
     * geçirirse geri dönülmez bir secret sızıntısı olurdu.
     */
    private ScriptedResult errWithContext(String message, ProcessProbe.Result r,
                                          String maskedOutput, Long durationMs, List<String> secretValues,
                                          boolean viaProxy) {
        String status = (r != null && r.timedOut()) ? "TIMEOUT" : "ERROR";
        Integer exitCode = r != null ? r.exitCode() : -1;
        String out = SecretMask.maskValues(maskedOutput, secretValues);
        return new ScriptedResult(status, false, durationMs, exitCode,
                null, null, null, null, null, null, out, message, viaProxy, Phases.EMPTY);
    }

    /** Sonuç montajı — saf ve statik, böylece Spring'siz/k6'sız birim-test edilebilir. */
    static ScriptedResult buildResult(String status, Long durationMs, ProcessProbe.Result r,
                                      Summary s, String maskedOutput, String error, boolean viaProxy) {
        return new ScriptedResult(status, "PASS".equals(status), durationMs, r.exitCode(),
                s.checksPassed, s.checksFailed, s.iterationMs, s.httpReqAvgMs, s.httpReqP95Ms,
                s.checksJson, maskedOutput, error, viaProxy, Phases.of(s));
    }

    /**
     * {@code no-checks-policy}: NO_CHECKS filoda kaç monitörü etkileyeceği önceden bilinemez,
     * bu yüzden yeniden dağıtım gerektirmeyen bir kaçış kapısı var.
     * WARN (varsayılan) = NO_CHECKS (kayıt hatalı, alarm yok) · FAIL = alarm da üret ·
     * PASS = eski davranış (acil geri dönüş).
     */
    private String applyNoChecksPolicy(String status) {
        if (!"NO_CHECKS".equals(status)) return status;
        String policy = appSettings.getString("site.monitor.scripted.no-checks-policy", "WARN");
        if ("PASS".equalsIgnoreCase(policy)) return "PASS";
        return "NO_CHECKS";   // WARN ve FAIL aynı statü; ayrım alarm (up) bayrağında yapılır
    }

    /** NO_CHECKS alarm da üretsin mi (policy=FAIL)? Varsayılan: hayır — kayıt hatalı ama kimse çağrılmaz. */
    public boolean noChecksAlarms() {
        return "FAIL".equalsIgnoreCase(appSettings.getString("site.monitor.scripted.no-checks-policy", "WARN"));
    }

    /**
     * Statüyü kullanıcıya gösterilecek tek bir hata metnine çevirir.
     *
     * <p>Görünürlük paket-özel: TIMEOUT sözleşmesi ({@code ":\n"} ile eklenen sebep) hem testte
     * hem frontend'de ({@code scriptedExitCodes.diagnosisHint}) dayanak noktası — pinlenmesi şart.
     */
    static String summarizeError(String status, ProcessProbe.Result r, String output) {
        // Sebep ayıklaması TÜM dallardan ÖNCE yapılır: TIMEOUT'ta da gösterilecek.
        String lines = sanitizeScriptPath(extractErrorLines(output));
        if ("TIMEOUT".equals(status)) {
            // Süreci BİZ öldürdüğümüz için k6 çoğu kez sebebi yazmaya fırsat bulamaz — ama
            // asılmadan ÖNCE düşen istekler varsa sebep çıktıda DURUYORDU ve buraya hiç
            // taşınmıyordu. Prod'da 294 koşum "Süre aşımı" deyip susarken elimizde
            // `Request Failed — Post "http://…": request timeout` satırı vardı (2026-08'de
            // uçtan uca ölçüldü). Artık FAIL/ERROR ile aynı muamele.
            return "Süre aşımı — süreç sonlandırıldı" + (lines != null ? ":\n" + lines : "");
        }
        if ("NO_CHECKS".equals(status)) {
            return "Script hiç check() çalıştırmadı — koşum hiçbir şey doğrulamadı";
        }
        // exit 0 + ERROR: script iterasyon içinde patladı, k6 yine 0 ile çıktı. exitCodeLabel(0)
        // "başarılı" der; onu buraya yazmak "başarılı (çıkış 0): Error: ..." gibi kendini yalanlayan
        // bir mesaj üretirdi.
        String label = (r.exitCode() == 0 && !r.timedOut())
                ? "script çalışırken hata verdi (k6 yine çıkış 0 verdi)"
                : exitCodeLabel(r.exitCode(), r.timedOut());
        return switch (status) {
            // FAIL'de de sebep gösterilir. Eskiden yalnız "0✓/1✗" denirdi: isteği patlayan bir
            // monitör (bağlantı reddi, DNS, TLS, istek zaman aşımı) hiçbir sebep bildirmiyordu —
            // oysa k6 sebebi çıktıya yazmıştı, biz okumuyorduk.
            //
            // Çıkış 0'da kod ETİKETİ YAZILMAZ: exitCodeLabel(0) "başarılı" der ve ortaya
            // "k6 check/threshold başarısız — başarılı (çıkış 0)" gibi kendini yalanlayan bir
            // cümle çıkardı. Check'i düşen bir koşumun 0 ile çıkması zaten NORMALDİR; bilgi katmaz.
            case "FAIL"  -> "k6 check/threshold başarısız"
                            + (r.exitCode() == 0 && !r.timedOut() ? "" : " — " + exitCodeLabel(r.exitCode(), r.timedOut()))
                            + (lines != null ? ":\n" + lines : "");
            case "ERROR" -> label + (lines != null ? ":\n" + lines : sanitizeScriptPath(tailSuffix(output)));
            default      -> null;
        };
    }

    /**
     * k6 mesajlarındaki geçici script yolunu ({@code file:///…/k6-script-8471.js}) {@code script}
     * ile değiştirir. Hem kullanıcı için tamamen anlamsız, hem de sunucu dosya yolunu alarm
     * e-postasına taşıyor.
     */
    /**
     * Doğrulama koşumunun çıktısından KULLANICIYA gösterilecek metni üretir.
     *
     * <p>Sıra kritik ve üç adımın hepsi zorunlu:
     * <ol>
     *   <li><b>Maskele</b> — {@code extractErrorLines}'ın sözleşmesi "girdi zaten maskeli"dir
     *       ({@code execute()} bu sözleşmeye uyuyordu, doğrulama yolu UYMUYORDU). Buradan çıkan
     *       metin {@code ScriptDiagnostics.blocking}/{@code warnings} üzerinden doğrudan HTTP
     *       gövdesine gidiyor; vekil kimlikli ortamda k6 hata satırında vekil URL'ini basabildiği
     *       için parola 400 yanıtına düşüyordu.</li>
     *   <li><b>Ayıkla/çöz</b> — logfmt kaçışları çözülmeden kalırsa mesaj 65 satırlık babel
     *       yığınıyla ham hâlde yanıta girer.</li>
     *   <li><b>Yolu temizle</b> — geçici script/CA dosya yolları hem anlamsız hem de sunucunun
     *       dizin yapısını sızdırır. HAM YEDEK dalı da bu temizlikten geçer (eskiden geçmiyordu).</li>
     * </ol>
     */
    static String validationDetail(String rawOutput, List<String> secretValues) {
        String masked = SecretMask.maskValues(rawOutput, secretValues);
        String lines = sanitizeScriptPath(extractErrorLines(masked));
        return lines != null ? lines : sanitizeScriptPath(rawFallback(masked));
    }

    static String sanitizeScriptPath(String text) {
        if (text == null) return null;
        String out = text.replaceAll("(?:file:/{2,})?[^\\s\"']*k6-script-[0-9A-Za-z_-]+\\.js", "script");
        // Kurumsal CA geçici dosyası da aynı gerekçeyle gizlenir: kullanıcı için anlamsız ve
        // sunucunun dosya yolunu (dolayısıyla dizin yapısını) alarm e-postasına taşıyor.
        // TLS hatalarında k6 bu yolu SSL_CERT_FILE bağlamında basabiliyor.
        return out.replaceAll("(?:file:/{2,})?[^\\s\"']*k6-ca-[0-9A-Za-z_-]+\\.pem", "ca-bundle");
    }

    /** Hata satırı ayıklanamadığında eski davranış: çıktının son 400 karakteri. */
    private static String tailSuffix(String output) {
        String tail = output == null ? "" : output.strip();
        if (tail.length() > 400) tail = tail.substring(tail.length() - 400);
        return tail.isBlank() ? "" : ": " + tail;
    }

    // ── Saf/statik yardımcılar (birim-test edilebilir) ───────────────────────

    /** Karar tablosu: timeout→TIMEOUT; non-zero&non-99→ERROR; 99 veya failed-check→FAIL; else PASS. */
    static String decideStatus(int exitCode, boolean timedOut, int checksFailed) {
        if (timedOut) return "TIMEOUT";
        if (exitCode != 0 && exitCode != 99) return "ERROR";
        if (exitCode == 99 || checksFailed > 0) return "FAIL";
        return "PASS";
    }

    /**
     * Null-toleranslı sarmalayıcı — karar tablosu DEĞİŞMEZ, yalnız NPE kapanır.
     *
     * <p>k6 özetinde {@code metrics.checks} yoksa (hiç {@code check()} çalışmadıysa ya da özet
     * dosyası hiç yazılmadıysa) {@code checksFailed} null kalır ve primitive imzaya geçerken
     * auto-unboxing NPE atardı — argüman değerlendirilirken, metot gövdesine girmeden.
     *
     * <p>Null'ı burada 0 gibi ele almak bilinçli: "0 başarısız check" ile "hiç check çalışmadı"
     * aynı şey DEĞİL, ama bu ayrım {@code parseSummary}'nin sözleşmesine ait değil — o null'ı
     * korumalı ({@code ScriptedCheckerServiceTest} bunu pinliyor). Ayrımın statüye yansıması
     * ayrı bir iştir; burada yalnız çökme kapatılıyor.
     */
    static String decideStatus(int exitCode, boolean timedOut, Integer checksPassed, Integer checksFailed) {
        return decideStatus(exitCode, timedOut, checksPassed, checksFailed, false, false);
    }

    /**
     * Tam karar tablosu. Ek iki sinyal, "exit 0 ama hiç check çalışmadı" kovasını üçe ayırır —
     * bu ayrım olmadan üç farklı olay tek bir yanıltıcı PASS'e düşüyordu.
     *
     * <p><b>Neden gerekli (gerçek k6 v0.49 ölçümü):</b> {@code default} fonksiyonu içinde fırlatılan
     * bir istisna iterasyonu iptal eder ama k6 yine <b>0</b> ile çıkar ve özet JSON'unda
     * {@code metrics.checks} hiç oluşmaz. Yani her koşumda patlayan bir script "başarılı" görünürdü —
     * hem de %100 uptime'la ve alarmsız. k6 çıktısında {@code level=error} satırı dururken.
     *
     * <ul>
     *   <li>çıktıda hata satırı var → {@code ERROR} (script patladı, k6 yuttu)</li>
     *   <li>threshold tanımlı → {@code PASS} — {@code check()} kullanmayıp yalnız
     *       {@code options.thresholds} ile doğrulayan script MEŞRUDUR (düşerse exit 99 → FAIL)</li>
     *   <li>ikisi de yok → {@code NO_CHECKS} — koştu ama hiçbir şey doğrulanmadı</li>
     * </ul>
     */
    static String decideStatus(int exitCode, boolean timedOut, Integer checksPassed, Integer checksFailed,
                               boolean hasThresholds, boolean scriptErrored) {
        if (timedOut) return "TIMEOUT";
        if (exitCode != 0 && exitCode != 99) return "ERROR";
        if (exitCode == 99) return "FAIL";
        if (checksFailed != null && checksFailed > 0) return "FAIL";
        if (checksPassed == null && checksFailed == null) {
            if (scriptErrored) return "ERROR";
            if (!hasThresholds) return "NO_CHECKS";
        }
        return "PASS";
    }

    /**
     * k6 çıkış kodunu insan-okur etikete çevirir.
     * Kaynak: k6 {@code errext/exitcodes/codes.go}, v0.49.0 etiketi (imajdaki sürüm).
     */
    static String exitCodeLabel(int exitCode, boolean timedOut) {
        String name = switch (exitCode) {
            case 0   -> "başarılı";
            case 97  -> "k6 Cloud koşumu başarısız";
            case 98  -> "k6 Cloud ilerlemesi alınamadı";
            case 99  -> "threshold eşiği aşıldı";
            case 100 -> "setup() zaman aşımı";
            case 101 -> "teardown() zaman aşımı";
            case 102 -> "k6 iç zaman aşımı";
            case 103 -> "REST API'den durduruldu";
            case 104 -> "geçersiz k6 yapılandırması — script/options düzeltilmeli";
            // timedOut=false ÖNEMLİ: ProcessProbe kendi timeout'unda exitCode'u -1 yapar, yani 105
            // bizim sonlandırmamız OLAMAZ. Pod restart / OOM / node tahliyesi işaretidir.
            case 105 -> "dış sinyalle sonlandırıldı (pod yeniden başlatma, OOM sınırı veya node tahliyesi olabilir)";
            case 106 -> "k6 REST API portu açılamadı";
            case 107 -> "script çalışma-zamanı hatası";
            case 108 -> "script test.abort() ile durduruldu";
            case 109 -> "k6 iç hatası (panic)";
            case -1  -> timedOut ? "süreç sonlandırıldı" : "süreç başlatılamadı";
            default  -> null;
        };
        return name == null ? "çıkış kodu " + exitCode : name + " (çıkış " + exitCode + ")";
    }

    private static final int ERR_MAX_ENTRIES = 3;        // kaç k6 log satırı (çözülmeden önce)
    private static final int ERR_MAX_TOTAL_LINES = 14;   // çözüldükten sonra toplam metin satırı
    private static final int ERR_MAX_LINE_CHARS = 300;
    private static final int ERR_MAX_TOTAL_CHARS = 1500;

    /**
     * k6'nın logfmt satırından {@code msg="…"} alanının ÇÖZÜLMÜŞ değerini döndürür.
     *
     * <p>Neden gerekli: k6 hataları tek fiziksel satır olarak, logfmt tırnağı içinde basar —
     * satır sonları {@code \} + {@code n} şeklinde <b>iki karakterlik kaçış dizisi</b>dir. Bu yüzden
     * Babel'in kod çerçevesi ({@code > 46 | …} ve altındaki {@code ^} işareti) ekrana tek satır
     * hâlinde, kaçışlar görünür şekilde düşüyordu; 300 karakterlik kırpma da tam caret'in olduğu
     * yerde devreye girip hatanın <i>neresi</i> olduğunu yok ediyordu.
     *
     * <p>{@code time=}/{@code level=}/{@code hint=} alanları kullanıcı için değersiz olduğundan
     * atılır. Satırda {@code msg=} de {@code error=} de yoksa, ya da tırnak kapanmamışsa satır
     * <b>olduğu gibi</b> döner — bu fonksiyon hiçbir koşulda istisna fırlatmaz
     * ({@code validateScript} catch yolundan da çağrılıyor).
     *
     * <p><b>{@code error=} alanı neden şart:</b> k6 başarısız bir isteği
     * {@code level=warning msg="Request Failed" error="Post \"http://…\": request timeout"}
     * olarak basar. Asıl teşhis {@code msg}'de değil {@code error}'dadır; yalnız {@code msg}
     * alınsaydı kullanıcı "Request Failed" görüp neden başarısız olduğunu yine bilemezdi.
     */
    static String decodeK6LogLine(String line) {
        if (line == null) return null;
        Map<String, String> f = parseLogfmt(line);
        String msg = f.get("msg");
        String err = f.get("error");
        if (msg == null && err == null) return line;   // logfmt değil → dokunma
        if (msg == null) return err;
        if (err == null || msg.contains(err)) return msg;
        return msg + " — " + err;
    }

    /**
     * k6'nın logfmt satırını üst-seviye {@code anahtar=değer} çiftlerine ayırır.
     *
     * <p>Neden düz {@code indexOf("error=\"")} değil: {@code error=} dizisi bir başka alanın
     * TIRNAK İÇİ değerinde de geçebilir (k6 hata metinleri kendi içinde {@code error=} taşıyabiliyor);
     * naif arama yanlış alanı yakalar. Bu tarayıcı tırnak durumunu takip ettiği için yalnız gerçek
     * üst-seviye alanları görür.
     *
     * <p>Tırnak kapanmadan satır biterse o ana kadar toplananlar döner (kısmi logfmt); çağıran
     * {@code msg}/{@code error} bulamazsa satırı olduğu gibi bırakır.
     */
    static Map<String, String> parseLogfmt(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            int ks = i;
            while (i < n && line.charAt(i) != '=' && line.charAt(i) != ' ') i++;
            if (i >= n || line.charAt(i) != '=' || i == ks) {   // anahtarsız kelime → atla
                while (i < n && line.charAt(i) != ' ') i++;
                continue;
            }
            String key = line.substring(ks, i);
            i++;                                                 // '='
            StringBuilder val = new StringBuilder();
            if (i < n && line.charAt(i) == '"') {
                i++;
                boolean esc = false, closed = false;
                for (; i < n; i++) {
                    char c = line.charAt(i);
                    if (esc) {
                        switch (c) {
                            case 'n'  -> val.append('\n');
                            case 't'  -> val.append('\t');
                            case 'r'  -> val.append('\r');
                            case '"'  -> val.append('"');
                            case '\\' -> val.append('\\');
                            default   -> val.append('\\').append(c);   // tanımadığımız kaçış: bozma
                        }
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        i++; closed = true; break;
                    } else {
                        val.append(c);
                    }
                }
                if (!closed) return out;                         // tırnak kapanmadı
            } else {
                while (i < n && line.charAt(i) != ' ') val.append(line.charAt(i++));
            }
            out.put(key, val.toString());
        }
        return out;
    }

    /**
     * k6/Babel'in KENDİ iç yığın çerçevesi mi? Kullanıcı için sıfır değer taşır ve tek bir
     * sözdizimi hatasında 60+ satır üretip paneli boğar.
     *
     * <p>Kullanıcının kendi script'ine ait çerçeveler ({@code at file:///…/k6-script-1.js:12:5})
     * KORUNUR — asıl aranan bilgi onlardır.
     */
    private static boolean isInternalStackFrame(String line) {
        String s = line.strip();
        if (!s.startsWith("at ")) return false;
        return s.contains("<internal/") || s.endsWith("(native)");
    }

    /**
     * Maskeli k6 çıktısından ilk anlamlı hata satırlarını ayıklar; eşleşme yoksa {@code null}.
     *
     * <p>Neden: {@code summarizeError} eskiden çıktının SON 400 karakterini basıyordu — k6 hata
     * satırını değil, ondan sonraki özet gürültüsünü. Asıl sebep ekrana hiç çıkmıyordu.
     *
     * <p>Girdi ZATEN maskelenmiş olmalıdır: bu metnin çıktısı {@code scripted_checks.error}
     * kolonuna, oradan alarm mesajına ve e-postaya gidiyor. Sıra: {@code maskValues} →
     * {@code extractErrorLines} (içeride logfmt çözme) → kırpma. Kaçış çözme MASKELENMİŞ metin
     * üzerinde yapılır; tersi olsaydı maskeleme kaçırılmış bir secret'ı sonradan ortaya çıkarabilirdi.
     *
     * <p>Girinti bilinçli olarak KORUNUR (yalnız sağ taraf kırpılır): Babel'in {@code ^} caret'i
     * bir üstteki kod satırıyla sütun sütun hizalıdır, soldaki boşluk gidince hata "neresi"
     * bilgisini kaybeder.
     *
     * <p>Bilinen sınır 1: {@code outputTail} zaten son 8 KiB'dır; daha uzun çıktıda gerçek İLK hata
     * bu pencerenin dışında kalmış olabilir. Alternasyonlu regex yerine {@code contains} kullanılır
     * (8 KiB metinde ReDoS yüzeyi açmamak için). Hiçbir koşulda istisna fırlatmaz — catch yolundan
     * da çağrılabilir.
     *
     * <p>Bilinen sınır 2: yalnız EŞLEŞEN satırlar alınır; bir hatanın devamı AYRI fiziksel satırlara
     * yayılmışsa devam satırları görülmez. Pratikte sorun değil, çünkü k6 boruya (non-TTY) yazarken
     * logfmt kullanır ve yığının tamamını tek {@code msg="…"} alanına koyar — {@code ERRO[…]} çok
     * satırlı biçimi yalnız TTY'de çıkar, alt-sürecimiz ise hiçbir zaman TTY değildir.
     */
    static String extractErrorLines(String maskedOutput) {
        if (maskedOutput == null || maskedOutput.isBlank()) return null;
        List<String> picked = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int total = 0, entries = 0, hiddenFrames = 0;
        outer:
        for (String raw : maskedOutput.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            // level=warning + error= : k6 BAŞARISIZ İSTEĞİ uyarı seviyesinde basıyor
            // (msg="Request Failed" error="Post \"http://…\": request timeout"). En sık gerçek
            // arıza budur — bağlantı reddi, DNS, TLS, istek zaman aşımı hep buradan gelir.
            // `error=` şartı bilinçli: k6 başka konularda da uyarı basıyor, onlar gürültü.
            boolean hit = line.contains("ERRO[")
                    || line.contains("GoError:")
                    || lower.contains("level=error")
                    || (lower.contains("level=warning") && line.contains("error="));
            if (!hit) continue;
            String decoded = decodeK6LogLine(line);
            if (seen.contains(decoded)) continue;   // setup()+default() aynı GoError'ı iki kez basabilir
            seen.add(decoded);
            for (String frag : decoded.split("\\R")) {
                String f = frag.stripTrailing();
                if (f.isBlank()) continue;
                if (isInternalStackFrame(f)) { hiddenFrames++; continue; }
                if (f.length() > ERR_MAX_LINE_CHARS) f = f.substring(0, ERR_MAX_LINE_CHARS) + "…";
                if (picked.size() >= ERR_MAX_TOTAL_LINES || total + f.length() > ERR_MAX_TOTAL_CHARS) break outer;
                picked.add(f);
                total += f.length();
            }
            if (++entries >= ERR_MAX_ENTRIES) break;
        }
        if (picked.isEmpty()) return null;
        // Sessiz kırpma yok: kaç satır saklandığı söylenir. Nereye bakılacağı YAZILMAZ — bu metin
        // hem kontrol geçmişine (teknik detay paneli VAR) hem kaydetme hatasına (panel YOK) gidiyor.
        if (hiddenFrames > 0) picked.add("… " + hiddenFrames + " k6 iç yığın satırı gizlendi");
        return String.join("\n", picked);
    }

    static final class Summary {
        Integer checksPassed, checksFailed;
        Long iterationMs, httpReqAvgMs, httpReqP95Ms;
        /**
         * İsteğin FAZ kırılımı — "nerede takıldı?" sorusunun tek ölçülebilir cevabı.
         *
         * <p>k6 bu değerleri her koşumda {@code --summary-export} JSON'una yazıyordu; 2026-08'e kadar
         * yalnız {@code http_req_duration} ve {@code iteration_duration} okunup gerisi ATILIYORDU.
         * Sonuç: sahada 288 koşum boyunca "request timeout" görülüyor ama DNS mi, TCP mi, TLS mi,
         * yanıt bekleme mi olduğu kod tabanından cevaplanamıyordu — teşhis tahmine kalıyordu.
         *
         * <p>Sıra anlamlıdır: blocked (DNS + bağlantı bekleme) → connecting (TCP) → tls (el sıkışma)
         * → sending → waiting (TTFB) → receiving.
         *
         * <p><b>Yorumlama (k6 v0.49 ile ölçüldü, varsayım değil):</b> k6 fazların TAMAMINI her
         * koşumda basar; girilmemiş faz {@code 0} gelir, metrik eksilmez. Bu yüzden "nereye kadar
         * gelindi" sorusunun cevabı SON SIFIR-OLMAYAN fazdır, ilk sıfır DEĞİL — DNS önbellekliyse
         * {@code blocked} pekâlâ 0 okunabiliyor. Buradaki {@code null} ise "k6 özeti hiç
         * okunamadı" demektir (bozuk/eksik JSON), "faza girilmedi" değil.
         */
        Long blockedMs, connectingMs, tlsMs, sendingMs, waitingMs, receivingMs;
        /** Ağ düzeyinde taşınan byte (TLS dâhil) — "hiç yanıt gelmedi" ile "kısa yanıt geldi"yi ayırır. */
        Long dataSent, dataReceived;
        /** k6'nın kendi başarısız-istek sayacı ({@code http_req_failed.passes}). */
        Integer httpReqFailed;
        String checksJson;
        /** Özet JSON gerçekten okunup ayrıştırılabildi mi — "bozuk özet" ile "hiç özet"i ayırır. */
        boolean parsed;
        /** Script {@code options.thresholds} tanımlamış mı — check'siz ama MEŞRU script'i ayırır. */
        boolean hasThresholds;
    }

    /**
     * k6 özetindeki check adlarını GRUPLARIN İÇİ DÂHİL toplar.
     *
     * <p>Eskiden yalnız {@code root_group.checks} okunuyordu; oysa {@code group('...')} içindeki
     * check'ler {@code root_group.groups.<ad>.checks} altında durur ve kök boş kalır. Sonuç:
     * projenin KENDİ "Kritik iş akışı" şablonu (üç adım, her biri {@code group()}) ile kurulan
     * bir monitörde adım düştüğünde durum doğru (FAIL) ama {@code checksJson} null oluyor,
     * dolayısıyla alarm e-postasındaki "Başarısız Check'ler" listesi BOŞ gidiyordu — nöbetçi
     * hangi adımın düştüğünü göremiyordu. Şablonun ilan ettiği faydanın tam kaybı.
     *
     * <p>Grup adı check adının önüne eklenir ({@code "2) arama › ürün listelendi"}) — aynı check
     * adı farklı gruplarda tekrarlanabildiği için ayrım şart.
     */
    private static void collectChecks(JsonNode group, String prefix, List<Map<String, Object>> out) {
        JsonNode checks = group.path("checks");
        if (checks.isObject()) {
            checks.fields().forEachRemaining(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", prefix == null ? e.getKey() : prefix + " › " + e.getKey());
                row.put("passed", e.getValue().path("fails").asInt(0) == 0);
                out.add(row);
            });
        }
        JsonNode groups = group.path("groups");
        if (groups.isObject()) {
            groups.fields().forEachRemaining(g ->
                    collectChecks(g.getValue(), prefix == null ? g.getKey() : prefix + " › " + g.getKey(), out));
        }
    }

    /** Trend metriğinin ortalaması (ms). Metrik HİÇ yoksa null — özet okunamadı demektir
     *  ("faza girilmedi" DEĞİL: k6 girilmemiş fazı 0 basar, metriği eksiltmez). */
    private static Long avgOf(JsonNode metrics, String name) {
        JsonNode m = metrics.path(name);
        return m.has("avg") ? Math.round(m.path("avg").asDouble()) : null;
    }

    /** Sayaç metriğinin toplamı (byte). Metrik yoksa null. */
    private static Long countOf(JsonNode metrics, String name) {
        JsonNode m = metrics.path(name);
        return m.has("count") ? Math.round(m.path("count").asDouble()) : null;
    }

    /** k6 {@code --summary-export} JSON'unu ayrıştırır (null-toleranslı). */
    static Summary parseSummary(String json, ObjectMapper mapper) {
        Summary s = new Summary();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode metrics = root.path("metrics");
            s.parsed = metrics.isObject();
            // Threshold VARLIĞI (gerçek 0.49 çıktısı: metrics.<ad>.thresholds = {"p(95)<10000": false}).
            // Yalnız varlığa bakılır — boolean'ın anlamı yanıltıcı (yukarıdaki örnekte eşik GEÇTİ).
            if (metrics.isObject()) {
                for (JsonNode m : metrics) {
                    if (m.path("thresholds").isObject() && !m.path("thresholds").isEmpty()) {
                        s.hasThresholds = true;
                        break;
                    }
                }
            }
            JsonNode checks = metrics.path("checks");
            if (checks.has("passes")) s.checksPassed = checks.path("passes").asInt();
            if (checks.has("fails"))  s.checksFailed = checks.path("fails").asInt();
            JsonNode hrd = metrics.path("http_req_duration");
            if (hrd.has("avg"))     s.httpReqAvgMs = Math.round(hrd.path("avg").asDouble());
            if (hrd.has("p(95)"))   s.httpReqP95Ms = Math.round(hrd.path("p(95)").asDouble());
            JsonNode itd = metrics.path("iteration_duration");
            if (itd.has("avg"))     s.iterationMs = Math.round(itd.path("avg").asDouble());
            // Faz kırılımı: her metrik yalnız o faza GİRİLDİYSE üretilir. Yokluk da bilgidir.
            s.blockedMs    = avgOf(metrics, "http_req_blocked");
            s.connectingMs = avgOf(metrics, "http_req_connecting");
            s.tlsMs        = avgOf(metrics, "http_req_tls_handshaking");
            s.sendingMs    = avgOf(metrics, "http_req_sending");
            s.waitingMs    = avgOf(metrics, "http_req_waiting");
            s.receivingMs  = avgOf(metrics, "http_req_receiving");
            // data_sent/data_received sayaç (count) metrikleridir, süre değil.
            s.dataSent     = countOf(metrics, "data_sent");
            s.dataReceived = countOf(metrics, "data_received");
            JsonNode failed = metrics.path("http_req_failed");
            if (failed.has("passes")) s.httpReqFailed = failed.path("passes").asInt();
            // Per-check adları — grupların İÇİ DÂHİL (bkz. collectChecks).
            List<Map<String, Object>> list = new ArrayList<>();
            collectChecks(root.path("root_group"), null, list);
            if (!list.isEmpty()) s.checksJson = mapper.writeValueAsString(list);
        } catch (Exception e) {
            // Sessiz DEĞİL: bozuk özet ile hiç özet arasındaki fark tanı için önemli.
            log.debug("k6 özeti ayrıştırılamadı: {}", e.toString());
        }
        return s;
    }

    private List<EnvVar> parseEnv(String json, boolean decrypt) {
        List<EnvVar> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) return out;
            for (JsonNode n : arr) {
                String name = n.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                boolean secret = n.path("secret").asBoolean(false);
                String raw = n.path("value").asText("");
                String value = (secret && decrypt) ? cipher.decrypt(raw) : raw;
                out.add(new EnvVar(name, value == null ? "" : value, secret));
            }
        } catch (Exception e) {
            log.warn("Sentetik izleme env JSON ayrıştırılamadı: {}", e.getMessage());
        }
        return out;
    }

    /** Script gövdesinde sabit-kodlu secret literal'i tespiti (kaydetme kontrolü). Döner: eşleşen anahtar adları. */
    private static final Pattern HARDCODED = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|apikey|token|credential|bearer|authorization)\\s*[:=]\\s*[\"'][^\"']{6,}[\"']");

    public static List<String> scanHardcodedSecrets(String script) {
        List<String> hits = new ArrayList<>();
        if (script == null || script.isBlank()) return hits;
        var m = HARDCODED.matcher(script);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            if (!hits.contains(key)) hits.add(key);   // DEĞER değil yalnız anahtar-adı raporlanır (sızıntı yok)
        }
        return hits;
    }
}
