package com.sitemonitor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Deadlock-safe harici komut çalıştırıcı — tanılama servisleri (openssl, ağ)
 * için ortak. KRİTİK: stdout AYRI bir thread'de okunur; aksi halde komut
 * hedefe bağlanamayıp stdout'u kapatmadığında readAllBytes() sonsuza kadar
 * bloke olur ve waitFor timeout'u hiç tetiklenemez. Süre dolunca süreç zorla
 * öldürülür → stream EOF alır → okuma thread'i biter.
 *
 * GÜVENLİK: yalnız ProcessBuilder(List) — kabuk YOK, argümanlar dizi olarak
 * geçer (komut enjeksiyonu imkânsız). Çağıranlar domain/port'u doğrular.
 */
public final class ProcessProbe {

    private ProcessProbe() {}

    /**
     * stdout okuma thread'leri için ayrı daemon havuz. Ortak ForkJoinPool
     * (commonPool) KULLANILMAZ: bloke readAllBytes commonPool'u açlığa sokup
     * uygulama geneli paralel stream'leri yavaşlatabilir. Daemon thread'ler JVM
     * kapanışını engellemez; cached havuz boştaki thread'leri 60 sn'de bırakır.
     */
    private static final ExecutorService READER_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "process-probe-reader");
        t.setDaemon(true);
        return t;
    });

    public record Result(String output, int exitCode, boolean timedOut) {}

    /** SIGTERM→grace→SIGKILL sonlandırma; readAllBytes'in üst-sınırı (OOM koruması). */
    private static final int GRACE_SECONDS = 3;
    private static final int HARD_READ_CAP = 512 * 1024;

    /**
     * İZOLE ORTAM beyaz-listesi — {@code isolatedEnv} istendiğinde alt sürece geçirilecek TEK
     * ortam değişkenleri (çağıranın açıkça verdiği {@code env} bunun üstüne eklenir).
     *
     * <p>Neden: {@code ProcessBuilder.environment()} JVM'in ortamının KOPYASIYLA başlar. k6 gibi
     * kullanıcı script'i çalıştıran bir alt süreçte bu, {@code __ENV} üzerinden pod'un tüm ortamını
     * (DB parolası, SMTP kimliği, servis token'ları) script'e açar — {@code http.post(dışarı,
     * JSON.stringify(__ENV))} tek satırla sızdırır. Ayrıca sürpriz bir yan etkisi vardı: pod'daki
     * {@code NO_PROXY} sessizce miras alınıp Go'nun vekil kararını değiştiriyordu; artık vekil
     * değişkenlerinin tamamını çağıran açıkça verir.
     *
     * <p>Liste, sürecin AYAĞA KALKMASI için gerekenlerle sınırlıdır (Windows'ta {@code SystemRoot}
     * yoksa süreç oluşturma ve DNS bozulur). Karşılaştırma büyük/küçük harf duyarsızdır.
     */
    private static final java.util.Set<String> ENV_ALLOWLIST = java.util.Set.of(
            "PATH", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR",
            "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATHEXT",
            "TEMP", "TMP", "USERPROFILE", "NUMBER_OF_PROCESSORS", "OS",
            "PROGRAMDATA", "LOCALAPPDATA", "APPDATA");

    /**
     * Env-enjeksiyonlu, çalışma-dizinli, kibar-sonlandırmalı (SIGTERM→grace→SIGKILL) ve çıktı-sınırlı (son
     * {@code maxOutputBytes} byte) çalıştırıcı — k6 gibi uzun-koşabilen sandboxlu süreçler için. Timeout'ta
     * zombie kalmaz. Çağıran temp dosyalarını kendi {@code finally}'sinde temizler.
     */
    public static Result run(List<String> args, java.util.Map<String, String> env, java.io.File cwd,
                             int timeoutSeconds, boolean gracefulTerm, int maxOutputBytes) {
        return run(args, env, cwd, timeoutSeconds, gracefulTerm, maxOutputBytes, false);
    }

    /**
     * @param isolatedEnv true ise alt süreç JVM'in ortamını MİRAS ALMAZ; yalnız {@link #ENV_ALLOWLIST}
     *                    ve çağıranın verdiği {@code env} geçer. Kullanıcı kodu çalıştıran süreçler
     *                    (k6) için zorunlu.
     */
    public static Result run(List<String> args, java.util.Map<String, String> env, java.io.File cwd,
                             int timeoutSeconds, boolean gracefulTerm, int maxOutputBytes,
                             boolean isolatedEnv) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            if (cwd != null) pb.directory(cwd);
            if (isolatedEnv) {
                java.util.Map<String, String> inherited = new java.util.LinkedHashMap<>(pb.environment());
                pb.environment().clear();
                inherited.forEach((k, v) -> {
                    if (k != null && ENV_ALLOWLIST.contains(k.toUpperCase(java.util.Locale.ROOT)))
                        pb.environment().put(k, v);
                });
            }
            if (env != null && !env.isEmpty()) pb.environment().putAll(env);
            proc = pb.start();
            final Process p = proc;
            CompletableFuture<byte[]> reader = CompletableFuture.supplyAsync(() -> {
                try { return p.getInputStream().readNBytes(HARD_READ_CAP); }
                catch (IOException e) { return new byte[0]; }
            }, READER_POOL);

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            boolean timedOut = false;
            if (!finished) {
                timedOut = true;
                if (gracefulTerm) {
                    proc.destroy();                                        // SIGTERM — kibar
                    if (!proc.waitFor(GRACE_SECONDS, TimeUnit.SECONDS))
                        proc.destroyForcibly();                            // SIGKILL — kesin
                } else {
                    proc.destroyForcibly();
                }
                proc.waitFor(GRACE_SECONDS, TimeUnit.SECONDS);             // ölümü bekle → stream EOF
            }
            byte[] bytes;
            try { bytes = reader.get(GRACE_SECONDS, TimeUnit.SECONDS); }
            catch (Exception e) { bytes = new byte[0]; reader.cancel(true); }
            // Son maxOutputBytes'ı tut (hata k6 çıktısının sonundadır).
            int cap = Math.max(0, maxOutputBytes);
            if (cap > 0 && bytes.length > cap)
                bytes = java.util.Arrays.copyOfRange(bytes, bytes.length - cap, bytes.length);
            String output = new String(bytes, StandardCharsets.UTF_8);
            return new Result(output, timedOut ? -1 : proc.exitValue(), timedOut);
        } catch (IOException e) {
            return new Result("komut çalıştırılamadı: " + e.getMessage(), -1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            return new Result("kesintiye uğradı", -1, true);
        }
    }

    public static Result run(List<String> args, String stdin, int timeoutSeconds) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true); // stdout+stderr birlikte (araçlar stderr'e bilgi basar)
            proc = pb.start();
            final Process p = proc;
            CompletableFuture<byte[]> reader = CompletableFuture.supplyAsync(() -> {
                try { return p.getInputStream().readAllBytes(); }
                catch (IOException e) { return new byte[0]; }
            }, READER_POOL);
            if (stdin != null) {
                try (var os = proc.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignore) { /* süreç stdin'i erken kapatmış olabilir */ }
            }
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            boolean timedOut = false;
            if (!finished) {
                timedOut = true;
                proc.destroyForcibly();
                proc.waitFor(3, TimeUnit.SECONDS); // ölümü bekle → stream EOF
            }
            byte[] bytes;
            try { bytes = reader.get(3, TimeUnit.SECONDS); }
            catch (Exception e) {
                bytes = new byte[0];
                reader.cancel(true); // okuma thread'ini bırak (süreç zaten öldürüldü → stream EOF)
            }
            String output = new String(bytes, StandardCharsets.UTF_8)
                    + (timedOut ? "\n[zaman aşımı: " + timeoutSeconds + "s — komut sonlandırıldı]" : "");
            return new Result(output, timedOut ? -1 : proc.exitValue(), timedOut);
        } catch (IOException e) {
            // binary yok / çalıştırılamadı
            return new Result("komut çalıştırılamadı: " + e.getMessage(), -1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            return new Result("kesintiye uğradı", -1, true);
        }
    }
}
