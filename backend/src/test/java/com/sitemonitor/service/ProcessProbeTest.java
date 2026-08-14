package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcessProbe gerçek süreç çalıştırma testi — OS'e uygun hızlı/yavaş komutla
 * çıktı yakalama ve deadlock-safe timeout davranışını doğrular.
 */
class ProcessProbeTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    @DisplayName("run: hızlı komut çıktısını yakalar, timedOut=false")
    void run_capturesOutput() {
        List<String> args = isWindows()
                ? List.of("cmd", "/c", "echo", "merhaba")
                : List.of("sh", "-c", "echo merhaba");
        ProcessProbe.Result r = ProcessProbe.run(args, null, 5);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output()).contains("merhaba");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    @DisplayName("run: uzun komut timeout'a uğrar ve sonlandırılır (deadlock yok)")
    void run_timesOut() {
        // ~5sn süren komut, 1sn timeout → öldürülmeli, asılı kalmamalı
        List<String> args = isWindows()
                ? List.of("ping", "-n", "6", "127.0.0.1")
                : List.of("sh", "-c", "sleep 5");
        long t0 = System.currentTimeMillis();
        ProcessProbe.Result r = ProcessProbe.run(args, null, 1);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(r.timedOut()).isTrue();
        assertThat(r.output()).contains("zaman aşımı");
        // 1s timeout + 3s kill payı; 10s'i aşmamalı (asılı kalmadığının kanıtı)
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    @DisplayName("run: olmayan binary → çalıştırılamadı (asılmaz)")
    void run_missingBinary() {
        ProcessProbe.Result r = ProcessProbe.run(List.of("this-binary-does-not-exist-xyz"), null, 5);
        assertThat(r.output()).contains("çalıştırılamadı");
        assertThat(r.exitCode()).isEqualTo(-1);
    }

    // ── İzole ortam (k6 yolu) ────────────────────────────────────────────────────────────────
    // ProcessBuilder.environment() JVM'in ortamının KOPYASIYLA başlar. k6 kullanıcı script'i
    // çalıştırdığı için bu, __ENV üzerinden pod'un tüm ortamını (DB parolası, SMTP, token'lar)
    // script'e açıyordu: `http.post(dışarı, JSON.stringify(__ENV))` tek satırla sızdırır.

    /** Ortam değişkenini basan, platforma uygun komut. */
    private static List<String> echoEnv(String name) {
        return isWindows()
                ? List.of("cmd", "/c", "echo", "%" + name + "%")
                : List.of("sh", "-c", "echo \"$" + name + "\"");
    }

    @Test
    @DisplayName("isolatedEnv: JVM'in ortamı alt sürece GEÇMEZ (secret sızıntı yolu kapalı)")
    void isolatedEnv_dropsInheritedVars() {
        // Bu değişken JVM'in ortamında yok; onu ancak çağıran açıkça verirse görebiliriz.
        // Miras davranışını kanıtlamak için beyaz-listede OLMAYAN bir isim seçilir.
        java.util.Map<String, String> given = java.util.Map.of("SM_TEST_LEAK", "sizmamali");

        ProcessProbe.Result inherited = ProcessProbe.run(
                echoEnv("SM_TEST_LEAK"), given, null, 5, true, 4096, false);
        ProcessProbe.Result isolated = ProcessProbe.run(
                echoEnv("SM_TEST_LEAK"), given, null, 5, true, 4096, true);

        // Açıkça VERİLEN değişken her iki modda da geçer — izolasyon çağıranın env'ini kesmez.
        assertThat(inherited.output()).contains("sizmamali");
        assertThat(isolated.output()).contains("sizmamali");
    }

    @Test
    @DisplayName("isolatedEnv: beyaz-liste DIŞI miras değişken alt süreçte görünmez")
    void isolatedEnv_hidesNonAllowlistedInheritedVar() {
        // JVM ortamında kesin var olan ve beyaz-listede OLMAYAN bir değişken bul.
        String probe = System.getenv().keySet().stream()
                .filter(k -> !java.util.Set.of("PATH", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR",
                        "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP",
                        "USERPROFILE", "NUMBER_OF_PROCESSORS", "OS", "PROGRAMDATA", "LOCALAPPDATA", "APPDATA")
                        .contains(k.toUpperCase(java.util.Locale.ROOT)))
                .filter(k -> !System.getenv(k).isBlank())
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(probe != null,
                "ortamda beyaz-liste dışı değişken yok — bu makinede doğrulanamaz");

        String expected = System.getenv(probe);
        ProcessProbe.Result isolated = ProcessProbe.run(echoEnv(probe), null, null, 5, true, 4096, true);

        assertThat(isolated.output()).doesNotContain(expected);
    }

    // ── Büyük çıktı: deadlock YOK, saklanan pencere SON byte'lar ────────────────────────────

    @Test
    @DisplayName("readTail: kapasiteyi aşan akışta SON byte'lar tutulur (hata sondadır)")
    void readTail_keepsTail() throws Exception {
        byte[] data = "0123456789ABCDEF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var in = new java.io.ByteArrayInputStream(data);

        byte[] out = ProcessProbe.readTail(in, 6);

        assertThat(new String(out, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("ABCDEF");
    }

    @Test
    @DisplayName("readTail: kapasiteden kısa akış aynen döner; boş akış boş döner")
    void readTail_shortStream() throws Exception {
        assertThat(new String(ProcessProbe.readTail(
                new java.io.ByteArrayInputStream("abc".getBytes()), 100))).isEqualTo("abc");
        assertThat(ProcessProbe.readTail(new java.io.ByteArrayInputStream(new byte[0]), 10)).isEmpty();
    }

    @Test
    @DisplayName("readTail: tek okuma kapasiteyi aşsa da kuyruk doğru (halka sarması)")
    void readTail_singleChunkLargerThanCap() throws Exception {
        byte[] big = new byte[20_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) ('a' + (i % 26));
        byte[] out = ProcessProbe.readTail(new java.io.ByteArrayInputStream(big), 100);

        assertThat(out).hasSize(100);
        assertThat(out).isEqualTo(java.util.Arrays.copyOfRange(big, big.length - 100, big.length));
    }

    @Test
    @DisplayName("REGRESYON: çok çıktı basan süreç TIMEOUT'a düşmez (boru dolup asılmaz)")
    void largeOutput_doesNotDeadlock() {
        // Eskiden okuyucu 512 KB'ta duruyordu: alt sürecin stdout borusu doluyor, süreç write()'ta
        // bloke kalıyor, waitFor süresi doluyor ve TÜM check'leri geçen bir script bile TIMEOUT
        // görüyordu. Burada ~1 MB üretiliyor; süreç normal bitmeli.
        List<String> args = isWindows()
                ? List.of("cmd", "/c", "for /L %i in (1,1,12000) do @echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                : List.of("sh", "-c", "i=0; while [ $i -lt 12000 ]; do echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; i=$((i+1)); done");

        ProcessProbe.Result r = ProcessProbe.run(args, null, null, 60, true, 4096, false);

        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitCode()).isZero();
        // Saklanan pencere çıktının SONU (eskiden başıydı — extractErrorLines hatayı hiç göremezdi).
        // strip(): platforma göre satır sonu CRLF/LF olabiliyor.
        assertThat(r.output().strip()).endsWith("aaaa");
        // 4 KiB tavanı uygulanmış olmalı (~1 MB üretildi)
        assertThat(r.output().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("İzole modda süreç yine ayağa kalkar (beyaz-liste PATH/SystemRoot'u korur)")
    void isolatedEnv_processStillStarts() {
        ProcessProbe.Result r = ProcessProbe.run(
                isWindows() ? List.of("cmd", "/c", "echo", "ok") : List.of("sh", "-c", "echo ok"),
                null, null, 5, true, 4096, true);
        assertThat(r.output()).contains("ok");
        assertThat(r.exitCode()).isZero();
    }
}
