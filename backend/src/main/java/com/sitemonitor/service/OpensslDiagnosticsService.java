package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derin SSL/TLS tanılaması — openssl s_client'i değişik parametrelerle
 * çalıştırıp protokol desteği, anlaşılan cipher ve sunucu sertifikasını
 * raporlar (testssl.sh benzeri zayıf protokol/sertifika tespiti).
 *
 * GÜVENLİK: openssl yalnız ProcessBuilder(List) ile çağrılır — kabuk YOKTUR,
 * argümanlar dizi olarak geçer (komut enjeksiyonu imkânsız). domain/port
 * çağrı öncesi controller'da doğrulanır (harf/rakam/nokta/tire, port 1-65535).
 *
 * openssl yoksa available=false döner — akış bozulmaz, UI Java matrisine yönlendirir.
 * Kapsam: protokol enumerasyonu + sertifika; Heartbleed/ROBOT gibi karmaşık
 * zafiyet testleri kapsam dışı.
 */
@Slf4j
@Service
public class OpensslDiagnosticsService {

    @Value("${site.monitor.diagnostics.openssl-bin:openssl}")
    private String opensslBin;

    @Value("${site.monitor.diagnostics.openssl-timeout-seconds:8}")
    private int timeoutSeconds;

    private final ThreadPoolTaskExecutor executor;

    public OpensslDiagnosticsService(@Qualifier("certCheckExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** Test edilecek protokoller — etiket + openssl s_client flag'i + risk. */
    static final String[][] PROTOCOLS = {
            {"TLSv1.0", "-tls1",   "HIGH"},
            {"TLSv1.1", "-tls1_1", "HIGH"},
            {"TLSv1.2", "-tls1_2", "OK"},
            {"TLSv1.3", "-tls1_3", "OK"},
    };

    // ── Çalıştırma sonucu (ham) ───────────────────────────────────────────────
    record CmdResult(List<String> args, String output, int exitCode, boolean timedOut) {}

    public Map<String, Object> probe(String domain, int port) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();

        String version = opensslVersion();
        if (version == null) {
            out.put("available", false);
            out.put("version", null);
            out.put("elapsed_ms", System.currentTimeMillis() - start);
            return out;
        }
        out.put("available", true);
        out.put("version", version);

        // ── 4 protokol probe'u + sertifika komutu PARALEL (toplam ≈ tek komut) ──
        List<CompletableFuture<CmdResult>> protoFutures = new ArrayList<>();
        for (String[] p : PROTOCOLS) {
            List<String> args = buildProtocolArgs(domain, port, p[1]);
            protoFutures.add(CompletableFuture.supplyAsync(() -> runOpenssl(args, "Q\n"), executor));
        }
        List<String> certArgs = buildCertArgs(domain, port);
        CompletableFuture<CmdResult> certFuture =
                CompletableFuture.supplyAsync(() -> runOpenssl(certArgs, "Q\n"), executor);

        List<Map<String, Object>> raw = new ArrayList<>();
        List<Map<String, Object>> protocols = new ArrayList<>();
        int connectFailures = 0;
        for (int i = 0; i < PROTOCOLS.length; i++) {
            String[] p = PROTOCOLS[i];
            CmdResult r = join(protoFutures.get(i));
            boolean supported = parseHandshakeSucceeded(r.output());
            if (isConnectFailure(r.output())) connectFailures++;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("proto", p[0]);
            pm.put("supported", supported);
            pm.put("risk", supported ? p[2] : "OK"); // yalnız AÇIK zayıf protokol risklidir
            protocols.add(pm);
            raw.add(rawEntry(r.args(), r.output()));
        }
        out.put("protocols", protocols);

        CmdResult certRes = join(certFuture);
        raw.add(rawEntry(certRes.args(), certRes.output()));
        out.put("negotiated", parseNegotiated(certRes.output()));
        Map<String, Object> cert = parseCertificate(certRes.output());
        out.put("certificate", cert);
        out.put("flags", deriveFlags(cert));

        // Hiçbir protokol bağlanamadıysa hedef ulaşılamaz (ör. proxy gerekli) —
        // "tüm protokoller kapalı" yanılgısı yerine açık not.
        boolean reachable = !(connectFailures == PROTOCOLS.length && isConnectFailure(certRes.output()));
        out.put("reachable", reachable);
        out.put("raw", raw);
        out.put("elapsed_ms", System.currentTimeMillis() - start);
        return out;
    }

    private CmdResult join(CompletableFuture<CmdResult> f) {
        try {
            // Her komut zaten timeoutSeconds içinde kendini sonlandırır; +5s güvenlik payı
            return f.get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new CmdResult(List.of(opensslBin), "openssl beklenirken zaman aşımı/hata: "
                    + e.getMessage(), -1, true);
        }
    }

    /** Çıktı TCP bağlantı kurulamadığını mı gösteriyor (timeout/refused/errno)? */
    static boolean isConnectFailure(String output) {
        if (output == null) return true;
        String o = output.toLowerCase();
        return o.contains("connect:errno") || o.contains("connection refused")
                || o.contains("connection timed out") || o.contains("timeout")
                || o.contains("no route to host") || o.contains("zaman aşımı")
                || o.contains("unable to connect") || o.contains("socket operation");
    }

    // ── Komut kurma (saf, test edilebilir) ────────────────────────────────────

    List<String> buildProtocolArgs(String domain, int port, String protoFlag) {
        List<String> a = new ArrayList<>(List.of(
                opensslBin, "s_client", "-connect", domain + ":" + port,
                "-servername", domain, protoFlag));
        return a;
    }

    List<String> buildCertArgs(String domain, int port) {
        return new ArrayList<>(List.of(
                opensslBin, "s_client", "-connect", domain + ":" + port,
                "-servername", domain, "-showcerts"));
    }

    // ── Çıktı ayrıştırma (saf statik, test edilebilir) ────────────────────────

    /** Handshake başarılı mı? openssl başarıda sertifika zinciri + "Cipher is"
     *  basar; hata/red durumunda "handshake failure"/"errno"/"no peer cert". */
    static boolean parseHandshakeSucceeded(String output) {
        if (output == null || output.isBlank()) return false;
        String o = output.toLowerCase();
        if (o.contains("handshake failure") || o.contains("no peer certificate")
                || o.contains("ssl_connect:") && o.contains("error")
                || o.contains("connect:errno") || o.contains("unable to load")) {
            // "Cipher is (NONE)" → anlaşma olmadı
            if (o.contains("cipher is (none)")) return false;
        }
        // Kesin başarı işareti: gerçek bir cipher anlaşması
        Matcher m = Pattern.compile("cipher\\s+is\\s+([\\w.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
        if (m.find() && !"(NONE)".equalsIgnoreCase(m.group(1))) return true;
        // Yedek: sunucu sertifikası döndüyse handshake olmuştur
        return output.contains("BEGIN CERTIFICATE") && !o.contains("cipher is (none)");
    }

    /** Anlaşılan protokol + cipher (SSL-Session bloğundan). */
    static Map<String, Object> parseNegotiated(String output) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (output == null) return n;
        Matcher proto = Pattern.compile("Protocol\\s*:\\s*(\\S+)").matcher(output);
        if (proto.find()) n.put("protocol", proto.group(1));
        Matcher cipher = Pattern.compile("Cipher\\s+is\\s+([\\w.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
        if (cipher.find()) n.put("cipher", cipher.group(1));
        else {
            Matcher c2 = Pattern.compile("Cipher\\s*:\\s*(\\S+)").matcher(output);
            if (c2.find()) n.put("cipher", c2.group(1));
        }
        return n;
    }

    /** Sunucu sertifikası özeti — subject/issuer/notAfter/anahtar/imza. */
    static Map<String, Object> parseCertificate(String output) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (output == null) return c;
        Matcher subj = Pattern.compile("subject=\\s*(.+)").matcher(output);
        if (subj.find()) c.put("subject", subj.group(1).trim());
        Matcher iss = Pattern.compile("issuer=\\s*(.+)").matcher(output);
        if (iss.find()) c.put("issuer", iss.group(1).trim());
        // openssl s_client -showcerts: "Server certificate" altında veya
        // "NotAfter"/"notAfter" tarih; ayrıca "Verify return code"
        Matcher exp = Pattern.compile("NotAfter\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(output);
        if (exp.find()) c.put("not_after", exp.group(1).trim());
        Matcher keyBits = Pattern.compile("Server public key is (\\d+) bit").matcher(output);
        if (keyBits.find()) c.put("key_bits", Integer.parseInt(keyBits.group(1)));
        Matcher sig = Pattern.compile("Signature Algorithm:\\s*(\\S+)").matcher(output);
        if (sig.find()) c.put("signature_algorithm", sig.group(1));
        Matcher verify = Pattern.compile("Verify return code:\\s*(\\d+)\\s*\\(([^)]+)\\)").matcher(output);
        if (verify.find()) {
            c.put("verify_code", Integer.parseInt(verify.group(1)));
            c.put("verify_result", verify.group(2).trim());
        }
        return c;
    }

    /** Sertifika bayrakları — UI'da risk rozeti. */
    static List<String> deriveFlags(Map<String, Object> cert) {
        List<String> flags = new ArrayList<>();
        Object keyBits = cert.get("key_bits");
        if (keyBits instanceof Integer kb && kb < 2048) flags.add("WEAK_KEY");
        Object sig = cert.get("signature_algorithm");
        if (sig != null && sig.toString().toLowerCase().contains("sha1")) flags.add("SHA1_SIG");
        Object verifyCode = cert.get("verify_code");
        Object verifyResult = cert.get("verify_result");
        if (verifyResult != null) {
            String v = verifyResult.toString().toLowerCase();
            if (v.contains("self signed") || v.contains("self-signed")) flags.add("SELF_SIGNED");
            if (v.contains("expired")) flags.add("EXPIRED");
        }
        if (verifyCode instanceof Integer vc && vc != 0 && flags.isEmpty()) flags.add("VERIFY_FAILED");
        return flags;
    }

    // ── openssl çalıştırma (ince seam) ────────────────────────────────────────

    private String opensslVersion() {
        try {
            CmdResult r = runOpenssl(List.of(opensslBin, "version"), null);
            if (r.exitCode() == 0 && r.output() != null && !r.output().isBlank()) {
                return r.output().lines().findFirst().orElse("").trim();
            }
        } catch (Exception e) {
            log.debug("openssl version okunamadı: {}", e.getMessage());
        }
        return null;
    }

    /** openssl'i timeout-sınırlı çalıştırır — ortak ProcessProbe'a delege eder. */
    private CmdResult runOpenssl(List<String> args, String stdin) {
        ProcessProbe.Result r = ProcessProbe.run(args, stdin, timeoutSeconds);
        return new CmdResult(args, r.output(), r.exitCode(), r.timedOut());
    }

    private Map<String, Object> rawEntry(List<String> args, String output) {
        Map<String, Object> e = new LinkedHashMap<>();
        // Komut gösterimi — domain/port zaten doğrulanmış, güvenli
        e.put("cmd", String.join(" ", args));
        e.put("output", output != null ? output : "");
        return e;
    }
}
