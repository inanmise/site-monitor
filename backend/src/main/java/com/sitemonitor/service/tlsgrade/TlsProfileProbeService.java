package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.HelloResult;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.Kind;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.Outcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir ağ uç noktasının TLS profilini çıkarır (2026-10-10): hangi protokol sürümleri kabul ediliyor, OCSP zımbalama var mı,
 * yalnız zayıf takım önerildiğinde sunucu kabul ediyor mu. Ölçüm {@link TlsHelloProbe} ile — el sıkışması tamamlanmaz,
 * JVM güvenlik ayarlarına dokunulmaz.
 *
 * <p><b>Yönlendirme kontrolle AYNI:</b> hedef önce {@link SsrfGuard}'dan geçer (engelli / çözümlenemeyen → {@code BLOCKED},
 * bağlanılmaz); doğrudan yolda SSRF'nin doğruladığı İLK adrese bağlanılır (DNS yeniden bağlama yok). Vekil kararı sertifika
 * kontrolüyle birebir: kaydın {@code use_proxy} anahtarı ∧ vekil tanımlı ∧ {@code NO_PROXY} dışı → kurumsal vekilde
 * {@code CONNECT} tüneli ({@link ProxySettings#openConnectTunnel}). SSL denetimi yapan bir vekil tüneli sonlandırırsa
 * ölçülen profil VEKİLİNKİDİR — {@code via} alanı bunu gösterir.
 *
 * <p><b>Sınırlar:</b> yoklama başına {@code site.monitor.tls-profile.probe-timeout-ms} (5 sn), uç başına toplam
 * {@code site.monitor.tls-profile.endpoint-budget-ms} (30 sn); en fazla 5 yoklama (TLS 1.2 + zımbalama, 1.3, 1.1, 1.0, zayıf
 * takım). İlk yoklamada bağlantı kurulamazsa geri kalanı denenmez. Hiçbir yoklama tahmin üretmez: cevapsız soru UNKNOWN.
 */
@Slf4j
@Service
public class TlsProfileProbeService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    static final int DETAIL_MAX = 2000;
    static final int ERROR_MAX = 255;

    private final SsrfGuard ssrfGuard;
    private final ProxySettings proxySettings;

    @Value("${site.monitor.tls-profile.probe-timeout-ms:5000}")
    int probeTimeoutMs = 5000;

    @Value("${site.monitor.tls-profile.endpoint-budget-ms:30000}")
    long endpointBudgetMs = 30_000L;

    /** Bağlantı kurucu üretimi — test sahte sunucuya bağlar. */
    @FunctionalInterface
    interface ConnectorFactory {
        TlsHelloProbe.Connector create(String host, int port, boolean viaProxy, List<InetAddress> addrs);
    }

    ConnectorFactory connectorFactory = this::defaultConnector;

    public TlsProfileProbeService(SsrfGuard ssrfGuard, ProxySettings proxySettings) {
        this.ssrfGuard = ssrfGuard;
        this.proxySettings = proxySettings;
    }

    /**
     * Uç noktayı yoklar ve (kaydedilmemiş) profil satırını döner. Hiçbir koşulda istisna fırlatmaz.
     *
     * @param useProxy kaydın {@code use_proxy} anahtarı
     * @param trigger  {@link TlsProfile#TRIGGER_SCHEDULED} | {@link TlsProfile#TRIGGER_MANUAL}
     * @param actor    elle yeniden taramada kullanıcı adı
     */
    public TlsProfile probe(String domain, int port, boolean useProxy, String trigger, String actor) {
        long start = System.currentTimeMillis();
        TlsProfile p = new TlsProfile();
        p.setDomain(domain);
        p.setPort(port);
        p.setProbeTrigger(trigger);
        p.setProbedBy(actor);
        p.setTls10(TlsProfile.UNKNOWN);
        p.setTls11(TlsProfile.UNKNOWN);
        p.setTls12(TlsProfile.UNKNOWN);
        p.setTls13(TlsProfile.UNKNOWN);
        p.setOcspStapling(TlsProfile.UNKNOWN);
        p.setWeakCipher(TlsProfile.UNKNOWN);
        try {
            List<InetAddress> addrs;
            try {
                addrs = ssrfGuard.validate(domain);
            } catch (SsrfGuard.BlockedException e) {
                p.setStatus(TlsProfile.STATUS_BLOCKED);
                p.setError(clip(e.getMessage(), ERROR_MAX));
                return finish(p, start);
            }
            boolean viaProxy = useProxy && proxySettings.enabled() && !proxySettings.bypass(domain);
            p.setVia(viaProxy ? "proxy" : "direct");
            TlsHelloProbe.Connector connector = connectorFactory.create(domain, port, viaProxy, addrs);
            long deadline = start + Math.max(1_000L, endpointBudgetMs);

            Map<String, HelloResult> results = new LinkedHashMap<>();
            HelloResult r12 = run(connector, domain, TlsHelloProbe.TLS12, Kind.VERSION, true, deadline);
            results.put("tls12", r12);
            if (r12 != null && r12.outcome() == Outcome.CONNECT_FAILED) {
                p.setStatus(TlsProfile.STATUS_FAILED);
                p.setError(clip(r12.error(), ERROR_MAX));
                p.setDetail(detailJson(results));
                return finish(p, start);
            }
            HelloResult r13 = run(connector, domain, TlsHelloProbe.TLS13, Kind.VERSION, false, deadline);
            results.put("tls13", r13);
            HelloResult r11 = run(connector, domain, TlsHelloProbe.TLS11, Kind.VERSION, false, deadline);
            results.put("tls11", r11);
            HelloResult r10 = run(connector, domain, TlsHelloProbe.TLS10, Kind.VERSION, false, deadline);
            results.put("tls10", r10);

            boolean any = accepted(r12) || accepted(r13) || accepted(r11) || accepted(r10);
            HelloResult weak = any ? run(connector, domain, TlsHelloProbe.TLS12, Kind.WEAK_CIPHERS, false, deadline) : null;
            results.put("weak", weak);

            p.setTls12(versionState(r12, any));
            p.setTls13(versionState(r13, any));
            p.setTls11(versionState(r11, any));
            p.setTls10(versionState(r10, any));
            if (accepted(r12)) {
                p.setOcspStapling(r12.stapling());
                p.setPreferredCipher(r12.cipherSuiteName());
            }
            if (weak != null) {
                switch (weak.outcome()) {
                    case ACCEPTED -> { p.setWeakCipher(TlsProfile.YES); p.setWeakCipherSuite(weak.cipherSuiteName()); }
                    case REJECTED, CLOSED -> p.setWeakCipher(TlsProfile.NO);
                    default -> p.setWeakCipher(TlsProfile.UNKNOWN);
                }
            }
            p.setDetail(detailJson(results));
            if (!any) {
                p.setStatus(TlsProfile.STATUS_FAILED);
                p.setError(clip(firstError(results), ERROR_MAX));
            } else if (!known(p.getTls10()) || !known(p.getTls11()) || !known(p.getTls12()) || !known(p.getTls13())) {
                p.setStatus(TlsProfile.STATUS_PARTIAL);
                p.setError(clip(firstError(results), ERROR_MAX));
            } else {
                p.setStatus(TlsProfile.STATUS_OK);
            }
            return finish(p, start);
        } catch (RuntimeException e) {
            log.debug("TLS profili yoklanamadı ({}): {}", domain, e.toString());
            p.setStatus(TlsProfile.STATUS_FAILED);
            p.setError(clip(TlsHelloProbe.shortMsg(e), ERROR_MAX));
            return finish(p, start);
        }
    }

    /** Kalan bütçe yetmiyorsa yoklama atlanır (null → UNKNOWN). */
    private HelloResult run(TlsHelloProbe.Connector c, String host, int version, Kind kind, boolean stapling, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left < 500) return null;
        return TlsHelloProbe.probe(c, host, version, kind, stapling, (int) Math.min(Math.max(500, probeTimeoutMs), left));
    }

    /**
     * Yoklama sonucu → üç değer. Bağlantının cevapsız kapanması, sunucu BAŞKA bir sürümü kabul ettiyse "bu sürüm kapalı"
     * demektir; hiçbir sürüm kabul edilmediyse (WAF/vekil engeli olabilir) bilinmiyor.
     */
    static String versionState(HelloResult r, boolean anyAccepted) {
        if (r == null) return TlsProfile.UNKNOWN;
        return switch (r.outcome()) {
            case ACCEPTED -> TlsProfile.YES;
            case REJECTED -> TlsProfile.NO;
            case CLOSED -> anyAccepted ? TlsProfile.NO : TlsProfile.UNKNOWN;
            default -> TlsProfile.UNKNOWN;
        };
    }

    private static boolean accepted(HelloResult r) {
        return r != null && r.outcome() == Outcome.ACCEPTED;
    }

    private static boolean known(String v) {
        return TlsProfile.YES.equals(v) || TlsProfile.NO.equals(v);
    }

    private static String firstError(Map<String, HelloResult> results) {
        for (Map.Entry<String, HelloResult> e : results.entrySet()) {
            HelloResult r = e.getValue();
            if (r != null && r.outcome() != Outcome.ACCEPTED && r.outcome() != Outcome.REJECTED && r.error() != null) {
                return e.getKey() + ": " + r.error();
            }
        }
        for (Map.Entry<String, HelloResult> e : results.entrySet()) {
            if (e.getValue() == null) return e.getKey() + ": skipped (time budget)";
        }
        return null;
    }

    /** Yoklama başına kısa kayıt: p (soru), o (sonuç), v (sürüm), c (takım), a (uyarı), ms. */
    static String detailJson(Map<String, HelloResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, HelloResult> e : results.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p", e.getKey());
            HelloResult r = e.getValue();
            if (r == null) {
                m.put("o", "SKIPPED");
            } else {
                m.put("o", r.outcome().name());
                if (r.version() != 0) m.put("v", TlsHelloProbe.versionName(r.version()));
                if (r.cipher() != 0) m.put("c", r.cipherSuiteName());
                if (r.alert() != null) m.put("a", r.alert());
                if (e.getKey().equals("tls12") && r.outcome() == Outcome.ACCEPTED) m.put("s", r.stapling());
                m.put("ms", r.elapsedMs());
            }
            rows.add(m);
        }
        try {
            return clip(JSON.writeValueAsString(rows), DETAIL_MAX);
        } catch (Exception e) {
            return null;
        }
    }

    private TlsHelloProbe.Connector defaultConnector(String host, int port, boolean viaProxy, List<InetAddress> addrs) {
        if (viaProxy) return t -> proxySettings.openConnectTunnel(host, port, t);
        InetAddress target = addrs.get(0);
        return t -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(target, port), t);
                return s;
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignore) { /* zaten kapandı */ }
                throw e;
            }
        };
    }

    private static TlsProfile finish(TlsProfile p, long start) {
        p.setProbedAt(ISO.format(Instant.now()));
        p.setDurationMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
        return p;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
