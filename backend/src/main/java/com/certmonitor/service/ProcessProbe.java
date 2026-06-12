package com.certmonitor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    public record Result(String output, int exitCode, boolean timedOut) {}

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
            });
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
            catch (Exception e) { bytes = new byte[0]; }
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
