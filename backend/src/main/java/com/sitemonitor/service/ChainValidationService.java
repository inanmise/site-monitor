package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.*;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Slf4j
@Service
public class ChainValidationService {

    /**
     * OCSP/CRL hedefleri SUNUCUNUN KONTROLİNDEKİ sertifikadan gelir (AIA / CRL-DP uzantıları),
     * yani saldırgan-kontrollü bir URL'dir. Bağlanmadan önce politika kapısı şart.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private SsrfGuard ssrfGuard;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String OID_AIA = "1.3.6.1.5.5.7.1.1";
    private static final String OID_CRL_DP = "2.5.29.31";

    @Value("${site.monitor.cache.crl-max-size:200}")
    private int crlCacheMaxSize;

    @Value("${site.monitor.cache.crl-ttl-hours:1}")
    private int crlCacheTtlHours;

    @Value("${site.monitor.proxy.host:}")
    private String proxyHost;

    @Value("${site.monitor.proxy.port:0}")
    private int proxyPort;

    @Value("${site.monitor.proxy.user:}")
    private String proxyUser;

    @Value("${site.monitor.proxy.pass:}")
    private String proxyPass;

    /**
     * NO_PROXY listesi. Bu sınıfta EKSİKTİ: proxy tanımlıyken CRL/OCSP adreslerinin HEPSİ vekile
     * gidiyordu — İÇ ağdaki dağıtım noktaları dâhil. Kardeş giden-istek sınıflarının üçünde
     * (CertificateCheckerService, RdapDomainClient, TrWebWhoisClient) bu atlama zaten vardı.
     */
    @Value("${site.monitor.proxy.no-proxy:}")
    private String noProxyList;

    private Cache<String, X509CRL> crlCache;

    @PostConstruct
    public void init() {
        crlCache = Caffeine.newBuilder()
                .maximumSize(crlCacheMaxSize)
                .expireAfterWrite(crlCacheTtlHours, TimeUnit.HOURS)
                .build();
        resolveProxyFromEnv();
    }

    /**
     * site.monitor.proxy.host ayarlanmamışsa (HTTP_PROXY_HOST env), standart
     * HTTPS_PROXY / HTTP_PROXY URL env değişkenlerini ayrıştırmaya geri döner (Linux geleneği).
     */
    private void resolveProxyFromEnv() {
        if (proxyHost != null && !proxyHost.isBlank()) return;
        String url = System.getenv("HTTPS_PROXY");
        if (url == null || url.isBlank()) url = System.getenv("HTTP_PROXY");
        if (url == null || url.isBlank()) {
            url = System.getenv("https_proxy");
            if (url == null || url.isBlank()) url = System.getenv("http_proxy");
        }
        if (url == null || url.isBlank()) return;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank()) return;
            proxyHost = host;
            proxyPort = port > 0 ? port : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            String info = uri.getUserInfo();
            if (info != null && info.contains(":")) {
                String[] parts = info.split(":", 2);
                if (proxyUser == null || proxyUser.isBlank()) proxyUser = parts[0];
                if (proxyPass == null || proxyPass.isBlank()) proxyPass = parts[1];
            }
            log.info("Resolved proxy from env URL: {}:{}", proxyHost, proxyPort);
        } catch (Exception e) {
            log.warn("Failed to parse proxy URL '{}': {}", url, e.getMessage());
        }
    }

    public String calculateFingerprint(X509Certificate cert) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Fingerprint calculation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Tam SSL peer sertifika zincirinden zincir bilgisini oluşturur.
     * Şu anahtarlarla bir map döner: chain (liste), intermediate_expiry, intermediate_days_remaining, chain_status.
     */
    public Map<String, Object> analyzeChain(java.security.cert.Certificate[] peerCerts) {
        List<Map<String, Object>> chainList = new ArrayList<>();
        String chainStatus = "VALID";
        String earliestIntermediateExpiry = null;
        int earliestIntermediateDays = Integer.MAX_VALUE;

        Instant now = Instant.now();

        for (int i = 0; i < peerCerts.length; i++) {
            if (!(peerCerts[i] instanceof X509Certificate x509)) continue;

            Instant notAfter = x509.getNotAfter().toInstant();
            long daysRemaining = (notAfter.toEpochMilli() - now.toEpochMilli()) / 86_400_000L;
            boolean isRoot = isRootCa(x509);
            boolean isLeaf = (i == 0);

            Map<String, Object> certInfo = new LinkedHashMap<>();
            certInfo.put("position", i);
            certInfo.put("subject", x509.getSubjectX500Principal().getName());
            certInfo.put("issuer", x509.getIssuerX500Principal().getName());
            certInfo.put("not_after", ISO.format(notAfter));
            certInfo.put("days_remaining", (int) Math.max(daysRemaining, 0));
            certInfo.put("is_root", isRoot);
            certInfo.put("is_leaf", isLeaf);
            certInfo.put("not_before",          ISO.format(x509.getNotBefore().toInstant()));
            certInfo.put("serial_number",       x509.getSerialNumber().toString(16).toUpperCase());
            certInfo.put("signature_algorithm", x509.getSigAlgName());

            if (daysRemaining < 0) {
                certInfo.put("expired", true);
                if (!isLeaf) chainStatus = "BROKEN";
            } else {
                certInfo.put("expired", false);
            }

            // En erken leaf-olmayan (intermediate) sertifikanın son kullanımını izle
            if (!isLeaf) {
                String expiryIso = ISO.format(notAfter);
                int days = (int) Math.max(daysRemaining, 0);
                if (days < earliestIntermediateDays) {
                    earliestIntermediateDays = days;
                    earliestIntermediateExpiry = expiryIso;
                }
            }

            chainList.add(certInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chain", chainList);
        result.put("chain_status", chainStatus);
        result.put("intermediate_expiry", earliestIntermediateExpiry);
        result.put("intermediate_days_remaining",
                earliestIntermediateDays == Integer.MAX_VALUE ? null : earliestIntermediateDays);
        return result;
    }

    /**
     * Leaf sertifikanın OCSP iptal (revocation) durumunu, zincirdeki issuer'ıyla kontrol eder.
     * VALID, REVOKED veya UNKNOWN döner.
     */
    public String checkRevocation(java.security.cert.Certificate[] peerCerts) {
        if (peerCerts.length < 2) return "UNKNOWN";
        if (!(peerCerts[0] instanceof X509Certificate leaf)) return "UNKNOWN";
        if (!(peerCerts[1] instanceof X509Certificate issuer)) return "UNKNOWN";

        // Önce OCSP dene, olmazsa CRL'e geri düş
        String ocspResult = checkOcsp(leaf, issuer);
        if (!"UNKNOWN".equals(ocspResult)) return ocspResult;

        return checkCrl(leaf);
    }

    private String checkOcsp(X509Certificate cert, X509Certificate issuer) {
        try {
            String ocspUrl = getOcspUrl(cert);
            if (ocspUrl == null) return "UNKNOWN";

            DigestCalculatorProvider digCalcProv = new JcaDigestCalculatorProviderBuilder().build();
            CertificateID certId = new CertificateID(
                    digCalcProv.get(CertificateID.HASH_SHA1),
                    new JcaX509CertificateHolder(issuer),
                    cert.getSerialNumber());

            OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
            reqBuilder.addRequest(certId);
            OCSPReq request = reqBuilder.build();

            HttpURLConnection conn = openWithProxy(ocspUrl);
            try {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/ocsp-request");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getOutputStream().write(request.getEncoded());

                try (InputStream is = conn.getInputStream()) {
                    OCSPResp response = new OCSPResp(is);
                    if (response.getStatus() != OCSPRespBuilder.SUCCESSFUL) return "UNKNOWN";
                    BasicOCSPResp basicResp = (BasicOCSPResp) response.getResponseObject();
                    SingleResp[] singleResps = basicResp.getResponses();
                    if (singleResps.length == 0) return "UNKNOWN";
                    CertificateStatus status = singleResps[0].getCertStatus();
                    if (status == CertificateStatus.GOOD) return "VALID";
                    if (status instanceof RevokedStatus) return "REVOKED";
                }
                return "UNKNOWN";
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.debug("OCSP check failed: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * CRL üzerinden iptal durumu — VALID yalnız GERÇEKTEN DANIŞILMIŞ bir listeye dayanır.
     *
     * <p><b>Neden bu ayrım.</b> Eski hâli {@code return urls.isEmpty() ? "UNKNOWN" : "VALID"}
     * diyordu: dağıtım noktası TANIMLI ama hiçbiri indirilemediğinde döngü hiçbir şey kontrol
     * etmeden bitiyor ve sonuç "iptal edilmemiş" oluyordu. {@link #downloadCrl} başarısızlıkta
     * fırlatmaz, {@code null} döner — yani hata bu metoda hiç ulaşmıyordu.
     *
     * <p>Üretimde teorik değildi: iç CA ile imzalı sertifikalarda dağıtım noktalarının biri
     * {@code ldap://} (bu istemcinin desteklemediği şema), diğeri erişilemeyen bir HTTP adresi;
     * ikisi de düşüyor, sertifika yine de "iptal edilmemiş" raporlanıyordu. Bir sertifika izleme
     * ürününde bu en pahalı hata türü: İPTAL EDİLMİŞ bir sertifika temiz görünür.
     *
     * <p>Doğru anlam: hiçbir listeye danışılamadıysa cevap "hayır" değil, BİLİNMİYOR.
     * {@code urls} boş olduğu durum da bu ifadeye dahildir (danışılan liste yok → UNKNOWN).
     */
    // Paket-gorunur: kapi testi (ChainValidationServiceTest) dogrudan cagirir.
    String checkCrl(X509Certificate cert) {
        try {
            boolean consulted = false;
            for (String url : getCrlUrls(cert)) {
                // Herhangi bir cache kilidi tutmadan kontrol et; bloklamayı önlemek için ayrı indir
                X509CRL crl = crlCache.getIfPresent(url);
                if (crl == null) {
                    crl = downloadCrl(url);
                    if (crl != null) crlCache.put(url, crl);
                }
                if (crl == null) continue;   // indirilemedi → bu listeye DANIŞILMADI
                consulted = true;
                if (crl.isRevoked(cert)) return "REVOKED";
            }
            return consulted ? "VALID" : "UNKNOWN";
        } catch (Exception e) {
            log.debug("CRL check failed: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    public String extractOcspUrl(X509Certificate cert) { return getOcspUrl(cert); }

    public String extractCrlUrl(X509Certificate cert) {
        List<String> urls = getCrlUrls(cert);
        return urls.isEmpty() ? null : urls.get(0);
    }

    private String getOcspUrl(X509Certificate cert) {
        try {
            byte[] rawExt = cert.getExtensionValue(OID_AIA);
            if (rawExt == null) return null;
            byte[] extBytes = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(rawExt)).getOctets();
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(
                    ASN1Primitive.fromByteArray(extBytes));
            for (AccessDescription ad : aia.getAccessDescriptions()) {
                if (X509ObjectIdentifiers.id_ad_ocsp.equals(ad.getAccessMethod())) {
                    GeneralName gn = ad.getAccessLocation();
                    if (gn.getTagNo() == GeneralName.uniformResourceIdentifier) {
                        return gn.getName().toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("AIA parse failed: {}", e.getMessage());
        }
        return null;
    }

    private List<String> getCrlUrls(X509Certificate cert) {
        List<String> urls = new ArrayList<>();
        try {
            byte[] rawExt = cert.getExtensionValue(OID_CRL_DP);
            if (rawExt == null) return urls;
            byte[] extBytes = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(rawExt)).getOctets();
            CRLDistPoint cdp = CRLDistPoint.getInstance(ASN1Primitive.fromByteArray(extBytes));
            for (DistributionPoint dp : cdp.getDistributionPoints()) {
                DistributionPointName dpn = dp.getDistributionPoint();
                if (dpn == null || dpn.getType() != DistributionPointName.FULL_NAME) continue;
                for (GeneralName gn : GeneralNames.getInstance(dpn.getName()).getNames()) {
                    if (gn.getTagNo() == GeneralName.uniformResourceIdentifier) {
                        urls.add(gn.getName().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CRL DP parse failed: {}", e.getMessage());
        }
        return urls;
    }

    private X509CRL downloadCrl(String url) {
        // BEKLENEN durum, hata değil: AD ortamlarında CRL dağıtım noktası çoğu kez ldap:// olur ve
        // bu istemci yalnız http/https konuşur (guardTarget şema allow-list'i). Her kontrolde, her
        // iç sertifika için WARN basmak kalıcı gürültü üretiyordu — asıl indirme hataları bu
        // gürültünün içinde kayboluyor. Şema reddi DEBUG, gerçek başarısızlıklar WARN kalır.
        String scheme = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            log.debug("CRL atlandı (desteklenmeyen şema): {}", url);
            return null;
        }
        try {
            HttpURLConnection conn = openWithProxy(url);
            try {
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (InputStream is = conn.getInputStream()) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    return (X509CRL) cf.generateCRL(is);
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.warn("CRL download failed {}: {}", url, e.getMessage());
            return null;
        }
    }

    private boolean isRootCa(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    /**
     * Ayarlıysa yapılandırılmış proxy üzerinden, değilse doğrudan HTTP bağlantısı açar.
     * CertificateCheckerService proxy desenini yansıtır; böylece OCSP/CRL trafiği de
     * kurumsal çıkış (egress) proxy'sinden geçer (OpenShift / kısıtlı ağlar).
     */
    /**
     * OCSP/CRL indirmesi için bağlantı açar — <b>politika kapısından geçerek</b>.
     *
     * <p><b>Neden gerekti.</b> Hedef URL, izlenen sunucunun sunduğu sertifikanın AIA / CRL-DP
     * uzantısından okunuyor ({@link #getOcspUrl} / {@link #getCrlUrls}) — tamamen karşı tarafın
     * yazdığı bir dize. Kapı yokken AIA'sı {@code http://169.254.169.254/...} ya da bir iç servis
     * olan bir sertifika, uygulamaya iç ağa POST (OCSP) ve GET (CRL) attırabiliyordu; sonuç
     * {@code revocation_status} ve zamanlama üzerinden kör bir orakl olarak okunabiliyordu.
     *
     * <p><b>Yönlendirme KAPALI.</b> {@code HttpURLConnection} varsayılanı takip eder; OCSP/CRL'nin
     * yönlendirmeye ihtiyacı yoktur ve takip, kapıdan geçen ilk host'tan sonra başka bir hedefe
     * sıçramak demektir (SafeRedirect'in çözdüğü sınıfın ta kendisi).
     *
     * <p>Çözülemeyen host bağlantıyı DURDURMAZ: vekil arkasında (split-DNS) pod çözemese de vekil
     * çözebilir — {@code HstsDiagnosticsService.guardHost} ile aynı hoşgörü.
     */
    // Paket-gorunur: kapi testi (OcspCrlSsrfGuardTest) dogrudan cagirir.
    HttpURLConnection openWithProxy(String url) throws IOException {
        guardTarget(url);
        HttpURLConnection conn;
        String targetHost;
        try { targetHost = java.net.URI.create(url).getHost(); } catch (Exception e) { targetHost = null; }
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0 && !shouldBypass(targetHost)) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort));
            conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            if (proxyUser != null && !proxyUser.isBlank()) {
                String auth = Base64.getEncoder().encodeToString(
                        (proxyUser + ":" + proxyPass).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Proxy-Authorization", "Basic " + auth);
            }
        } else {
            conn = (HttpURLConnection) new URL(url).openConnection();
        }
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    /**
     * NO_PROXY eşleşmesi — vekil ATLANMALI mı? (RdapDomainClient/TrWebWhoisClient ile aynı kural:
     * tam ad ya da nokta-sınırlı sonek; baştaki nokta yok sayılır.)
     *
     * <p>İç CA'ların CRL dağıtım noktaları iç adreslerdir; onları DMZ vekiline göndermek indirmeyi
     * düşürür ve iptal durumu doğrulanamaz hâle gelir. Not: bu atlama ancak NO_PROXY tanımlıysa
     * çalışır — liste boşken davranış eskisiyle aynıdır (ortam tarafı ayrıca ayarlanmalı).
     */
    private boolean shouldBypass(String host) {
        if (noProxyList == null || noProxyList.isBlank() || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : noProxyList.split(",")) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.startsWith(".")) e = e.substring(1);
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    /** Şema allow-list + {@link SsrfGuard}; çözülemeyen host geçer (vekil senaryosu). */
    void guardTarget(String url) throws IOException {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (Exception e) {
            throw new IOException("geçersiz OCSP/CRL URL'si");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            // ldap:// CRL-DP'leri sahada görülür; HttpURLConnection zaten açamaz, burada açıkça reddedilir.
            throw new IOException("desteklenmeyen OCSP/CRL şeması: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IOException("OCSP/CRL URL'sinde host yok");
        try {
            ssrfGuard.validate(host);
        } catch (SsrfGuard.UnresolvableHostException ue) {
            log.debug("OCSP/CRL: {} yerelde çözülemedi, bağlantı yine denenecek", host);
        }
    }
}
