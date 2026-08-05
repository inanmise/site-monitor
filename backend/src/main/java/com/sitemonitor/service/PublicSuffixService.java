package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public Suffix List ile kayıtlı domain (eTLD+1) çıkarımı + IDN→punycode.
 * PSL algoritması (publicsuffix.org): en uzun eşleşen kural; {@code *} wildcard, {@code !} exception.
 * Veri {@code /public_suffix_list.dat} (curated subset) — listede olmayan TLD'ler için varsayılan
 * {@code *} kuralı (son etiket = public suffix → kayıtlı domain = son 2 etiket) uygulanır.
 */
@Slf4j
@Service
public class PublicSuffixService {

    private final List<String[]> rules = new ArrayList<>();       // normal + wildcard (etiketler)
    private final List<String[]> exceptions = new ArrayList<>();  // '!' exception (etiketler, ! olmadan)

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream("/public_suffix_list.dat")) {
            if (in == null) { log.warn("public_suffix_list.dat bulunamadı; last-2-label fallback kullanılacak"); return; }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("//")) continue;
                    boolean exc = line.startsWith("!");
                    if (exc) line = line.substring(1);
                    String[] labels = toAsciiLabels(line);
                    if (exc) exceptions.add(labels); else rules.add(labels);
                }
            }
            log.info("PSL yüklendi: {} kural, {} exception", rules.size(), exceptions.size());
        } catch (Exception e) {
            log.warn("PSL yüklenemedi, last-2-label fallback: {}", e.getMessage());
        }
    }

    /** URL/host/subdomain → kayıtlı domain (eTLD+1), punycode. null = geçersiz ya da salt-suffix. */
    public String registrableDomain(String hostOrUrl) {
        String host = extractHost(hostOrUrl);
        if (host == null || host.isBlank()) return null;
        String[] labels;
        try { labels = toAsciiLabels(host); } catch (Exception e) { return null; }
        if (labels.length == 0) return null;

        int ps = publicSuffixLabelCount(labels);
        if (labels.length <= ps) return null;   // host'un kendisi bir public suffix → kayıtlı domain yok
        StringBuilder sb = new StringBuilder();
        for (int i = labels.length - (ps + 1); i < labels.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /** Kayıtlı domain'in TLD'si (son etiket) — WHOIS/RDAP sunucu seçimi için. */
    public String tldOf(String hostOrUrl) {
        String reg = registrableDomain(hostOrUrl);
        if (reg == null) {
            String host = extractHost(hostOrUrl);
            if (host == null) return null;
            reg = host;
        }
        int dot = reg.lastIndexOf('.');
        return dot >= 0 ? reg.substring(dot + 1) : reg;
    }

    private int publicSuffixLabelCount(String[] labels) {
        int bestExc = -1;
        for (String[] rule : exceptions) if (matches(labels, rule)) bestExc = Math.max(bestExc, rule.length);
        if (bestExc >= 1) return bestExc - 1;   // exception: en soldaki etiket suffix'ten düşer
        int best = 0;
        for (String[] rule : rules) if (matches(labels, rule)) best = Math.max(best, rule.length);
        return best > 0 ? best : 1;              // varsayılan '*' → 1 etiket
    }

    /** Kuralın host'un en sağ etiketleriyle eşleşmesi (wildcard '*' herhangi tek etiket). */
    private static boolean matches(String[] host, String[] rule) {
        if (rule.length > host.length) return false;
        for (int i = 1; i <= rule.length; i++) {
            String rl = rule[rule.length - i];
            String hl = host[host.length - i];
            if (!"*".equals(rl) && !rl.equals(hl)) return false;
        }
        return true;
    }

    /** Host etiketlerini küçük harf + punycode'a çevirir ('*' korunur). */
    static String[] toAsciiLabels(String host) {
        String[] parts = host.toLowerCase(Locale.ROOT).split("\\.");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            out[i] = ("*".equals(p) || p.isEmpty()) ? p : IDN.toASCII(p);
        }
        return out;
    }

    /** URL veya host'tan çıplak host'u ayıklar (şema/userinfo/path/port/trailing-dot atılır).
     *  Saf string işlemi — AdminController giriş normalizasyonu (diagnostics/envanter) da kullanır. */
    public static String extractHost(String hostOrUrl) {
        if (hostOrUrl == null) return null;
        String s = hostOrUrl.trim();
        if (s.isEmpty()) return null;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');   if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');      if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');   if (colon >= 0) s = s.substring(0, colon);
        return s.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
    }
}
