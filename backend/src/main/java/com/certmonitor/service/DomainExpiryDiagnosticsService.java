package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alan Adı Tanılama aracı — alan adı (registrar) süre bitişi sorgusunu adım adım koşar ve her adımın
 * ne olduğunu (PSL → IANA bootstrap → registry RDAP → rdap.org → WHOIS) yakalayan yapısal bir trace döner.
 * Lookup mantığını KOPYALAMAZ: {@link RdapDomainClient#diagnoseSteps} ve {@link WhoisDomainClient#diagnose}
 * mevcut proxy-aware client'ları/TrustEvaluator SSLContext'ini/parse'ı yeniden kullanır (trust-all YOK).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainExpiryDiagnosticsService {

    private final RdapDomainClient rdap;
    private final WhoisDomainClient whois;
    private final PublicSuffixService psl;

    /** {@code {input, registrable, tld, steps:[...], source, expiry_date, days_remaining, registrar, elapsed_ms}} */
    public Map<String, Object> diagnose(String domainInput) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        out.put("input", domainInput);
        out.put("steps", steps);

        // 1) PSL / TLD çözümü
        String reg = psl.registrableDomain(domainInput);
        String tld = reg != null ? psl.tldOf(reg) : null;
        Map<String, Object> pslStep = new LinkedHashMap<>();
        pslStep.put("step", "PSL");
        if (reg == null || reg.isBlank()) {
            pslStep.put("status", "fail");
            pslStep.put("error_class", "PSL");
            pslStep.put("error", "geçersiz/çözümlenemeyen domain");
            steps.add(pslStep);
            out.put("registrable", null);
            out.put("tld", null);
            out.put("source", "FAILED");
            out.put("expiry_date", null);
            out.put("days_remaining", null);
            out.put("registrar", null);
            out.put("elapsed_ms", System.currentTimeMillis() - t0);
            log.info("Domain-expiry diagnose: input={} → PSL_FAIL ({}ms)", domainInput, System.currentTimeMillis() - t0);
            return out;
        }
        pslStep.put("status", "ok");
        pslStep.put("detail", "registrable: " + reg + " · TLD: ." + tld);
        steps.add(pslStep);
        out.put("registrable", reg);
        out.put("tld", tld);

        // 2-4) RDAP zinciri: bootstrap → registry → rdap.org (global timeout)
        Map<String, Object> rdapResult = rdap.diagnoseSteps(reg, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rdapSteps = (List<Map<String, Object>>) rdapResult.get("steps");
        if (rdapSteps != null) steps.addAll(rdapSteps);

        String source = String.valueOf(rdapResult.getOrDefault("source", "FAILED"));
        String expiry = (String) rdapResult.get("expiry_date");
        String registrar = (String) rdapResult.get("registrar");
        String whoisProvider = null;   // .tr WHOIS'i hangi kaynak yanıtladı (isimtescil/trabis/trabis43)

        // 5) WHOIS fallback — yalnız RDAP süre bitişi vermediyse
        if (expiry == null) {
            Map<String, Object> whoisStep = whois.diagnose(reg);
            steps.add(whoisStep);
            if ("ok".equals(whoisStep.get("status")) && whoisStep.get("expiry_date") != null) {
                expiry = String.valueOf(whoisStep.get("expiry_date"));
                if (whoisStep.get("registrar") != null) registrar = String.valueOf(whoisStep.get("registrar"));
                if (whoisStep.get("provider") != null) whoisProvider = String.valueOf(whoisStep.get("provider"));
                source = "WHOIS";
            }
        } else {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("step", "WHOIS");
            skip.put("status", "skip");
            skip.put("detail", "RDAP başarılı — atlandı");
            steps.add(skip);
        }

        if (expiry == null) source = "FAILED";
        Integer days = DomainCheckerService.daysUntil(expiry);
        out.put("source", source);
        out.put("expiry_date", expiry);
        out.put("days_remaining", days);
        out.put("registrar", registrar);
        out.put("whois_provider", whoisProvider);   // kart "kaynak" satırında gösterir (null → RDAP/gösterilmez)
        long elapsed = System.currentTimeMillis() - t0;
        out.put("elapsed_ms", elapsed);

        log.info("Domain-expiry diagnose: domain={} → source={} expiry={} days={} ({} adım, {}ms)",
                reg, source, expiry, days, steps.size(), elapsed);
        return out;
    }
}
