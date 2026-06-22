package com.certmonitor.service;

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

    static List<String> buildPingArgs(String host, String ipVersion, int count, int timeoutSec) {
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

        ProcessProbe.Result r = ProcessProbe.run(
                buildPingArgs(host, ipVersion, Math.max(1, count), timeoutSec), null, timeoutSec + 2);
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
        }
        return result;
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
