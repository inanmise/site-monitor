package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WHOIS (TCP/43) istemcisi — RDAP desteklemeyen TLD'ler (özellikle .tr) için fallback.
 * ENV-GATED: {@code cert.monitor.domain.whois-enabled} (varsayılan false). TLD→sunucu haritası
 * config'ten ({@code cert.monitor.domain.whois-servers} = "tr=whois.nic.tr,uk=whois.nic.uk") + gömülü
 * varsayılanlar. Parser TLD bazlı takılabilir ({@link TrWhoisParser} / {@link DefaultWhoisParser}).
 *
 * NOT: Ham port-43 soketi kurumsal HTTP-CONNECT proxy'sinden (yalnız 443/80) GEÇMEZ; bu yol yalnız
 * port-43 egress'i olan ortamda çalışır. Erişilemez/parse-yok → {@code error} dolu (→ orkestratörde UNKNOWN).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhoisDomainClient {

    private final AppSettingsService appSettings;
    private final PublicSuffixService psl;

    private final Map<String, WhoisParser> parsers = Map.of("tr", new TrWhoisParser());
    private final WhoisParser defaultParser = new DefaultWhoisParser();

    /** Gömülü TLD→WHOIS sunucu varsayılanları (config ile ezilebilir). */
    private static final Map<String, String> BUILTIN = Map.ofEntries(
            Map.entry("tr", "whois.trabis.gov.tr"),   // TRABIS/BTK — eski whois.nic.tr artık kullanılmıyor
            Map.entry("com", "whois.verisign-grs.com"),
            Map.entry("net", "whois.verisign-grs.com"),
            Map.entry("org", "whois.pir.org"),
            Map.entry("uk", "whois.nic.uk"),
            Map.entry("de", "whois.denic.de"),
            Map.entry("nl", "whois.domain-registry.nl"),
            Map.entry("eu", "whois.eu"),
            Map.entry("info", "whois.afilias.net"),
            Map.entry("io", "whois.nic.io"));

    public boolean enabled() {
        return appSettings.getBoolean("cert.monitor.domain.whois-enabled", false);
    }

    /** Kayıtlı domain için WHOIS sorgusu (env-gated). RDAP ile aynı Map şeklini döner (+ source=WHOIS). */
    public Map<String, Object> lookup(String registrableDomain) {
        if (!enabled()) return err("whois disabled");
        if (registrableDomain == null || registrableDomain.isBlank()) return err("invalid domain");
        String tld = psl.tldOf(registrableDomain);
        String server = serverFor(tld);
        if (server == null) return err("no whois server for ." + tld);
        String raw;
        try {
            raw = query(server, registrableDomain);
        } catch (Exception e) {
            log.debug("WHOIS {} @{} başarısız: {}", registrableDomain, server, e.getMessage());
            return err("whois: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        if (raw == null || raw.isBlank()) return err("empty whois response");
        Map<String, Object> info = parsers.getOrDefault(tld, defaultParser).parse(raw);
        info.put("source", "WHOIS");
        if (info.get("expiry_date") == null) info.put("error", "whois: no expiry parsed");
        // Ham özet (kısaltılmış) — denetim/UI için orkestratör kullanabilir.
        info.put("raw", raw.length() > 1500 ? raw.substring(0, 1500) : raw);
        return info;
    }

    String serverFor(String tld) {
        if (tld == null) return null;
        for (String e : appSettings.getCsv("cert.monitor.domain.whois-servers", "")) {
            int eq = e.indexOf('=');
            if (eq <= 0) continue;
            if (e.substring(0, eq).trim().equalsIgnoreCase(tld)) {
                String s = e.substring(eq + 1).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return BUILTIN.get(tld.toLowerCase(Locale.ROOT));
    }

    /** Ham port-43 WHOIS sorgusu. */
    String query(String server, String domain) throws Exception {
        int timeout = appSettings.getInt("cert.monitor.domain.whois-timeout-ms", 8000);
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(server, 43), timeout);
            sock.setSoTimeout(timeout);
            OutputStream os = sock.getOutputStream();
            os.write((domain + "\r\n").getBytes(StandardCharsets.US_ASCII));
            os.flush();
            StringBuilder sb = new StringBuilder();
            try (InputStream is = sock.getInputStream()) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = is.read(buf)) != -1 && total < 200_000) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    total += n;
                }
            }
            return sb.toString();
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "NONE");
        out.put("error", msg);
        return out;
    }
}
