package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ağ derin analizi — hedef domain'e dair temel ağ sorun-tespit komutlarını
 * (DNS, TCP port, curl izi, ip addr/route, ping, traceroute) çalıştırır.
 * Komutlar OS'e göre seçilir (Linux pod / Windows yerel) ve PARALEL koşar;
 * her biri ProcessProbe ile timeout-sınırlıdır. Eksik binary veya yetki
 * (ör. konteynerde ICMP) → status "na" ile zarif düşer.
 *
 * GÜVENLİK: tüm komutlar ProcessBuilder(List) — kabuk YOK; domain/port
 * controller'da doğrulanır (harf/rakam/nokta/tire, port 1-65535).
 */
@Slf4j
@Service
public class NetworkDiagnosticsService {

    @Value("${cert.monitor.diagnostics.network-timeout-seconds:10}")
    private int timeoutSeconds;

    private final ThreadPoolTaskExecutor executor;

    public NetworkDiagnosticsService(@Qualifier("certCheckExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String nullDevice() {
        return isWindows() ? "NUL" : "/dev/null";
    }

    public Map<String, Object> analyze(String domain, int port) {
        long start = System.currentTimeMillis();
        boolean win = isWindows();

        // Harici komutlar PARALEL — her biri ProcessProbe ile timeout-sınırlı
        record Cmd(String key, String label, List<String> args) {}
        List<Cmd> cmds = new ArrayList<>();
        cmds.add(new Cmd("nslookup", "nslookup", List.of("nslookup", domain)));
        if (!win) cmds.add(new Cmd("dig", "dig", List.of("dig", "+short", domain)));
        cmds.add(new Cmd("curl", "curl -v", buildCurlArgs(domain, port)));
        cmds.add(new Cmd("ip_addr", win ? "ipconfig" : "ip addr",
                win ? List.of("ipconfig", "/all") : List.of("ip", "addr")));
        cmds.add(new Cmd("ip_route", win ? "route print" : "ip route",
                win ? List.of("route", "print") : List.of("ip", "route")));
        cmds.add(new Cmd("ping", "ping", buildPingArgs(domain)));
        cmds.add(new Cmd("traceroute", win ? "tracert" : "traceroute", buildTracerouteArgs(domain)));

        Map<String, CompletableFuture<ProcessProbe.Result>> futures = new LinkedHashMap<>();
        for (Cmd c : cmds) {
            futures.put(c.key(), CompletableFuture.supplyAsync(
                    () -> ProcessProbe.run(c.args(), null, timeoutSeconds), executor));
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        // TCP port — saf Java Socket (telnet eşdeğeri; taşınabilir, yetki/kabuk yok)
        checks.add(tcpCheck(domain, port));

        for (Cmd c : cmds) {
            ProcessProbe.Result r = join(futures.get(c.key()));
            checks.add(commandCheck(c.key(), c.label(), c.args(), r));
        }

        long okCount = checks.stream().filter(m -> "ok".equals(m.get("status"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("port", port);
        out.put("os", win ? "windows" : "linux");
        out.put("checks", checks);
        out.put("ok_count", okCount);
        out.put("total", checks.size());
        out.put("elapsed_ms", System.currentTimeMillis() - start);
        return out;
    }

    private ProcessProbe.Result join(CompletableFuture<ProcessProbe.Result> f) {
        try {
            return f.get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ProcessProbe.Result("komut beklenirken hata: " + e.getMessage(), -1, true);
        }
    }

    // ── Komut kurma (saf, test edilebilir; OS'e göre) ─────────────────────────

    static List<String> buildCurlArgs(String domain, int port) {
        String scheme = (port == 80) ? "http" : "https";
        String url = scheme + "://" + domain + (port == 443 || port == 80 ? "" : ":" + port);
        return List.of("curl", "-sS", "-v", "--max-time", "8", "-o", nullDevice(), url);
    }

    static List<String> buildPingArgs(String domain) {
        return isWindows()
                ? List.of("ping", "-n", "4", domain)
                : List.of("ping", "-c", "4", "-w", "8", domain);
    }

    static List<String> buildTracerouteArgs(String domain) {
        return isWindows()
                ? List.of("tracert", "-h", "15", "-w", "2000", domain)
                : List.of("traceroute", "-m", "15", "-w", "2", "-q", "1", domain);
    }

    // ── TCP port — Java Socket (telnet eşdeğeri) ──────────────────────────────

    private Map<String, Object> tcpCheck(String domain, int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "tcp");
        m.put("label", "TCP " + port);
        m.put("command", "TCP connect " + domain + ":" + port);
        m.put("available", true);
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(domain, port), timeoutSeconds * 1000);
            long ms = System.currentTimeMillis() - t0;
            m.put("status", "ok");
            m.put("summary", "Port açık (" + ms + " ms)");
            m.put("output", domain + ":" + port + " → bağlandı (" + ms + " ms)");
        } catch (java.net.UnknownHostException e) {
            m.put("status", "fail");
            m.put("summary", "DNS çözümlenemedi");
            m.put("output", "UnknownHost: " + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            m.put("status", "fail");
            m.put("summary", "Zaman aşımı — port erişilemez");
            m.put("output", "timeout: " + domain + ":" + port);
        } catch (Exception e) {
            m.put("status", "fail");
            m.put("summary", "Bağlanılamadı (" + e.getClass().getSimpleName() + ")");
            m.put("output", e.getMessage());
        }
        return m;
    }

    // ── Komut sonucu → kontrol kaydı ──────────────────────────────────────────

    private Map<String, Object> commandCheck(String key, String label, List<String> args, ProcessProbe.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("command", String.join(" ", args));
        String out = r.output() != null ? r.output() : "";
        boolean unavailable = out.startsWith("komut çalıştırılamadı");
        boolean noPerm = out.toLowerCase().contains("operation not permitted")
                || out.toLowerCase().contains("permission denied")
                || out.toLowerCase().contains("socket operation");
        m.put("available", !unavailable);
        if (unavailable || noPerm) {
            m.put("status", "na");
            m.put("summary", unavailable ? "Komut bu ortamda yok" : "Yetki yok (konteyner kısıtı)");
        } else if (r.timedOut()) {
            m.put("status", "fail");
            m.put("summary", "Zaman aşımı (" + timeoutSeconds + "s)");
        } else if (r.exitCode() == 0) {
            m.put("status", "ok");
            m.put("summary", firstMeaningfulLine(out));
        } else {
            m.put("status", "warn");
            m.put("summary", firstMeaningfulLine(out));
        }
        m.put("output", out);
        return m;
    }

    /** Özet için ilk anlamlı (boş olmayan) satır, kısaltılmış. */
    static String firstMeaningfulLine(String out) {
        if (out == null) return "";
        return out.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .map(l -> l.length() > 120 ? l.substring(0, 120) + "…" : l)
                .orElse("—");
    }
}
