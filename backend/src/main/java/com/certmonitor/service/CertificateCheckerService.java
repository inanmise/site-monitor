package com.certmonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateCheckerService {

    private final ChainValidationService chainValidator;
    private final ObjectMapper objectMapper;

    @Value("${cert.monitor.check-timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${cert.monitor.warning-days:30}")
    private int warningDays;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, int port) {
        return CompletableFuture.completedFuture(check(domain, port));
    }

    public Map<String, Object> check(String domain, int port) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress(domain, port), timeoutSeconds * 1000);
                socket.setSoTimeout(timeoutSeconds * 1000);

                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(Collections.singletonList(new SNIHostName(domain)));
                socket.setSSLParameters(params);

                socket.startHandshake();

                Certificate[] peerCerts = socket.getSession().getPeerCertificates();
                if (peerCerts.length == 0) return error(domain, "No certificates in chain");

                X509Certificate leaf = (X509Certificate) peerCerts[0];
                Map<String, Object> result = parseLeafCert(leaf, domain);

                // Full chain analysis
                Map<String, Object> chainInfo = chainValidator.analyzeChain(peerCerts);
                result.put("chain_status", chainInfo.get("chain_status"));
                result.put("intermediate_expiry", chainInfo.get("intermediate_expiry"));
                result.put("intermediate_days_remaining", chainInfo.get("intermediate_days_remaining"));
                result.put("chain", chainInfo.get("chain"));

                // Fingerprint
                String fingerprint = chainValidator.calculateFingerprint(leaf);
                result.put("fingerprint", fingerprint);

                // Revocation (OCSP then CRL)
                String revocation = chainValidator.checkRevocation(peerCerts);
                result.put("revocation_status", revocation);

                // If chain is broken due to intermediate expiry, override chain_status
                if ("BROKEN".equals(chainInfo.get("chain_status")) && !"REVOKED".equals(revocation)) {
                    result.put("chain_status", "BROKEN");
                } else if ("REVOKED".equals(revocation)) {
                    result.put("chain_status", "REVOKED");
                }

                // deployment_status is determined by CertificateService (needs inventory lookup)
                result.put("deployment_status", "UNKNOWN");

                return result;
            }
        } catch (java.net.SocketTimeoutException e) {
            return error(domain, "Connection timeout");
        } catch (java.net.UnknownHostException e) {
            return error(domain, "Domain resolution failed");
        } catch (javax.net.ssl.SSLException e) {
            return error(domain, "SSL Error: " + e.getMessage());
        } catch (Exception e) {
            return error(domain, "Error: " + e.getMessage());
        }
    }

    private Map<String, Object> parseLeafCert(X509Certificate cert, String domain) {
        try {
            Instant notBefore = cert.getNotBefore().toInstant();
            Instant notAfter = cert.getNotAfter().toInstant();
            Instant now = Instant.now();

            long daysRemaining = (notAfter.toEpochMilli() - now.toEpochMilli()) / 86_400_000L;
            boolean warning = daysRemaining <= warningDays;

            String subjectCn = extractCn(cert.getSubjectX500Principal().getName());
            String issuerOrg = extractField(cert.getIssuerX500Principal().getName(), "O");
            String issuerCn = extractCn(cert.getIssuerX500Principal().getName());
            List<String> san = extractSan(cert);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("domain", domain);
            result.put("subject", subjectCn);
            result.put("issuer", issuerOrg);
            result.put("issuer_cn", issuerCn);
            result.put("not_before", ISO.format(notBefore));
            result.put("not_after", ISO.format(notAfter));
            result.put("days_remaining", (int) daysRemaining);
            result.put("warning", warning);
            result.put("status", warning ? "warning" : "valid");
            result.put("san", san);
            result.put("checked_at", ISO.format(now));
            return result;
        } catch (Exception e) {
            return error(domain, "Parse error: " + e.getMessage());
        }
    }

    private String extractCn(String dn) {
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith("CN="))
                .map(s -> s.substring(3))
                .findFirst()
                .orElse("Unknown");
    }

    private String extractField(String dn, String field) {
        String prefix = field + "=";
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith(prefix))
                .map(s -> s.substring(prefix.length()))
                .findFirst()
                .orElse("Unknown");
    }

    private List<String> extractSan(X509Certificate cert) {
        List<String> sans = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> entry : altNames) {
                    if (Integer.valueOf(2).equals(entry.get(0))) {
                        sans.add((String) entry.get(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return sans;
    }

    public String serializeSan(List<String> san) {
        try {
            return objectMapper.writeValueAsString(san);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<String> deserializeSan(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> error(String domain, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domain);
        result.put("status", "error");
        result.put("error", msg);
        result.put("warning", true);
        result.put("checked_at", ISO.format(Instant.now()));
        result.put("san", Collections.emptyList());
        result.put("chain_status", "UNKNOWN");
        result.put("revocation_status", "UNKNOWN");
        result.put("deployment_status", "UNKNOWN");
        result.put("fingerprint", null);
        result.put("intermediate_expiry", null);
        result.put("intermediate_days_remaining", null);
        result.put("chain", Collections.emptyList());
        return result;
    }
}
