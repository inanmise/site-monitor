package com.sitemonitor.service;

import java.util.Locale;

/**
 * Alan adı tanılama trace'i için hata sınıflandırıcı (saf/yan-etkisiz — birim testli).
 * Bir istisnayı ya da hata mesajını okunabilir bir hata koduna eşler; UI bu koda göre
 * Türkçe ipucu gösterir (ör. PKIX_TRUST → "Proxy SSL-inspection kök CA'sı truststore'da yok").
 */
public final class DiagnosticErrorClassifier {

    private DiagnosticErrorClassifier() {}

    /** Throwable → hata kodu (kök nedene inip mesaj + sınıf adına bakar). */
    public static String classify(Throwable t) {
        if (t == null) return "UNKNOWN";
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String cls = cur.getClass().getName();
        String msg = cur.getMessage();
        String combined = (cls + " " + (msg != null ? msg : ""));
        return classify(combined);
    }

    /** Hata mesajı/metni → hata kodu. Eşleşme yoksa UNKNOWN. */
    public static String classify(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        String s = raw.toLowerCase(Locale.ROOT);

        // TLS güven zinciri (kurumsal SSL-inspection kök CA'sı truststore'da yok)
        if (s.contains("pkix")
                || s.contains("suncertpathbuilderexception")
                || s.contains("unable to find valid certification path")
                || s.contains("certificate_unknown")
                || s.contains("sslhandshakeexception")
                || s.contains("certpathvalidatorexception")) {
            return "PKIX_TRUST";
        }
        // Proxy tünelleme sorunları (HTTP CONNECT)
        if (s.contains("unable to tunnel")
                || s.contains("proxy")
                || s.contains(" 407")
                || s.contains("proxyexception")) {
            return "PROXY";
        }
        // Ham TCP bağlantı zaman aşımı (WHOIS port-43 / socket) — egress kapalı olabilir
        if (s.contains("connect timed out") || s.contains("connecttimeoutexception")) {
            return "CONNECT_TIMEOUT";
        }
        // HTTP istek/yanıt zaman aşımı (java.net.http)
        if (s.contains("httptimeoutexception")
                || s.contains("request timed out")
                || s.contains("timed out")
                || s.contains("timeout")) {
            return "TIMEOUT";
        }
        // DNS çözümlenemedi
        if (s.contains("unknownhostexception") || s.contains("no such host") || s.contains("name or service not known")) {
            return "DNS";
        }
        // Bağlantı reddedildi
        if (s.contains("connection refused") || s.contains("connect refused") || s.contains("connectexception")) {
            return "REFUSED";
        }
        return "UNKNOWN";
    }

    /** HTTP durum kodu → hata kodu (2xx başarı sayılmaz; çağıran 2xx için bu metodu çağırmaz). */
    public static String httpStatus(int status) {
        return "HTTP_" + status;
    }
}
