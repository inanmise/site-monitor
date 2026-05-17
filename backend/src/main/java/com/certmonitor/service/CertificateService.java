package com.certmonitor.service;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.CertificateCheck;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.repository.CertificateCheckRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateCheckRepository checkRepo;
    private final LatestCheckRepository latestRepo;
    private final CertificateCheckerService checkerService;
    private final CertificateInventoryRepository inventoryRepo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Value("${cert.monitor.warning-days:30}")
    private int warningDays;

    @CacheEvict(value = {"cert-stats", "cert-latest", "cert-warnings", "renewal-advice"}, allEntries = true)
    public void saveResult(Map<String, Object> result) {
        String domain = (String) result.get("domain");
        String now = ISO.format(Instant.now());

        @SuppressWarnings("unchecked")
        List<String> sanList = (List<String>) result.getOrDefault("san", Collections.emptyList());
        String sanJson = checkerService.serializeSan(sanList);

        @SuppressWarnings("unchecked")
        List<String> keyUsageList = (List<String>) result.getOrDefault("key_usage", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<String> extKeyUsageList = (List<String>) result.getOrDefault("ext_key_usage", Collections.emptyList());
        String keyUsageJson   = checkerService.serializeSan(keyUsageList);
        String extKeyUsageJson = checkerService.serializeSan(extKeyUsageList);

        // Determine deployment status by comparing fingerprints
        String servedFingerprint = (String) result.get("fingerprint");
        String deploymentStatus = determineDeploymentStatus(domain, servedFingerprint);
        result.put("deployment_status", deploymentStatus);

        // Serialize chain details
        String chainDetailsJson = serializeChain(result.get("chain"));

        // Save to history — wrapped so a schema error here never blocks the latest_checks upsert below
        try {
            CertificateCheck check = new CertificateCheck();
            check.setDomain(domain);
            check.setSubject((String) result.get("subject"));
            check.setIssuer((String) result.get("issuer"));
            check.setIssuerCn((String) result.get("issuer_cn"));
            check.setNotBefore((String) result.get("not_before"));
            check.setNotAfter((String) result.get("not_after"));
            check.setDaysRemaining(toInt(result.get("days_remaining")));
            check.setWarning(toBool(result.get("warning")));
            check.setStatus((String) result.get("status"));
            check.setError((String) result.get("error"));
            check.setSan(sanJson);
            check.setFingerprint(servedFingerprint);
            check.setChainStatus((String) result.get("chain_status"));
            check.setRevocationStatus((String) result.get("revocation_status"));
            check.setDeploymentStatus(deploymentStatus);
            check.setIntermediateExpiry((String) result.get("intermediate_expiry"));
            check.setIntermediateDaysRemaining(toInt(result.get("intermediate_days_remaining")));
            check.setSerialNumber((String) result.get("serial_number"));
            check.setSignatureAlgorithm((String) result.get("signature_algorithm"));
            check.setPublicKeyAlgorithm((String) result.get("public_key_algorithm"));
            check.setPublicKeySize(toInt(result.get("public_key_size")));
            check.setSubjectDn((String) result.get("subject_dn"));
            check.setIssuerDn((String) result.get("issuer_dn"));
            check.setKeyUsage(keyUsageJson);
            check.setExtKeyUsage(extKeyUsageJson);
            check.setIsCa(toBool(result.get("is_ca")));
            check.setOcspUrl((String) result.get("ocsp_url"));
            check.setCrlUrl((String) result.get("crl_url"));
            check.setRunId((String) result.get("run_id"));
            check.setCheckedAt((String) result.get("checked_at"));
            check.setCreatedAt(now);
            checkRepo.save(check);
        } catch (Exception e) {
            log.warn("Could not save check history for {} (run_id={}): {}", domain, result.get("run_id"), e.getMessage());
        }

        // Upsert latest — each save is isolated so one failure doesn't skip remaining domains
        try {
            LatestCheck latest = latestRepo.findById(domain).orElse(new LatestCheck());
            latest.setDomain(domain);
            latest.setSubject((String) result.get("subject"));
            latest.setIssuer((String) result.get("issuer"));
            latest.setIssuerCn((String) result.get("issuer_cn"));
            latest.setNotBefore((String) result.get("not_before"));
            latest.setNotAfter((String) result.get("not_after"));
            latest.setDaysRemaining(toInt(result.get("days_remaining")));
            latest.setWarning(toBool(result.get("warning")));
            latest.setStatus((String) result.get("status"));
            latest.setError((String) result.get("error"));
            latest.setSan(sanJson);
            latest.setFingerprint(servedFingerprint);
            latest.setChainStatus((String) result.get("chain_status"));
            latest.setDeploymentStatus(deploymentStatus);
            latest.setIntermediateExpiry((String) result.get("intermediate_expiry"));
            latest.setIntermediateDaysRemaining(toInt(result.get("intermediate_days_remaining")));
            latest.setRevocationStatus((String) result.get("revocation_status"));
            latest.setChainDetails(chainDetailsJson);
            latest.setSerialNumber((String) result.get("serial_number"));
            latest.setSignatureAlgorithm((String) result.get("signature_algorithm"));
            latest.setPublicKeyAlgorithm((String) result.get("public_key_algorithm"));
            latest.setPublicKeySize(toInt(result.get("public_key_size")));
            latest.setSubjectDn((String) result.get("subject_dn"));
            latest.setIssuerDn((String) result.get("issuer_dn"));
            latest.setKeyUsage(keyUsageJson);
            latest.setExtKeyUsage(extKeyUsageJson);
            latest.setIsCa(toBool(result.get("is_ca")));
            latest.setOcspUrl((String) result.get("ocsp_url"));
            latest.setCrlUrl((String) result.get("crl_url"));
            latest.setCheckedAt((String) result.get("checked_at"));
            latest.setUpdatedAt(now);
            latestRepo.save(latest);
            log.debug("Saved result for {}", domain);
        } catch (Exception e) {
            log.error("Failed to update latest_checks for {} — dashboard entry skipped: {}", domain, e.getMessage(), e);
        }
    }

    private String determineDeploymentStatus(String domain, String servedFingerprint) {
        if (servedFingerprint == null) return "UNKNOWN";
        return inventoryRepo.findByDomain(domain)
                .map(inv -> {
                    if (inv.getExpectedFingerprint() == null || inv.getExpectedFingerprint().isBlank())
                        return "OK";
                    return servedFingerprint.equalsIgnoreCase(inv.getExpectedFingerprint())
                            ? "OK" : "INCOMPLETE";
                })
                .orElse("OK");
    }

    @Cacheable("cert-latest")
    public List<CertificateDto> getAllLatest() {
        return latestRepo.findAllByOrderByDomainAsc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** Returns certs visible to the given team (null = ADMIN, sees all). */
    public List<CertificateDto> getAllLatestForTeam(Long teamId) {
        if (teamId == null) return getAllLatest();
        Set<String> domains = getTeamDomains(teamId);
        return getAllLatest().stream()
                .filter(c -> domains.contains(c.getDomain()))
                .collect(Collectors.toList());
    }

    private Set<String> getTeamDomains(Long teamId) {
        return inventoryRepo.findByTeamIdAndActiveTrueOrderByDomainAsc(teamId)
                .stream().map(com.certmonitor.model.CertificateInventory::getDomain)
                .collect(Collectors.toSet());
    }

    public Map<String, Object> getPaginated(int page, int perPage,
                                             String sortBy, String sortDir,
                                             String filterDomain, String filterIssuer,
                                             String filterStatus, Long teamId) {
        List<CertificateDto> all = getAllLatestForTeam(teamId);

        List<CertificateDto> filtered = all.stream()
                .filter(c -> filterDomain.isBlank() || c.getDomain().toLowerCase().contains(filterDomain.toLowerCase()))
                .filter(c -> filterIssuer.isBlank() || issuerStr(c).contains(filterIssuer.toLowerCase()))
                .filter(c -> {
                    if (filterStatus.isBlank()) return true;
                    boolean isError   = "error".equals(c.getStatus());
                    boolean isWarning = Boolean.TRUE.equals(c.getWarning()) && !isError;
                    boolean isCrit    = isWarning && c.getDaysRemaining() != null && c.getDaysRemaining() <= 30;
                    return switch (filterStatus) {
                        case "error"    -> isError;
                        case "critical" -> isCrit;
                        case "warning"  -> isWarning && !isCrit;
                        case "valid"    -> !isWarning && !isError;
                        default         -> filterStatus.equals(c.getStatus());
                    };
                })
                .collect(Collectors.toList());

        Comparator<CertificateDto> comparator = switch (sortBy) {
            case "priority" ->
                    Comparator.<CertificateDto, Integer>comparing(c -> "error".equals(c.getStatus()) ? 0 : 1)
                              .thenComparingInt(c -> c.getDaysRemaining() == null ? 999999 : c.getDaysRemaining());
            case "issuer" -> Comparator.comparing(c -> issuerStr(c));
            case "days_remaining" -> Comparator.comparingInt(c -> c.getDaysRemaining() == null ? 999999 : c.getDaysRemaining());
            case "checked_at" -> Comparator.comparing(c -> c.getCheckedAt() == null ? "" : c.getCheckedAt());
            default -> Comparator.comparing(c -> c.getDomain().toLowerCase());
        };
        if (!"priority".equals(sortBy) && "desc".equalsIgnoreCase(sortDir)) comparator = comparator.reversed();
        filtered.sort(comparator);

        int total = filtered.size();
        int totalPages = (int) Math.ceil((double) total / perPage);
        int from = Math.min((page - 1) * perPage, total);
        int to = Math.min(from + perPage, total);

        return Map.of(
                "data", filtered.subList(from, to),
                "pagination", Map.of(
                        "current_page", page,
                        "per_page", perPage,
                        "total", total,
                        "total_pages", Math.max(totalPages, 1)
                )
        );
    }

    @Cacheable("cert-warnings")
    public List<CertificateDto> getWarnings() {
        return latestRepo.findByWarningTrueOrStatus("error").stream()
                .sorted(Comparator.comparingInt(c -> c.getDaysRemaining() == null ? 0 : c.getDaysRemaining()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<CertificateDto> getWarningsForTeam(Long teamId) {
        if (teamId == null) return getWarnings();
        Set<String> domains = getTeamDomains(teamId);
        return getWarnings().stream()
                .filter(c -> domains.contains(c.getDomain()))
                .collect(Collectors.toList());
    }

    /**
     * Adds the domain to certificate_inventory if not already present,
     * assigning it to the given team. Called when a user checks a domain via the UI.
     */
    @Transactional
    public void ensureInInventory(String domain, int port, Long teamId) {
        if (inventoryRepo.existsByDomain(domain)) return;
        String now = ISO.format(Instant.now());
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain(domain);
        inv.setPort(port);
        inv.setTeamId(teamId);
        inv.setActive(true);
        inv.setCreatedAt(now);
        inv.setUpdatedAt(now);
        inventoryRepo.save(inv);
        log.info("Auto-added {} to inventory (port {}, teamId={})", domain, port, teamId);
    }

    public List<CertificateDto> getHistory(String domain, int limit) {
        return checkRepo.findTopByDomainOrderByCheckedAtDesc(domain, limit).stream()
                .map(this::toDtoFromCheck)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getStatsForTeam(Long teamId) {
        if (teamId == null) return getStats();
        List<CertificateDto> all = getAllLatestForTeam(teamId);
        List<CertificateDto> warnings = getWarningsForTeam(teamId);
        return computeStats(all, warnings);
    }

    @Cacheable("cert-stats")
    public Map<String, Object> getStats() {
        return computeStats(getAllLatest(), getWarnings());
    }

    private Map<String, Object> computeStats(List<CertificateDto> all, List<CertificateDto> warnings) {
        long errors = warnings.stream().filter(c -> "error".equals(c.getStatus())).count();
        long warningOnly = warnings.size() - errors;
        long valid = all.size() - warnings.size();
        long expiring30 = warnings.stream()
                .filter(c -> c.getDaysRemaining() != null && c.getDaysRemaining() > 0 && c.getDaysRemaining() <= 30)
                .count();
        long expired = warnings.stream()
                .filter(c -> c.getDaysRemaining() != null && c.getDaysRemaining() < 0)
                .count();
        long revoked = all.stream()
                .filter(c -> "REVOKED".equals(c.getRevocationStatus()))
                .count();
        long mismatch = all.stream()
                .filter(c -> "INCOMPLETE".equals(c.getDeploymentStatus()))
                .count();
        long chainBroken = all.stream()
                .filter(c -> "BROKEN".equals(c.getChainStatus()))
                .count();

        return Map.of(
                "total_certificates", all.size(),
                "valid_count", valid,
                "warning_count", warningOnly,
                "error_count", errors,
                "expiring_in_30_days", expiring30,
                "expired", expired,
                "revoked", revoked,
                "deployment_mismatch", mismatch,
                "chain_broken", chainBroken
        );
    }

    /**
     * Returns check runs from the last {@code hours} hours, newest first.
     * When teamId is non-null only runs containing that team's domains are included.
     */
    public List<Map<String, Object>> getActivityLog(int hours, Long teamId) {
        String cutoff = ISO.format(Instant.now().minus(hours, ChronoUnit.HOURS));
        List<CertificateCheck> checks = checkRepo.findByCheckedAtAfter(cutoff);

        if (teamId != null) {
            Set<String> teamDomains = getTeamDomains(teamId);
            checks = checks.stream().filter(c -> teamDomains.contains(c.getDomain())).collect(Collectors.toList());
        }

        // Group by runId preserving DESC order (newest run first)
        LinkedHashMap<String, List<CertificateCheck>> grouped = new LinkedHashMap<>();
        for (CertificateCheck c : checks) {
            String key = c.getRunId() != null ? c.getRunId() : "unknown";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        List<Map<String, Object>> runs = new ArrayList<>();
        for (Map.Entry<String, List<CertificateCheck>> entry : grouped.entrySet()) {
            List<CertificateCheck> batch = entry.getValue();

            long errCount  = batch.stream().filter(c -> "error".equals(c.getStatus())).count();
            long warnCount = batch.stream().filter(c -> Boolean.TRUE.equals(c.getWarning())
                                                        && !"error".equals(c.getStatus())).count();
            long okCount   = batch.size() - errCount - warnCount;

            // Run start time = earliest checkedAt in the batch
            String runTime = batch.stream()
                    .map(CertificateCheck::getCheckedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse("");

            List<Map<String, Object>> entries = batch.stream()
                    .sorted(Comparator.comparing(c -> c.getDomain().toLowerCase()))
                    .map(c -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("domain",         c.getDomain());
                        e.put("status",         c.getStatus());
                        e.put("warning",        c.getWarning());
                        e.put("days_remaining", c.getDaysRemaining());
                        e.put("not_after",      c.getNotAfter());
                        e.put("error",          c.getError());
                        e.put("checked_at",     c.getCheckedAt());
                        return e;
                    }).toList();

            Map<String, Object> run = new LinkedHashMap<>();
            run.put("run_id",  entry.getKey());
            run.put("run_time", runTime);
            run.put("total",   batch.size());
            run.put("ok",      okCount);
            run.put("warning", warnCount);
            run.put("error",   errCount);
            run.put("entries", entries);
            runs.add(run);
        }
        return runs;
    }

    public List<Map<String, Object>> getRenewalAdviceForTeam(Long teamId) {
        if (teamId == null) return getRenewalAdvice();
        return computeRenewalAdvice(getAllLatestForTeam(teamId));
    }

    @Cacheable("renewal-advice")
    public List<Map<String, Object>> getRenewalAdvice() {
        return computeRenewalAdvice(getAllLatest());
    }

    private List<Map<String, Object>> computeRenewalAdvice(List<CertificateDto> all) {
        List<Map<String, Object>> advice = new ArrayList<>();

        for (CertificateDto cert : all) {
            Integer days = cert.getDaysRemaining();
            String domain = cert.getDomain();

            if ("REVOKED".equals(cert.getRevocationStatus())) {
                advice.add(buildAdvice(domain, "critical",
                        "Sertifika İPTAL EDİLDİ! Trafiği derhal kesin.",
                        "Sertifikayı yenileyin ve CDN/load-balancer yapılandırmasını güncelleyin.",
                        days, cert.getNotAfter()));
            } else if ("INCOMPLETE".equals(cert.getDeploymentStatus())) {
                advice.add(buildAdvice(domain, "critical",
                        "DEPLOYMENT EKSİK: Yenileme yapıldı ancak uç nokta eski sertifikayı sunuyor.",
                        "Yeni sertifikayı uç noktalara deploy edin ve konfigürasyonu yeniden yükleyin.",
                        days, cert.getNotAfter()));
            } else if ("BROKEN".equals(cert.getChainStatus())) {
                advice.add(buildAdvice(domain, "critical",
                        "ZİNCİR SORUNLU: Ara/kök CA sertifikası süresi dolmuş veya geçersiz.",
                        "Ara sertifika zincirini güncelleyin. Sunucu yapılandırmasını kontrol edin.",
                        days, cert.getNotAfter()));
            } else if ("error".equals(cert.getStatus())) {
                advice.add(buildAdvice(domain, "critical",
                        "Sertifikaya ulaşılamıyor.",
                        "Bağlantıyı ve domain yapılandırmasını kontrol edin.",
                        null, cert.getNotAfter()));
            } else if (days != null && days < 0) {
                advice.add(buildAdvice(domain, "critical",
                        "Sertifika süresi dolmuş! " + Math.abs(days) + " gün önce bitti.",
                        "Sertifikayı derhal yenileyin.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 7) {
                advice.add(buildAdvice(domain, "critical",
                        "Sertifika " + days + " gün içinde bitiyor! Acil yenileme gerekli.",
                        "Sertifikayı bugün yenileyin.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 30) {
                advice.add(buildAdvice(domain, "warning",
                        "Sertifika " + days + " gün içinde bitiyor.",
                        "Sertifika yenileme sürecini başlatın.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 60) {
                advice.add(buildAdvice(domain, "info",
                        "Sertifika " + days + " gün içinde bitiyor.",
                        "Sertifika yenileme takviminizi güncelleyin.",
                        days, cert.getNotAfter()));
            }
        }

        Map<String, Integer> order = Map.of("critical", 0, "warning", 1, "info", 2);
        advice.sort(Comparator.comparingInt(a -> order.getOrDefault((String) a.get("priority"), 9)));
        return advice;
    }

    private Map<String, Object> buildAdvice(String domain, String priority,
                                             String message, String action,
                                             Integer days, String notAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("priority", priority);
        m.put("message", message);
        m.put("action", action);
        m.put("days_remaining", days);
        m.put("not_after", notAfter);
        return m;
    }

    private CertificateDto toDto(LatestCheck c) {
        return CertificateDto.from(c,
                checkerService.deserializeSan(c.getSan()),
                checkerService.deserializeSan(c.getKeyUsage()),
                checkerService.deserializeSan(c.getExtKeyUsage()));
    }

    private CertificateDto toDtoFromCheck(CertificateCheck c) {
        LatestCheck l = new LatestCheck();
        l.setDomain(c.getDomain());
        l.setSubject(c.getSubject());
        l.setIssuer(c.getIssuer());
        l.setIssuerCn(c.getIssuerCn());
        l.setNotBefore(c.getNotBefore());
        l.setNotAfter(c.getNotAfter());
        l.setDaysRemaining(c.getDaysRemaining());
        l.setWarning(c.getWarning());
        l.setStatus(c.getStatus());
        l.setError(c.getError());
        l.setSan(c.getSan());
        l.setFingerprint(c.getFingerprint());
        l.setChainStatus(c.getChainStatus());
        l.setRevocationStatus(c.getRevocationStatus());
        l.setDeploymentStatus(c.getDeploymentStatus());
        l.setIntermediateExpiry(c.getIntermediateExpiry());
        l.setIntermediateDaysRemaining(c.getIntermediateDaysRemaining());
        l.setSerialNumber(c.getSerialNumber());
        l.setSignatureAlgorithm(c.getSignatureAlgorithm());
        l.setPublicKeyAlgorithm(c.getPublicKeyAlgorithm());
        l.setPublicKeySize(c.getPublicKeySize());
        l.setSubjectDn(c.getSubjectDn());
        l.setIssuerDn(c.getIssuerDn());
        l.setKeyUsage(c.getKeyUsage());
        l.setExtKeyUsage(c.getExtKeyUsage());
        l.setIsCa(c.getIsCa());
        l.setOcspUrl(c.getOcspUrl());
        l.setCrlUrl(c.getCrlUrl());
        l.setCheckedAt(c.getCheckedAt());
        return CertificateDto.from(l,
                checkerService.deserializeSan(c.getSan()),
                checkerService.deserializeSan(c.getKeyUsage()),
                checkerService.deserializeSan(c.getExtKeyUsage()));
    }

    private String issuerStr(CertificateDto c) {
        String s = c.getIssuerCn() != null ? c.getIssuerCn() : (c.getIssuer() != null ? c.getIssuer() : "");
        return s.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private String serializeChain(Object chain) {
        try {
            if (chain == null) return "[]";
            return objectMapper.writeValueAsString(chain);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private Boolean toBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
}
