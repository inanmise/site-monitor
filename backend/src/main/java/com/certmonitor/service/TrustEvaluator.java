package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Sunucu sertifika ZİNCİRİNİN güven durumunu değerlendirir: zincir, JVM varsayılan truststore'u
 * (cacerts) VEYA admin'in Genel Ayarlar'da girdiği kurumsal CA paketi (PEM) ile bir güven
 * köküne (trust anchor) bağlanabiliyor mu?
 *
 * <p>Sertifika ÇEKİMİ {@code CertificateCheckerService}'te trust-all soketle yapılır — yani izleme
 * aracı, zincir public CA ile doğrulanmasa bile sertifikayı okuyup süre/zincir/ayrıntıyı raporlar.
 * Güven ise burada, okunan zincir üzerinde AYRI ve YIKICI-OLMAYAN bir adımda belirlenir; çekimi
 * engellemez. Parola/secret loglanmaz, CA sertifikaları gizli değildir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustEvaluator {

    /** Genel Ayarlar key'i — admin'in yapıştırdığı kurumsal kök/ara CA paketi (PEM, çok satırlı). */
    public static final String CA_BUNDLE_KEY = "cert.monitor.trust.ca-bundle-pem";

    private final AppSettingsService appSettings;

    /** JVM varsayılan trust manager (cacerts) — bir kez kurulur. */
    private final X509TrustManager defaultTm = buildDefaultTm();

    /** Admin PEM paketinden türeyen TM; PEM içeriği değişince yeniden kurulur (canlı reload). */
    private volatile String cachedPem = null;
    private volatile X509TrustManager extraTm = null;

    public record TrustResult(boolean trusted, String reason) {}

    /**
     * Zincir, varsayılan cacerts VEYA admin CA paketiyle bir güven köküne bağlanıyor (ve şu an
     * geçerli) ise TRUSTED; aksi halde UNTRUSTED (+ kök sebep). Caller yalnız handshake'ten gelen
     * geçerli zinciri verir.
     */
    public TrustResult evaluate(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return new TrustResult(false, "empty chain");
        }
        String authType = chain[0].getPublicKey().getAlgorithm(); // RSA / EC / ...
        String reason = null;
        // 1) JVM varsayılan truststore (public CA'lar)
        if (defaultTm != null) {
            try {
                defaultTm.checkServerTrusted(chain, authType);
                return new TrustResult(true, null);
            } catch (CertificateException e) {
                reason = rootMessage(e);
            }
        }
        // 2) Admin kurumsal CA paketi (varsa)
        X509TrustManager extra = currentExtraTm();
        if (extra != null) {
            try {
                extra.checkServerTrusted(chain, authType);
                return new TrustResult(true, null);
            } catch (CertificateException e) {
                if (reason == null) reason = rootMessage(e);
            }
        }
        return new TrustResult(false, reason != null ? reason : "untrusted");
    }

    /** PEM değiştiyse extra TM'i yeniden kur; bozuk PEM'i tolere et (yalnız default truststore). */
    private X509TrustManager currentExtraTm() {
        String pem = appSettings.getString(CA_BUNDLE_KEY, "");
        if (pem == null || pem.isBlank()) {
            cachedPem = null;
            extraTm = null;
            return null;
        }
        if (pem.equals(cachedPem)) return extraTm; // değişmemiş (başarılı ya da daha önce başarısız)
        try {
            extraTm = buildTmFromPem(pem);
        } catch (Exception e) {
            log.warn("Güvenilir CA paketi (PEM) ayrıştırılamadı — yalnız varsayılan truststore kullanılacak: {}",
                    e.getMessage());
            extraTm = null;
        }
        cachedPem = pem; // başarılı/başarısız fark etmez: aynı PEM'i tekrar tekrar deneme
        return extraTm;
    }

    private static X509TrustManager buildDefaultTm() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) return x;
            }
        } catch (Exception e) {
            log.error("Varsayılan TrustManager kurulamadı: {}", e.getMessage(), e);
        }
        return null;
    }

    private static X509TrustManager buildTmFromPem(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs =
                cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        if (certs.isEmpty()) throw new CertificateException("PEM içinde sertifika yok");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate c : certs) ks.setCertificateEntry("ca-" + (i++), c);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) return x;
        }
        throw new CertificateException("X509TrustManager bulunamadı");
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return (m != null && !m.isBlank()) ? m : cur.getClass().getSimpleName();
    }
}
