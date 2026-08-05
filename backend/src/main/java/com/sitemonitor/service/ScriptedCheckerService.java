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

import java.nio.charset.StandardCharsets;
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

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int ABS_MAX_TIMEOUT = 180;   // mutlak tavan (sn)

    private Semaphore permits;
    private ExecutorService execPool;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean k6Available = false;
    private volatile String k6Version = null;

    // ── Sonuç + env tipleri ──────────────────────────────────────────────────
    public record EnvVar(String name, String value, boolean secret) {}
    public record ScriptedResult(String status, boolean ok, long durationMs, Integer exitCode,
                                 Integer checksPassed, Integer checksFailed, Long iterationMs,
                                 Long httpReqAvgMs, Long httpReqP95Ms, String checksJson,
                                 String outputTail, String error) {}

    @PostConstruct
    public void init() {
        int pool = Math.max(1, appSettings.getInt("site.monitor.scripted.pool-size", 2));
        permits = new Semaphore(pool);
        execPool = Executors.newVirtualThreadPerTaskExecutor();
        Gauge.builder("scripted.k6.active", active, AtomicInteger::get)
                .description("Şu an çalışan k6 alt süreç sayısı").register(registry);
        Gauge.builder("scripted.k6.queued", queued, AtomicInteger::get)
                .description("k6 havuzunda bekleyen kontrol sayısı").register(registry);
        probeK6();
    }

    @PreDestroy
    public void shutdown() {
        if (execPool != null) execPool.shutdownNow();
    }

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

    public boolean isAvailable() { return k6Available; }
    public String version() { return k6Version; }
    public int activeProcesses() { return active.get(); }
    public int queuedChecks() { return queued.get(); }

    private String k6Bin() { return appSettings.getString("site.monitor.scripted.k6-bin", "k6"); }

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** Kaydedilmiş monitör (scheduler/manuel). */
    public ScriptedResult run(ScriptedMonitor m) {
        return runGuarded(m.getScript(), parseEnv(m.getEnvJson(), true), clampTimeout(m.getTimeoutSeconds()));
    }

    /** Scheduler fan-out: ayrı executor'a submit → scheduler thread'i bloklanmaz. */
    public Future<ScriptedResult> submit(ScriptedMonitor m) {
        queued.incrementAndGet();
        return execPool.submit(() -> runGuardedAfterQueue(m.getScript(), parseEnv(m.getEnvJson(), true), clampTimeout(m.getTimeoutSeconds())));
    }

    /** Kaydetmeden tek seferlik test — env JSON ham (secret değerleri düz gelir, henüz şifreli değil). */
    public ScriptedResult test(String script, String envJson, Integer timeoutSeconds) {
        return runGuarded(script, parseEnv(envJson, false), clampTimeout(timeoutSeconds));
    }

    int clampTimeout(Integer t) {
        int v = (t == null) ? appSettings.getInt("site.monitor.scripted.default-timeout-seconds", 60) : t;
        int max = Math.min(ABS_MAX_TIMEOUT, appSettings.getInt("site.monitor.scripted.max-timeout-seconds", 60));
        return Math.max(5, Math.min(max, v));
    }

    private ScriptedResult runGuarded(String script, List<EnvVar> env, int timeoutSec) {
        queued.incrementAndGet();
        return runGuardedAfterQueue(script, env, timeoutSec);
    }

    private ScriptedResult runGuardedAfterQueue(String script, List<EnvVar> env, int timeoutSec) {
        if (!k6Available) { queued.decrementAndGet(); return err("k6 bulunamadı — Sentetik İzleme devre dışı"); }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeoutSec + 30L, TimeUnit.SECONDS);
            queued.decrementAndGet();
            if (!acquired) return err("k6 havuzu dolu — kontrol atlandı (sıra beklemesi aşıldı)");
            active.incrementAndGet();
            return execute(script, env, timeoutSec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queued.decrementAndGet();
            return err("kesintiye uğradı");
        } finally {
            if (acquired) { active.decrementAndGet(); permits.release(); }
        }
    }

    // ── Çalıştırma ───────────────────────────────────────────────────────────

    private ScriptedResult execute(String script, List<EnvVar> envVars, int timeoutSec) {
        Path scriptFile = null, summaryFile = null;
        try {
            scriptFile = Files.createTempFile("k6-script-", ".js");
            summaryFile = Files.createTempFile("k6-summary-", ".json");
            Files.writeString(scriptFile, script == null ? "" : script);

            Map<String, String> env = new LinkedHashMap<>();
            List<String> secretValues = new ArrayList<>();
            for (EnvVar v : envVars) {
                if (v.name() == null || v.name().isBlank()) continue;
                env.put(v.name(), v.value() == null ? "" : v.value());
                if (v.secret() && v.value() != null && !v.value().isBlank()) secretValues.add(v.value());
            }

            List<String> args = new ArrayList<>(List.of(
                    k6Bin(), "run", "--quiet", "--no-usage-report",
                    "--summary-export=" + summaryFile.toAbsolutePath(),
                    "--vus", "1", "--iterations", "1"));
            for (String cidr : ssrfGuard.blacklistCidrs()) { args.add("--blacklist-ip"); args.add(cidr); }
            args.add(scriptFile.toAbsolutePath().toString());

            int tailBytes = appSettings.getInt("site.monitor.scripted.output-tail-bytes", 8192);
            long t0 = System.currentTimeMillis();
            ProcessProbe.Result r = ProcessProbe.run(args, env, scriptFile.getParent().toFile(), timeoutSec, true, tailBytes);
            long durationMs = System.currentTimeMillis() - t0;

            String output = SecretMask.maskValues(r.output(), secretValues);

            // Özet JSON (varsa) ayrıştır
            Summary s = new Summary();
            try {
                if (Files.size(summaryFile) > 0) s = parseSummary(Files.readString(summaryFile), mapper);
            } catch (Exception e) { /* JSON yok/bozuk → stdout'tan özetle */ }

            String status = decideStatus(r.exitCode(), r.timedOut(), s.checksFailed);
            boolean ok = "PASS".equals(status);
            String error = ok ? null : summarizeError(status, r, output);

            return new ScriptedResult(status, ok, durationMs, r.exitCode(),
                    s.checksPassed, s.checksFailed, s.iterationMs, s.httpReqAvgMs, s.httpReqP95Ms,
                    s.checksJson, output, error);
        } catch (Exception e) {
            return err("çalıştırma hatası: " + e.getMessage());
        } finally {
            deleteQuiet(scriptFile);
            deleteQuiet(summaryFile);
        }
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { /* temp temizliği asla hata fırlatmaz */ }
    }

    private ScriptedResult err(String message) {
        return new ScriptedResult("ERROR", false, 0, -1, null, null, null, null, null, null, null, message);
    }

    private static String summarizeError(String status, ProcessProbe.Result r, String output) {
        if ("TIMEOUT".equals(status)) return "Süre aşımı — süreç sonlandırıldı";
        String tail = output == null ? "" : output.strip();
        if (tail.length() > 400) tail = tail.substring(tail.length() - 400);
        return switch (status) {
            case "FAIL"  -> "k6 check/threshold başarısız (çıkış kodu " + r.exitCode() + ")";
            case "ERROR" -> "Script/çalışma hatası (çıkış kodu " + r.exitCode() + ")" + (tail.isBlank() ? "" : ": " + tail);
            default      -> null;
        };
    }

    // ── Saf/statik yardımcılar (birim-test edilebilir) ───────────────────────

    /** Karar tablosu: timeout→TIMEOUT; non-zero&non-99→ERROR; 99 veya failed-check→FAIL; else PASS. */
    static String decideStatus(int exitCode, boolean timedOut, int checksFailed) {
        if (timedOut) return "TIMEOUT";
        if (exitCode != 0 && exitCode != 99) return "ERROR";
        if (exitCode == 99 || checksFailed > 0) return "FAIL";
        return "PASS";
    }

    static final class Summary {
        Integer checksPassed, checksFailed;
        Long iterationMs, httpReqAvgMs, httpReqP95Ms;
        String checksJson;
    }

    /** k6 {@code --summary-export} JSON'unu ayrıştırır (null-toleranslı). */
    static Summary parseSummary(String json, ObjectMapper mapper) {
        Summary s = new Summary();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode metrics = root.path("metrics");
            JsonNode checks = metrics.path("checks");
            if (checks.has("passes")) s.checksPassed = checks.path("passes").asInt();
            if (checks.has("fails"))  s.checksFailed = checks.path("fails").asInt();
            JsonNode hrd = metrics.path("http_req_duration");
            if (hrd.has("avg"))     s.httpReqAvgMs = Math.round(hrd.path("avg").asDouble());
            if (hrd.has("p(95)"))   s.httpReqP95Ms = Math.round(hrd.path("p(95)").asDouble());
            JsonNode itd = metrics.path("iteration_duration");
            if (itd.has("avg"))     s.iterationMs = Math.round(itd.path("avg").asDouble());
            // Per-check adları: root_group.checks {name -> {passes,fails}}
            JsonNode rgChecks = root.path("root_group").path("checks");
            if (rgChecks.isObject()) {
                List<Map<String, Object>> list = new ArrayList<>();
                rgChecks.fields().forEachRemaining(e -> {
                    JsonNode c = e.getValue();
                    boolean passed = c.path("fails").asInt(0) == 0;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("passed", passed);
                    list.add(row);
                });
                if (!list.isEmpty()) s.checksJson = mapper.writeValueAsString(list);
            }
        } catch (Exception ignored) { /* bozuk JSON → boş özet */ }
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
