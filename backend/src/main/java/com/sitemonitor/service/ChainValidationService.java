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

    private String checkCrl(X509Certificate cert) {
        try {
            List<String> urls = getCrlUrls(cert);
            for (String url : urls) {
                // Herhangi bir cache kilidi tutmadan kontrol et; bloklamayı önlemek için ayrı indir
                X509CRL crl = crlCache.getIfPresent(url);
                if (crl == null) {
                    crl = downloadCrl(url);
                    if (crl != null) crlCache.put(url, crl);
                }
                if (crl != null && crl.isRevoked(cert)) return "REVOKED";
            }
            return urls.isEmpty() ? "UNKNOWN" : "VALID";
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
    private HttpURLConnection openWithProxy(String url) throws IOException {
        HttpURLConnection conn;
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
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
        return conn;
    }
}
