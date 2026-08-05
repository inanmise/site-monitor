package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kurumsal SSL-inspection proxy'sinin sunduğu CA zincirini yakalayıp, "Güvenilir CA paketi (PEM)" alanına
 * yapıştırılmaya HAZIR PEM olarak veren tanılama servisi. Zincir çekimi {@link CertificateCheckerService#captureProxyChain}
 * (yalnız-okuma, openssl -showcerts eşdeğeri) ile yapılır; burada yalnız biçimlendirme + özet vardır.
 * Amaç: "kurumsal CA'yı nereden alacağım?" adımını pod'un içinden, UI'dan çözmek.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyCaExportService {

    private final CertificateCheckerService certChecker;

    /** {@code {host, port, ok, chain:[{subject,issuer,not_after,self_signed,is_ca,leaf}], ca_pem, ca_count, error?, error_class?}} */
    public Map<String, Object> capture(String host, int port) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("port", port);
        try {
            X509Certificate[] chain = certChecker.captureProxyChain(host, port);
            List<Map<String, Object>> certs = new ArrayList<>();
            StringBuilder caPem = new StringBuilder();
            int caCount = 0;
            for (int i = 0; i < chain.length; i++) {
                X509Certificate c = chain[i];
                boolean leaf = (i == 0);
                boolean selfSigned = c.getSubjectX500Principal().equals(c.getIssuerX500Principal());
                boolean isCa = c.getBasicConstraints() >= 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", c.getSubjectX500Principal().getName());
                m.put("issuer", c.getIssuerX500Principal().getName());
                m.put("not_after", String.valueOf(c.getNotAfter()));
                m.put("self_signed", selfSigned);
                m.put("is_ca", isCa);
                m.put("leaf", leaf);
                certs.add(m);
                // Yaprağı (leaf) atla; kalan tüm CA'ları (ara + kök) pakete koy → trust anchor olur.
                if (!leaf) {
                    caPem.append(toPem(c));
                    caCount++;
                }
            }
            out.put("ok", true);
            out.put("chain", certs);
            out.put("ca_pem", caPem.toString());
            out.put("ca_count", caCount);
            log.info("Proxy CA zinciri yakalandı: {}:{} → {} sertifika ({} CA)", host, port, chain.length, caCount);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error_class", DiagnosticErrorClassifier.classify(e));
            out.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.warn("Proxy CA zinciri yakalanamadı: {}:{} → {}", host, port, e.getMessage());
        }
        return out;
    }

    /** X.509 → PEM bloğu (64-karakter satır sarma, standart BEGIN/END). */
    static String toPem(X509Certificate cert) {
        try {
            String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            return "";
        }
    }
}
