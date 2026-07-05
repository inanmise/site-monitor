package com.certmonitor.service;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.AlertThreshold;
import com.certmonitor.model.CertificateCheck;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateCheckRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.TeamRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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
    private final TeamRepository teamRepo;
    private final AlertThresholdRepository alertThresholdRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Value("${cert.monitor.warning-days:30}")
    private int warningDays;

    /**
     * Cache eviction'lar buradan çıkarıldı — sweep boyunca saveResult N kez
     * çağrılıp 4 cache'i N×4 kez boşaltıyordu. Caller (SchedulerService veya
     * manuel /check endpoint'i) iş bitince {@link #evictAllCaches()} çağırır.
     */
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
            check.setTrustStatus((String) result.get("trust_status"));
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
            check.setErrorClass((String) result.get("error_class"));
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
            latest.setTrustStatus((String) result.get("trust_status"));
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
            latest.setVia((String) result.get("via"));
            latest.setTlsModeUsed((String) result.get("tls_mode_used"));
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

    /**
     * Sweep ya da manuel check sonrası 4 listede cache'i tek seferde temizler.
     * Önceden her saveResult bunu yapardı — 1000 domain'lik sweep'te 4000 evict
     * tetikleniyordu. Şimdi caller batch sonunda tek çağırır.
     */
    @Caching(evict = {
        @CacheEvict(value = "cert-stats",     allEntries = true),
        @CacheEvict(value = "cert-latest",    allEntries = true),
        @CacheEvict(value = "cert-warnings",  allEntries = true),
        @CacheEvict(value = "renewal-advice", allEntries = true)
    })
    public void evictAllCaches() {
        // metod gövdesi boş — annotation'lar Spring AOP'a iş yaptırır
    }

    @Cacheable(value = "cert-latest", sync = true)
    public List<CertificateDto> getAllLatest() {
        // Active inventory'yi TEK SEFER yükle — hem domain set'i hem tier map'i bundan üret
        List<CertificateInventory> activeInventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        Set<String> activeDomains = new HashSet<>(activeInventory.size());
        Map<String, Integer> tierMap = new HashMap<>(activeInventory.size());
        // domain → sorumlu (SY) takım — dashboard kart etiketi + takım filtresi için.
        // Yalnız aktif envanterin atıf yaptığı takımları yükle (tüm teams tablosu yerine).
        Set<Long> neededTeamIds = activeInventory.stream()
                .map(CertificateInventory::getTeamId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> teamNames = teamRepo.findAllById(neededTeamIds).stream()
                .filter(tm -> tm.getId() != null && tm.getName() != null)
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
        Map<String, Long> teamIdMap = new HashMap<>(activeInventory.size());
        Map<String, String> teamNameMap = new HashMap<>(activeInventory.size());
        for (CertificateInventory inv : activeInventory) {
            String d = inv.getDomain();
            if (d == null) continue;
            activeDomains.add(d);
            if (inv.getTier() != null) tierMap.put(d, inv.getTier());
            if (inv.getTeamId() != null) {
                teamIdMap.put(d, inv.getTeamId());
                String tn = teamNames.get(inv.getTeamId());
                if (tn != null) teamNameMap.put(d, tn);
            }
        }
        var thrOpt   = alertThresholdRepo.findFirstByActiveTrue();
        int critDays = thrOpt.map(AlertThreshold::getCriticalDays).orElse(7);
        int highDays  = thrOpt.map(AlertThreshold::getHighDays).orElse(15);
        // findByDomainIn → tüm latest_checks yerine sadece aktif domain'lerin satırlarını çek
        return latestRepo.findByDomainIn(activeDomains).stream()
                .sorted(Comparator.comparing(LatestCheck::getDomain,
                        Comparator.nullsLast(String::compareTo)))
                .map(c -> {
                    CertificateDto dto = toDto(c);
                    dto.setTier(tierMap.get(c.getDomain()));
                    dto.setTeamId(teamIdMap.get(c.getDomain()));
                    dto.setTeamName(teamNameMap.get(c.getDomain()));
                    dto.setAlertLevel(computeAlertLevel(dto, critDays, highDays));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private String computeAlertLevel(CertificateDto dto, int critDays, int highDays) {
        if ("error".equals(dto.getStatus())) return "error";
        Integer days = dto.getDaysRemaining();
        if (days == null) return "valid";
        if (days < 0)         return "expired";
        if (days <= critDays) return "critical";
        if (days <= highDays) return "high";
        if (Boolean.TRUE.equals(dto.getWarning())) return "warning";
        return "valid";
    }

    /**
     * Takım id→ad haritası (izleme listesi zenginleştirmesi). Takımlar nadiren değişir →
     * 60s cache; her liste isteğinde (keyword/ping/port/dns) teamRepo.findAll() round-trip'ini önler.
     */
    @Cacheable("teamNames")
    public Map<Long, String> teamNamesById() {
        return teamRepo.findAll().stream()
                .filter(tm -> tm.getId() != null && tm.getName() != null)
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
    }

    /**
     * domain → sorumlu (SY) takım adı (aktif envanter). İzleme ekranları (uptime/port/dns)
     * yanıtlarını takımla zenginleştirmek için — getAllLatest'teki aynı domain→team deseni.
     * Cache'li sıcak yolu (getAllLatest) bozmamak için ayrı, bağımsız bir okuma.
     */
    public Map<String, String> domainTeamNameMap() {
        Map<Long, String> teamNames = teamRepo.findAll().stream()
                .filter(tm -> tm.getId() != null && tm.getName() != null)
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
        Map<String, String> out = new HashMap<>();
        for (CertificateInventory inv : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
            if (inv.getDomain() == null || inv.getTeamId() == null) continue;
            String tn = teamNames.get(inv.getTeamId());
            if (tn != null) out.put(inv.getDomain(), tn);
        }
        return out;
    }

    private Map<String, Integer> buildTierMap() {
        // Yalnız aktif envanteri çek + tier set olanları seç. findAll() ile
        // tüm tabloyu yüklemek yerine WHERE active=true filtresi DB tarafına iner.
        return inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .filter(i -> i.getTier() != null && i.getDomain() != null)
                .collect(Collectors.toMap(
                        CertificateInventory::getDomain,
                        CertificateInventory::getTier,
                        (a, b) -> a));
    }

    /** Certs visible to the given team scope. null = unrestricted (global); empty = none. */
    public List<CertificateDto> getAllLatestForTeams(java.util.Collection<Long> teamIds) {
        if (teamIds == null) return getAllLatest();
        if (teamIds.isEmpty()) return List.of();
        Set<String> domains = getTeamDomains(teamIds);
        return getAllLatest().stream()
                .filter(c -> domains.contains(c.getDomain()))
                .collect(Collectors.toList());
    }

    /** Distinct domains owned (SY slot) by any of the given teams. */
    private Set<String> getTeamDomains(java.util.Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return Set.of();
        return inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(teamIds)
                .stream().map(com.certmonitor.model.CertificateInventory::getDomain)
                .collect(Collectors.toSet());
    }

    public Map<String, Object> getPaginated(int page, int perPage,
                                             String sortBy, String sortDir,
                                             String filterDomain, String filterIssuer,
                                             String filterStatus, java.util.Collection<Long> teamIds) {
        List<CertificateDto> all = getAllLatestForTeams(teamIds);

        var thrOpt   = alertThresholdRepo.findFirstByActiveTrue();
        int critDays = thrOpt.map(AlertThreshold::getCriticalDays).orElse(7);
        int highDays  = thrOpt.map(AlertThreshold::getHighDays).orElse(15);

        List<CertificateDto> filtered = all.stream()
                .filter(c -> filterDomain.isBlank() || c.getDomain().toLowerCase().contains(filterDomain.toLowerCase()))
                .filter(c -> filterIssuer.isBlank() || issuerStr(c).contains(filterIssuer.toLowerCase()))
                .filter(c -> {
                    if (filterStatus.isBlank()) return true;
                    boolean isError   = "error".equals(c.getStatus());
                    boolean isWarning = Boolean.TRUE.equals(c.getWarning()) && !isError;
                    boolean isCrit    = isWarning && c.getDaysRemaining() != null && c.getDaysRemaining() <= critDays;
                    boolean isHigh    = isWarning && !isCrit && c.getDaysRemaining() != null && c.getDaysRemaining() <= highDays;
                    return switch (filterStatus) {
                        case "error"     -> isError;
                        case "critical"  -> isCrit;
                        case "high"      -> isHigh;
                        case "warning"   -> isWarning && !isCrit && !isHigh;
                        case "valid"     -> !isWarning && !isError;
                        case "expiring7" -> c.getDaysRemaining() != null && c.getDaysRemaining() >= 0 && c.getDaysRemaining() <= 7;
                        default          -> filterStatus.equals(c.getStatus());
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

    @Cacheable(value = "cert-warnings", sync = true)
    public List<CertificateDto> getWarnings() {
        Set<String> activeDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc()
                .stream().map(CertificateInventory::getDomain).collect(Collectors.toSet());
        return latestRepo.findByWarningTrueOrStatus("error").stream()
                .filter(c -> activeDomains.contains(c.getDomain()))
                .sorted(Comparator.comparingInt(c -> c.getDaysRemaining() == null ? 0 : c.getDaysRemaining()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<CertificateDto> getWarningsForTeams(java.util.Collection<Long> teamIds) {
        if (teamIds == null) return getWarnings();
        if (teamIds.isEmpty()) return List.of();
        Set<String> domains = getTeamDomains(teamIds);
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

    public Map<String, Object> getStatsForTeams(java.util.Collection<Long> teamIds) {
        if (teamIds == null) return getStats();
        List<CertificateDto> all = getAllLatestForTeams(teamIds);
        List<CertificateDto> warnings = getWarningsForTeams(teamIds);
        return computeStats(all, warnings);
    }

    private Set<String> getUgTeamDomains(Long ugTeamId) {
        return inventoryRepo.findByUgTeamIdAndActiveTrueOrderByDomainAsc(ugTeamId)
                .stream().map(CertificateInventory::getDomain)
                .collect(Collectors.toSet());
    }

    public Map<String, Object> getTeamBreakdownStats(Long teamId, String teamName) {
        List<CertificateDto> syAll  = getAllLatestForTeams(List.of(teamId));
        List<CertificateDto> syWarn = getWarningsForTeams(List.of(teamId));

        Set<String> ugDomains = getUgTeamDomains(teamId);
        List<CertificateDto> ugAll  = getAllLatest().stream()
                .filter(c -> ugDomains.contains(c.getDomain())).toList();
        List<CertificateDto> ugWarn = getWarnings().stream()
                .filter(c -> ugDomains.contains(c.getDomain())).toList();

        Map<String, Integer> tierMap = buildTierMap();
        List<CertificateDto> syT1All  = syAll.stream().filter(c -> Integer.valueOf(1).equals(tierMap.get(c.getDomain()))).toList();
        List<CertificateDto> syT1Warn = syWarn.stream().filter(c -> Integer.valueOf(1).equals(tierMap.get(c.getDomain()))).toList();
        List<CertificateDto> syT2All  = syAll.stream().filter(c -> Integer.valueOf(2).equals(tierMap.get(c.getDomain()))).toList();
        List<CertificateDto> syT2Warn = syWarn.stream().filter(c -> Integer.valueOf(2).equals(tierMap.get(c.getDomain()))).toList();

        int[] th = thresholdDays(); // eşik tek okuma → 4 alt-grup aynı değeri kullanır
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode",        "personal");
        res.put("team_id",     teamId);
        res.put("team_name",   teamName != null ? teamName : "");
        res.put("sy_stats",    computeStats(syAll, syWarn, th[0], th[1]));
        res.put("ug_stats",    computeStats(ugAll, ugWarn, th[0], th[1]));
        res.put("sy_t1_stats", computeStats(syT1All, syT1Warn, th[0], th[1]));
        res.put("sy_t2_stats", computeStats(syT2All, syT2Warn, th[0], th[1]));
        return res;
    }

    public Map<String, Object> getAllTeamsBreakdownStats() { return teamsBreakdown(null); }

    /** Per-team breakdown limited to the given teams (Faz 3b scoped müdür/PO). null = all. */
    public Map<String, Object> getTeamsBreakdownStats(java.util.Collection<Long> onlyTeamIds) {
        return teamsBreakdown(onlyTeamIds);
    }

    private Map<String, Object> teamsBreakdown(java.util.Collection<Long> onlyTeamIds) {
        List<CertificateInventory> allInv     = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        List<CertificateDto>       allLatest  = getAllLatest();
        List<CertificateDto>       allWarnings = getWarnings();

        Map<Long, Set<String>> syMap = new HashMap<>();
        Map<Long, Set<String>> ugMap = new HashMap<>();
        for (CertificateInventory inv : allInv) {
            if (inv.getTeamId()   != null) syMap.computeIfAbsent(inv.getTeamId(),   k -> new HashSet<>()).add(inv.getDomain());
            if (inv.getUgTeamId() != null) ugMap.computeIfAbsent(inv.getUgTeamId(), k -> new HashSet<>()).add(inv.getDomain());
        }

        // Domain → DTO indeksleri (her domain için tek satır: latest_checks per-domain'dir).
        // Böylece per-team filtrelemede TÜM sertifikaları taramak yerine takımın kendi
        // (küçük) domain kümesinden O(1) lookup yapılır → O(takım × sertifika) yerine ~O(sertifika).
        Map<String, CertificateDto> latestByDomain = new HashMap<>(allLatest.size() * 2);
        for (CertificateDto c : allLatest) latestByDomain.putIfAbsent(c.getDomain(), c);
        Map<String, CertificateDto> warnByDomain = new HashMap<>(allWarnings.size() * 2);
        for (CertificateDto c : allWarnings) warnByDomain.putIfAbsent(c.getDomain(), c);

        Map<String, Integer> tierMap = buildTierMap();
        int[] th = thresholdDays(); // eşik tek okuma → döngüde her team için DB okunmaz
        List<Map<String, Object>> result = new ArrayList<>();
        // findAll() yerine sadece aktif team'leri çek — DB tarafında WHERE active=true
        for (Team team : teamRepo.findByActiveTrueOrderByNameAsc()) {
            Long tid = team.getId();
            if (onlyTeamIds != null && !onlyTeamIds.contains(tid)) continue;  // Faz 3b scope
            Set<String> sy = syMap.getOrDefault(tid, Set.of());
            Set<String> ug = ugMap.getOrDefault(tid, Set.of());

            // Takımın kendi domain'leri üzerinden lookup (domain'e göre sıralı → çıktı sırası
            // eski "allLatest.filter" sürümüyle birebir aynı; allLatest zaten domain-sıralı).
            List<CertificateDto> syAll  = lookupByDomains(sy, latestByDomain);
            List<CertificateDto> syWarn = lookupByDomains(sy, warnByDomain);
            List<CertificateDto> ugAll  = lookupByDomains(ug, latestByDomain);
            List<CertificateDto> ugWarn = lookupByDomains(ug, warnByDomain);

            List<CertificateDto> syT1All  = filterTier(syAll,  tierMap, 1);
            List<CertificateDto> syT1Warn = filterTier(syWarn, tierMap, 1);
            List<CertificateDto> syT2All  = filterTier(syAll,  tierMap, 2);
            List<CertificateDto> syT2Warn = filterTier(syWarn, tierMap, 2);

            Map<String, Object> entry = new HashMap<>();
            entry.put("team_id",   tid);
            entry.put("team_name", team.getName());
            entry.put("sy_stats",  computeStats(syAll, syWarn, th[0], th[1]));
            entry.put("ug_stats",  computeStats(ugAll, ugWarn, th[0], th[1]));
            entry.put("sy_t1_stats", computeStats(syT1All, syT1Warn, th[0], th[1]));
            entry.put("sy_t2_stats", computeStats(syT2All, syT2Warn, th[0], th[1]));
            result.add(entry);
        }
        return Map.of("mode", "all_teams", "teams", result);
    }

    /** Verilen domain kümesine ait DTO'ları indeksten toplar; domain'e göre sıralı döner
     *  (kaynak allLatest/allWarnings domain-sıralı olduğu için eski filter sırasıyla aynı). */
    private static List<CertificateDto> lookupByDomains(Set<String> domains, Map<String, CertificateDto> byDomain) {
        List<CertificateDto> out = new ArrayList<>(domains.size());
        for (String d : domains) {
            CertificateDto c = byDomain.get(d);
            if (c != null) out.add(c);
        }
        out.sort(Comparator.comparing(CertificateDto::getDomain, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private static List<CertificateDto> filterTier(List<CertificateDto> certs, Map<String, Integer> tierMap, int tier) {
        List<CertificateDto> out = new ArrayList<>();
        for (CertificateDto c : certs) {
            if (Integer.valueOf(tier).equals(tierMap.get(c.getDomain()))) out.add(c);
        }
        return out;
    }

    @Cacheable(value = "cert-stats", sync = true)
    public Map<String, Object> getStats() {
        return computeStats(getAllLatest(), getWarnings());
    }

    /** Aktif eşik (kritik/yüksek gün) — TEK okuma; 4-arg computeStats'e geçirilir. */
    private int[] thresholdDays() {
        var t = alertThresholdRepo.findFirstByActiveTrue();
        return new int[]{ t.map(AlertThreshold::getCriticalDays).orElse(7),
                          t.map(AlertThreshold::getHighDays).orElse(15) };
    }

    private Map<String, Object> computeStats(List<CertificateDto> all, List<CertificateDto> warnings) {
        int[] th = thresholdDays();
        return computeStats(all, warnings, th[0], th[1]);
    }

    /**
     * İstatistikleri TEK GEÇİŞ ile hesaplar — eski 12+ ayrı stream yerine 2 döngü
     * (warnings'te bir, all'da bir). Çıktı eski sürümle birebir aynıdır; eşik günleri
     * dışarıdan verilir ki toplu (per-team) çağrılarda her seferinde DB okunmasın.
     */
    private Map<String, Object> computeStats(List<CertificateDto> all, List<CertificateDto> warnings,
                                             int critDays, int highDays) {
        long errors = 0, criticalCount = 0, highCount = 0, expiring30 = 0, expiring7 = 0, expired = 0;
        List<String> warningDomains  = new ArrayList<>();
        List<String> highDomains     = new ArrayList<>();
        List<String> criticalDomains = new ArrayList<>();
        List<String> expiredDomains  = new ArrayList<>();
        List<String> errorDomains    = new ArrayList<>();
        Set<String> warnDomainSet    = new HashSet<>();

        for (CertificateDto c : warnings) {
            warnDomainSet.add(c.getDomain());
            boolean isError = "error".equals(c.getStatus());
            Integer d = c.getDaysRemaining();
            if (isError) { errors++; errorDomains.add(c.getDomain()); }
            if (!isError && d != null) {
                if (d <= critDays) criticalCount++;
                else if (d <= highDays) highCount++;
                if (d > highDays) warningDomains.add(c.getDomain());
                if (d > critDays && d <= highDays) highDomains.add(c.getDomain());
                if (d >= 0 && d <= critDays) criticalDomains.add(c.getDomain());
            }
            if (d != null) {
                if (d > 0 && d <= 30) expiring30++;
                if (d >= 0 && d <= 7)  expiring7++;
                if (d < 0) { expired++; expiredDomains.add(c.getDomain()); }
            }
        }
        long warningOnly = warnings.size() - errors - criticalCount - highCount;

        long valid = 0, revoked = 0, mismatch = 0, chainBroken = 0;
        List<String> validDomains = new ArrayList<>();
        for (CertificateDto c : all) {
            if (!warnDomainSet.contains(c.getDomain())) { valid++; validDomains.add(c.getDomain()); }
            if ("REVOKED".equals(c.getRevocationStatus()))    revoked++;
            if ("INCOMPLETE".equals(c.getDeploymentStatus())) mismatch++;
            if ("BROKEN".equals(c.getChainStatus()))          chainBroken++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total_certificates",  all.size());
        result.put("valid_count",          valid);
        result.put("critical_count",       criticalCount);
        result.put("high_count",           highCount);
        result.put("warning_count",        warningOnly);
        result.put("error_count",          errors);
        result.put("expiring_in_7_days",   expiring7);
        result.put("expiring_in_30_days",  expiring30);
        result.put("expired",              expired);
        result.put("revoked",              revoked);
        result.put("deployment_mismatch",  mismatch);
        result.put("chain_broken",         chainBroken);
        result.put("valid_domains",        validDomains);
        result.put("warning_domains",      warningDomains);
        result.put("high_domains",         highDomains);
        result.put("critical_domains",     criticalDomains);
        result.put("expired_domains",      expiredDomains);
        result.put("error_domains",        errorDomains);
        return result;
    }

    /**
     * Returns check runs from the last {@code hours} hours, newest first.
     * When teamId is non-null only runs containing that team's domains are included.
     */
    /** Aktivite log için en fazla bu kadar kontrol satırı belleğe alınır (OOM koruması). */
    private static final int ACTIVITY_LOG_MAX_ROWS = 20_000;

    public List<Map<String, Object>> getActivityLog(int hours, java.util.Collection<Long> teamIds) {
        String cutoff = ISO.format(Instant.now().minus(hours, ChronoUnit.HOURS));
        List<CertificateCheck> checks = checkRepo.findByCheckedAtAfterLimited(cutoff, ACTIVITY_LOG_MAX_ROWS);

        if (teamIds != null) {
            Set<String> teamDomains = getTeamDomains(teamIds);
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

    public List<Map<String, Object>> getRenewalAdviceForTeams(java.util.Collection<Long> teamIds) {
        if (teamIds == null) return getRenewalAdvice();
        return computeRenewalAdvice(getAllLatestForTeams(teamIds));
    }

    @Cacheable(value = "renewal-advice", sync = true)
    public List<Map<String, Object>> getRenewalAdvice() {
        return computeRenewalAdvice(getAllLatest());
    }

    private List<Map<String, Object>> computeRenewalAdvice(List<CertificateDto> all) {
        List<Map<String, Object>> advice = new ArrayList<>();

        for (CertificateDto cert : all) {
            Integer days = cert.getDaysRemaining();
            String domain = cert.getDomain();

            if ("REVOKED".equals(cert.getRevocationStatus())) {
                advice.add(buildAdvice(domain, "REVOKED", "critical",
                        "Sertifika İPTAL EDİLDİ! Trafiği derhal kesin.",
                        "Sertifikayı yenileyin ve CDN/load-balancer yapılandırmasını güncelleyin.",
                        days, cert.getNotAfter()));
            } else if ("INCOMPLETE".equals(cert.getDeploymentStatus())) {
                advice.add(buildAdvice(domain, "DEPLOYMENT_INCOMPLETE", "critical",
                        "DEPLOYMENT EKSİK: Yenileme yapıldı ancak uç nokta eski sertifikayı sunuyor.",
                        "Yeni sertifikayı uç noktalara deploy edin ve konfigürasyonu yeniden yükleyin.",
                        days, cert.getNotAfter()));
            } else if ("BROKEN".equals(cert.getChainStatus())) {
                advice.add(buildAdvice(domain, "CHAIN_BROKEN", "critical",
                        "ZİNCİR SORUNLU: Ara/kök CA sertifikası süresi dolmuş veya geçersiz.",
                        "Ara sertifika zincirini güncelleyin. Sunucu yapılandırmasını kontrol edin.",
                        days, cert.getNotAfter()));
            } else if ("error".equals(cert.getStatus())) {
                advice.add(buildAdvice(domain, "UNREACHABLE", "critical",
                        "Sertifikaya ulaşılamıyor.",
                        "Bağlantıyı ve domain yapılandırmasını kontrol edin.",
                        null, cert.getNotAfter()));
            } else if (days != null && days < 0) {
                advice.add(buildAdvice(domain, "EXPIRED", "critical",
                        "Sertifika süresi dolmuş! " + Math.abs(days) + " gün önce bitti.",
                        "Sertifikayı derhal yenileyin.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 7) {
                advice.add(buildAdvice(domain, "EXPIRING_CRITICAL", "critical",
                        "Sertifika " + days + " gün içinde bitiyor! Acil yenileme gerekli.",
                        "Sertifikayı bugün yenileyin.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 30) {
                advice.add(buildAdvice(domain, "EXPIRING_WARNING", "warning",
                        "Sertifika " + days + " gün içinde bitiyor.",
                        "Sertifika yenileme sürecini başlatın.",
                        days, cert.getNotAfter()));
            } else if (days != null && days <= 60) {
                advice.add(buildAdvice(domain, "EXPIRING_INFO", "info",
                        "Sertifika " + days + " gün içinde bitiyor.",
                        "Sertifika yenileme takviminizi güncelleyin.",
                        days, cert.getNotAfter()));
            }
        }

        Map<String, Integer> order = Map.of("critical", 0, "warning", 1, "info", 2);
        advice.sort(Comparator.comparingInt(a -> order.getOrDefault((String) a.get("priority"), 9)));
        return advice;
    }

    private Map<String, Object> buildAdvice(String domain, String code, String priority,
                                             String message, String action,
                                             Integer days, String notAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("code", code);
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
