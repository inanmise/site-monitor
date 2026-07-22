package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

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

    /**
     * Outbound HTTPS için delege TrustManager: sunucu zincirini önce JVM cacerts, olmazsa admin'in
     * Genel Ayarlar'da girdiği kurumsal CA paketiyle (canlı reload) doğrular; hiçbiri güvenmezse
     * CertificateException fırlatır (el sıkışma reddedilir). Hostname doğrulaması ayrıdır (java.net.http
     * HttpClient zincir güveninden bağımsız yapar) → korunur.
     */
    public X509TrustManager compositeTrustManager() {
        return new X509TrustManager() {
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                CertificateException first = null;
                if (defaultTm != null) {
                    try { defaultTm.checkServerTrusted(chain, authType); return; }
                    catch (CertificateException e) { first = e; }
                }
                X509TrustManager extra = currentExtraTm();   // canlı-reload'lu kurumsal CA paketi
                if (extra != null) { extra.checkServerTrusted(chain, authType); return; }
                throw (first != null) ? first : new CertificateException("no trust managers");
            }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("outbound-only");
            }
            public X509Certificate[] getAcceptedIssuers() {
                return defaultTm != null ? defaultTm.getAcceptedIssuers() : new X509Certificate[0];
            }
        };
    }

    /** {@link #compositeTrustManager()} ile başlatılmış outbound TLS SSLContext; kurulamazsa null
     *  (çağıran {@code if (ssl != null) b.sslContext(ssl)} ile varsayılan güvene düşer). */
    public SSLContext outboundSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ compositeTrustManager() }, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            log.warn("Outbound SSLContext kurulamadı, varsayılan güven kullanılacak: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pin-farkındalıklı outbound SSLContext (HTTP monitör strict yolu): delege sırası JVM cacerts →
     * kurumsal CA paketi (canlı reload) → {@code pinLookup} ile host:port'un otomatik pinlenmiş CA'sı
     * ({@code CaAutoPinService}). Hepsi reddederse {@code onTrustFailure} bilgilendirilir (retry yolunun
     * redirect hedeflerini pinleyebilmesi için) ve ilk hata fırlatılır.
     *
     * <p><b>JSSE tuzağı:</b> Bu TM {@link X509ExtendedTrustManager} olduğundan JSSE hostname
     * doğrulamasını SARMALAMAZ — sorumluluk delegelere geçer. Bu yüzden delegeler her zaman 3-arg
     * (engine/socket) overload ile çağrılır ({@code checkDelegate}); TMF üretimi TM'ler extended'dır ve
     * endpoint-identification "HTTPS" set edilmiş engine ile hostname+geçerlilik kontrolünü kendileri
     * yapar. 2-arg overload'a düşmek hostname kontrolünü atlar — yalnız delege extended değilse (olağan
     * dışı provider) kullanılır.
     */
    public SSLContext pinAwareOutboundSslContext(BiFunction<String, Integer, X509TrustManager> pinLookup,
                                                 BiConsumer<String, Integer> onTrustFailure) {
        X509ExtendedTrustManager tm = new X509ExtendedTrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                String host = engine != null ? engine.getPeerHost() : null;
                int port = engine != null ? engine.getPeerPort() : -1;
                checkAll(chain, authType, engine, null, host, port);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                String host = null;
                int port = -1;
                if (socket instanceof SSLSocket ssl && ssl.getHandshakeSession() != null) {
                    host = ssl.getHandshakeSession().getPeerHost();
                    port = ssl.getHandshakeSession().getPeerPort();
                }
                checkAll(chain, authType, null, socket, host, port);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                // 2-arg giriş noktası hostname bağlamı taşımaz (pin lookup imkânsız) — cacerts+bundle.
                checkAll(chain, authType, null, null, null, -1);
            }

            private void checkAll(X509Certificate[] chain, String authType, SSLEngine engine, Socket socket,
                                  String host, int port) throws CertificateException {
                CertificateException first = null;
                if (defaultTm != null) {
                    try { checkDelegate(defaultTm, chain, authType, engine, socket); return; }
                    catch (CertificateException e) { first = e; }
                }
                X509TrustManager extra = currentExtraTm();
                if (extra != null) {
                    try { checkDelegate(extra, chain, authType, engine, socket); return; }
                    catch (CertificateException e) { if (first == null) first = e; }
                }
                if (host != null && !host.isBlank()) {
                    X509TrustManager pinned = pinLookup.apply(host, port);
                    if (pinned != null) {
                        try { checkDelegate(pinned, chain, authType, engine, socket); return; }
                        catch (CertificateException e) { if (first == null) first = e; }
                    }
                    if (onTrustFailure != null) onTrustFailure.accept(host, port);
                }
                throw first != null ? first : new CertificateException("no trust managers");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException { throw new CertificateException("outbound-only"); }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException { throw new CertificateException("outbound-only"); }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException { throw new CertificateException("outbound-only"); }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return defaultTm != null ? defaultTm.getAcceptedIssuers() : new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ tm }, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            log.warn("Pin-farkındalıklı SSLContext kurulamadı, varsayılan güven kullanılacak: {}", e.getMessage());
            return null;
        }
    }

    /** Delegeyi hostname doğrulamasını KORUYARAK çağır: extended ise 3-arg engine/socket overload'u. */
    private static void checkDelegate(X509TrustManager tm, X509Certificate[] chain, String authType,
                                      SSLEngine engine, Socket socket) throws CertificateException {
        if (tm instanceof X509ExtendedTrustManager x) {
            if (engine != null) { x.checkServerTrusted(chain, authType, engine); return; }
            if (socket != null) { x.checkServerTrusted(chain, authType, socket); return; }
        }
        tm.checkServerTrusted(chain, authType);
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

    /** PEM paketinden TM kur — {@code CaAutoPinService} pinlenmiş PEM'ler için de kullanır. */
    static X509TrustManager buildTmFromPem(String pem) throws Exception {
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
