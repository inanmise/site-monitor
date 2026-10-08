package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ping monitor checker — host'a ICMP ping atar (sistem {@code ping} komutu,
 * ProcessProbe ile deadlock-safe). Çıktıdan paket kaybı % + ortalama RTT parse
 * edilir. Container non-root ICMP'ye izin vermiyorsa (CAP_NET_RAW / ping_group_range
 * yok) zarif şekilde {@code na=true} döner. Komut deseni NetworkDiagnosticsService'ten.
 */
@Slf4j
@Service
public class PingCheckerService {

    // "0% packet loss" / "0% loss" / "%0 paket kaybı" gibi varyantlar
    private static final Pattern LOSS = Pattern.compile("(\\d+)%\\s*(?:packet\\s*)?loss", Pattern.CASE_INSENSITIVE);
    // iputils/busybox: "= 0.1/0.2/0.3[/0.0] ms" → avg = 2. grup
    private static final Pattern RTT_UNIX = Pattern.compile("=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)");
    // Windows: "Average = 12ms" / "Ortalama = 12ms"
    private static final Pattern RTT_WIN = Pattern.compile("(?:Average|Ortalama)\\s*=\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ── Girdi kuralları (2026-10-08, "doğrulanmadan alınan veri var mı?") ───────────────────────────
    // Host doğrudan `ping` argv'sine gider (kabuk YOK, yani komut enjeksiyonu yok) — ama '-' ile başlayan bir host
    // ping'e SEÇENEK olarak geçer (ör. "-f" flood). Paket sayısı kayıtta kırpılmıyordu; test ucu 1..10 kırparken
    // zamanlayıcı/tanılama ham değeri geçiriyordu.

    /** Paket sayısı sınırları — kayıt, test ucu ve koşum AYNI aralığı kullanır. */
    public static final int MIN_PACKETS = 1;
    public static final int MAX_PACKETS = 10;
    public static final int DEFAULT_PACKETS = 4;

    /** Ana bilgisayar adı (RFC 1123 etiketleri; iç ağ adları için '_' da kabul) — en çok 253 karakter, sondaki '.' serbest. */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?(?:\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*\\.?$");
    /** IPv6 değişmezi (köşeli parantezsiz; isteğe bağlı bölge kimliği {@code %eth0}). */
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[A-Za-z0-9_.-]{1,32})?$");

    /** Paket sayısını [1, 10] aralığına kırpar; null → 4. */
    public static int clampPackets(Integer count) {
        if (count == null) return DEFAULT_PACKETS;
        return Math.max(MIN_PACKETS, Math.min(MAX_PACKETS, count));
    }

    /** Host '-' ile mi başlıyor (ping'e seçenek olarak geçerdi)? Baştaki boşluk yok sayılır. */
    public static boolean startsWithDash(String host) {
        return host != null && host.strip().startsWith("-");
    }

    /** Yeni girdi için host biçimi geçerli mi: ana bilgisayar adı, IPv4 ya da IPv6 (şema/yol/boşluk/port yok). */
    public static boolean isValidHost(String host) {
        if (host == null) return false;
        String h = host.strip();
        if (h.isEmpty() || h.length() > 253 || h.startsWith("-")) return false;
        return HOSTNAME.matcher(h).matches() || (h.indexOf(':') >= 0 && IPV6.matcher(h).matches());
    }

    public static List<String> buildPingArgs(String host, String ipVersion, int count, int timeoutSec) {   // public: ping uçtan uca tanılaması (2026-10-05) aynı komutu kullanır
        List<String> a = new ArrayList<>();
        a.add("ping");
        if ("v4".equals(ipVersion)) a.add("-4");
        else if ("v6".equals(ipVersion)) a.add("-6");
        if (isWindows()) {
            a.add("-n"); a.add(String.valueOf(count));
            // Windows -w: paket başına ms bekleme
            a.add("-w"); a.add(String.valueOf(Math.max(1000, (timeoutSec * 1000) / Math.max(1, count))));
        } else {
            a.add("-c"); a.add(String.valueOf(count));
            a.add("-w"); a.add(String.valueOf(timeoutSec)); // iputils/busybox: toplam deadline (sn)
        }
        a.add(host);
        return a;
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String host, String ipVersion, int count, int timeoutMs) {
        return CompletableFuture.completedFuture(check(host, ipVersion, count, timeoutMs));
    }

    /** {"up", "rtt_ms"?, "packet_loss"?, "error"?, "na"?} döner. */
    public Map<String, Object> check(String host, String ipVersion, int count, int timeoutMs) {
        int timeoutSec = Math.max(2, (int) Math.ceil(timeoutMs / 1000.0) + 1);
        Map<String, Object> result = new LinkedHashMap<>();
        // Paket sayısı HER çağıranda aynı tavanla (2026-10-08): saklı eski kayıtta 50 olsa bile 10 paket atılır.
        int packets = clampPackets(count);

        // Eski kayıt güvenliği (2026-10-08): '-' ile başlayan host ping'e SEÇENEK olarak geçerdi → komut ÇALIŞTIRILMAZ,
        // kontrol açık bir hatayla başarısız sayılır (koşum düşmez; kullanıcı host'u düzeltince düzelir).
        if (host == null || host.isBlank() || startsWithDash(host)) {
            result.put("up", false);
            result.put("error", "Geçersiz host (boş ya da '-' ile başlıyor) — ping çalıştırılmadı; izlemenin host alanını düzeltin");
            try {
                com.sitemonitor.service.failure.CheckFailure.of(com.sitemonitor.service.failure.CheckFailureReason.CONFIG_ERROR)
                        .with("target", host)
                        .applyTo(result);
            } catch (Exception ignore) { /* üst veri */ }
            log.warn("Ping çalıştırılmadı — geçersiz host: '{}'", host);
            return result;
        }

        ProcessProbe.Result r = ProcessProbe.run(
                buildPingArgs(host, ipVersion, packets, timeoutSec), null, timeoutSec + 2);
        String out = r.output() != null ? r.output() : "";
        String low = out.toLowerCase(Locale.ROOT);

        // Binary yok / ortam ICMP'ye izin vermiyor → zarif N/A
        if (out.startsWith("komut çalıştırılamadı")
                || low.contains("operation not permitted")
                || low.contains("permission denied")
                || low.contains("socket operation")) {
            result.put("up", false);
            result.put("na", true);
            result.put("error", "ICMP bu ortamda kullanılamıyor (yetki/binary)");
            classify(result, true, null, out, host, ipVersion, packets, timeoutMs);
            log.debug("Ping unavailable for {}: {}", host, firstLine(out));
            return result;
        }

        Integer loss = match(LOSS, out, 1) != null ? Integer.valueOf(match(LOSS, out, 1)) : null;
        Long rtt = parseAvgRtt(out);
        boolean up = loss != null ? loss < 100 : (rtt != null);

        result.put("up", up);
        result.put("packet_loss", loss);
        result.put("rtt_ms", rtt);
        if (!up) {
            result.put("error", loss != null
                    ? "Yanıt yok (%" + loss + " paket kaybı)"
                    : firstLine(out));
            classify(result, false, loss, out, host, ipVersion, packets, timeoutMs);
        }
        return result;
    }

    /**
     * Hata teşhisi (2026-10-05): başarısız ping'in NEDENİ ({@code failure_reason} + {@code failure_detail}) — ICMP'nin
     * pod'da kullanılamaması, ad çözümlenemedi, yol yok, tüm paketler kayıp. Yalnız ÜST VERİ: up/na/error değerleri
     * yukarıda zaten belirlendi ve değişmez; sınıflandırma asla fırlatmaz.
     */
    private static void classify(Map<String, Object> result, boolean na, Integer loss, String out,
                                 String host, String ipVersion, int count, int timeoutMs) {
        try {
            com.sitemonitor.service.failure.CheckFailureClassifier.forPing(na, loss, out)
                    .with("target", host)
                    .with("ip_version", ipVersion)
                    .with("packets", Math.max(1, count))
                    .with("timeout_ms", timeoutMs)
                    .applyTo(result);
        } catch (Exception ignore) { /* üst veri — kontrol sonucu olduğu gibi kalır */ }
    }

    private static Long parseAvgRtt(String out) {
        Matcher mu = RTT_UNIX.matcher(out);
        if (mu.find()) {
            try { return Math.round(Double.parseDouble(mu.group(2))); } catch (NumberFormatException ignore) {}
        }
        Matcher mw = RTT_WIN.matcher(out);
        if (mw.find()) {
            try { return Long.parseLong(mw.group(1)); } catch (NumberFormatException ignore) {}
        }
        return null;
    }

    private static String match(Pattern p, String s, int g) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(g) : null;
    }

    private static String firstLine(String s) {
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) return t.length() > 160 ? t.substring(0, 160) : t;
        }
        return "yanıt yok";
    }
}
