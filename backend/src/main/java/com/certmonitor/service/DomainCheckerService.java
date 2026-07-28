package com.certmonitor.service;

import com.certmonitor.model.DomainCheck;
import com.certmonitor.model.DomainMonitor;
import com.certmonitor.repository.DomainCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alan adı süre-bitişi orkestratörü: kayıtlı domain'i (PSL) çözer → RDAP (birincil) → gerekirse WHOIS
 * (env-gated) → 4-seviyeli durum (OK/WARNING/CRITICAL/UNKNOWN) + EPP status sınıflaması + DNS çapraz
 * doğrulama (NS çözülüyor mu) + değişiklik tespiti (registrar/NS/status vs son başarılı kontrol) hesaplar,
 * geçmişe ({@link DomainCheck}) yazar. UNKNOWN "veri yok" = kendi başına bir durumdur (alarma dönüşür).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainCheckerService {

    private final PublicSuffixService psl;
    private final RdapDomainClient rdap;
    private final WhoisDomainClient whois;
    private final DnsCheckerService dns;
    private final DomainCheckRepository checkRepo;
    private final ActivityLogService activityLog;   // birleşik aktivite akışı (best-effort)

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** EPP kritik: anında CRITICAL (redemption/silme/hold). Normalleştirilmiş (harf-dışı atılmış, küçük). */
    static final Set<String> EPP_CRITICAL = Set.of("redemptionperiod", "pendingdelete", "serverhold", "clienthold");
    /** EPP uyarı: WARNING. */
    static final Set<String> EPP_WARN = Set.of("autorenewperiod", "pendingrenew");

    /** İzleme/manuel kontrol — kontrol + persist + değişiklik tespiti. */
    public Map<String, Object> check(DomainMonitor m) {
        Map<String, Object> r = evaluate(m.getDomain(), m.getWarningDays() != null ? m.getWarningDays() : 30,
                m.getCriticalDays() != null ? m.getCriticalDays() : 7, m.getId(), m.getCheckTimeoutMs());
        activityLog.recordCheck(ActivityLogService.DOMAIN, m.getId(), m.getName(),
                m.getDomain(), m.getTeamId(), false, "scheduler", r);
        return r;
    }

    /** Test (kaydetmeden) — persist yok, değişiklik tespiti yok. */
    public Map<String, Object> test(String domainInput, int warningDays, int criticalDays) {
        return evaluate(domainInput, warningDays, criticalDays, null, null);
    }

    private Map<String, Object> evaluate(String input, int warningDays, int criticalDays, Long monitorId, Integer timeoutMs) {
        String checkedAt = ISO.format(Instant.now());
        String reg = psl.registrableDomain(input);
        if (reg == null || reg.isBlank()) {
            Map<String, Object> out = unknownResult(input, "geçersiz/çözümlenemeyen domain", checkedAt);
            persist(monitorId, out, false);
            return out;
        }

        // RDAP birincil → gerekirse WHOIS fallback (monitör başına timeout override'ı ile)
        Map<String, Object> info = rdap.lookup(reg, timeoutMs);
        if ((info.get("error") != null || info.get("expiry_date") == null) && whois.enabled()) {
            Map<String, Object> w = whois.lookup(reg);
            if (w.get("expiry_date") != null && w.get("error") == null) info = w;
            else if (info.get("expiry_date") == null && w.get("expiry_date") != null) info = w;
        }

        String source = String.valueOf(info.getOrDefault("source", "NONE"));
        String expiry = (String) info.get("expiry_date");
        Integer days = daysUntil(expiry);
        List<String> statusCodes = asList(info.get("status_codes"));
        List<String> nameservers = asList(info.get("nameservers"));
        String registrar = (String) info.get("registrar");
        boolean hasData = !"NONE".equals(source) && expiry != null && days != null;

        Set<String> lc = statusCodes.stream().map(DomainCheckerService::normEpp).collect(Collectors.toSet());
        boolean eppCritical = lc.stream().anyMatch(EPP_CRITICAL::contains);
        boolean eppWarn = lc.stream().anyMatch(EPP_WARN::contains);
        // "Transfer kilidi yok" uyarısı yalnız RDAP kaynağı için (EPP kodları standarttır); WHOIS'te lock formatı
        // TLD'ye göre değişir → yanlış-pozitif önlemek için WHOIS'te bu uyarı üretilmez.
        boolean noTransferLock = hasData && "RDAP".equals(source)
                && !lc.contains("clienttransferprohibited") && !lc.contains("servertransferprohibited");

        String status;
        if (!hasData) status = "UNKNOWN";
        else if (eppCritical || days < 0 || days <= criticalDays) status = "CRITICAL";
        else if (eppWarn || noTransferLock || days <= warningDays) status = "WARNING";
        else status = "OK";

        // DNS çapraz doğrulama — NS kayıtları çözülüyor mu (best-effort)
        Boolean nsResolves = null;
        try { nsResolves = Boolean.TRUE.equals(dns.check(reg, "NS").get("success")); } catch (Exception ignore) {}

        // A/AAAA çözümlenen IP'ler + reverse-DNS (best-effort — Domain Kaydı görünümü)
        List<String> resolvedIps = new ArrayList<>();
        try { resolvedIps.addAll(asList(dns.check(reg, "A").get("values"))); } catch (Exception ignore) {}
        try { resolvedIps.addAll(asList(dns.check(reg, "AAAA").get("values"))); } catch (Exception ignore) {}
        List<String> hostnames = reverseDns(resolvedIps);

        // Değişiklik tespiti (yalnız persisted + veri var)
        boolean changed = false;
        String changeDetail = null;
        if (monitorId != null && hasData) {
            var prevOpt = checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(monitorId, "NONE");
            if (prevOpt.isPresent()) {
                DomainCheck prev = prevOpt.get();
                StringBuilder ch = new StringBuilder();
                if (registrar != null && prev.getRegistrar() != null && !registrar.equalsIgnoreCase(prev.getRegistrar()))
                    ch.append("registrar: ").append(prev.getRegistrar()).append(" → ").append(registrar).append("; ");
                if (setChanged(prev.getNameservers(), nameservers)) ch.append("nameserver seti değişti; ");
                if (setChanged(prev.getStatusCodes(), statusCodes)) ch.append("EPP status kodları değişti; ");
                if (ch.length() > 0) { changed = true; changeDetail = ch.toString().trim(); }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", reg);
        out.put("source", source);
        out.put("whois_provider", info.get("whois_provider"));   // .tr web-whois'te hangi kaynak (isimtescil/trabis/trabis43)
        out.put("status", status);
        out.put("days_remaining", days);
        out.put("expiry_date", expiry);
        out.put("registration_date", info.get("registration_date"));
        out.put("last_changed", info.get("last_changed"));
        out.put("registrar", registrar);
        out.put("registrar_iana_id", info.get("registrar_iana_id"));
        out.put("dnssec", info.get("dnssec"));
        out.put("status_codes", statusCodes);
        out.put("nameservers", nameservers);
        out.put("resolved_ips", resolvedIps);
        out.put("hostnames", hostnames);
        out.put("ns_resolves", nsResolves);
        out.put("changed", changed);
        out.put("change_detail", changeDetail);
        out.put("epp_critical", eppCritical);
        out.put("epp_warn", eppWarn || noTransferLock);
        out.put("no_transfer_lock", noTransferLock);
        out.put("error", info.get("error"));
        out.put("checked_at", checkedAt);
        persist(monitorId, out, changed);
        return out;
    }

    private void persist(Long monitorId, Map<String, Object> out, boolean changed) {
        if (monitorId == null) return;
        try {
            DomainCheck dc = new DomainCheck();
            dc.setMonitorId(monitorId);
            dc.setSource((String) out.get("source"));
            dc.setWhoisProvider((String) out.get("whois_provider"));
            dc.setStatus((String) out.get("status"));
            dc.setDaysRemaining((Integer) out.get("days_remaining"));
            dc.setExpiryDate((String) out.get("expiry_date"));
            dc.setRegistrationDate((String) out.get("registration_date"));
            dc.setLastChanged((String) out.get("last_changed"));
            dc.setRegistrar((String) out.get("registrar"));
            dc.setRegistrarIanaId((String) out.get("registrar_iana_id"));
            dc.setDnssec((String) out.get("dnssec"));
            dc.setStatusCodes(join(asList(out.get("status_codes"))));
            dc.setNameservers(join(asList(out.get("nameservers"))));
            dc.setResolvedIps(join(asList(out.get("resolved_ips"))));
            dc.setHostnames(join(asList(out.get("hostnames"))));
            dc.setNsResolves((Boolean) out.get("ns_resolves"));
            dc.setChanged(changed);
            dc.setRawSummary(summary(out));
            dc.setError((String) out.get("error"));
            dc.setCheckedAt((String) out.get("checked_at"));
            checkRepo.save(dc);
        } catch (Exception e) {
            log.warn("Domain kaydı yazılamadı: {} — {}", out.get("domain"), e.getMessage());
        }
    }

    /** Çözülen IP'ler için reverse-DNS (PTR) — best-effort, en fazla ilk 8 IP; PTR yoksa (host==ip) atlanır. */
    private static List<String> reverseDns(List<String> ips) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String ip : ips) {
            if (ip == null || ip.isBlank()) continue;
            if (n++ >= 8) break;
            try {
                String host = java.net.InetAddress.getByName(ip).getCanonicalHostName();
                if (host != null && !host.equalsIgnoreCase(ip)) out.add(host.toLowerCase(java.util.Locale.ROOT));
            } catch (Exception ignore) {}
        }
        return out;
    }

    private Map<String, Object> unknownResult(String domain, String error, String checkedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain); out.put("source", "NONE"); out.put("status", "UNKNOWN");
        out.put("days_remaining", null); out.put("expiry_date", null); out.put("registration_date", null);
        out.put("last_changed", null); out.put("registrar", null);
        out.put("status_codes", List.of()); out.put("nameservers", List.of()); out.put("ns_resolves", null);
        out.put("changed", false); out.put("change_detail", null);
        out.put("epp_critical", false); out.put("epp_warn", false); out.put("no_transfer_lock", false);
        out.put("error", error); out.put("checked_at", checkedAt);
        return out;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────

    /** ISO tarihten bugüne kalan tam gün (negatif = geçmiş). Parse edilemezse null. */
    static Integer daysUntil(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            Instant when;
            try { when = OffsetDateTime.parse(iso).toInstant(); }
            catch (Exception e1) {
                try { when = Instant.parse(iso); }
                catch (Exception e2) { when = LocalDate.parse(iso.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            }
            return (int) ChronoUnit.DAYS.between(Instant.now(), when);
        } catch (Exception e) { return null; }
    }

    static String normEpp(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return new ArrayList<>();
    }

    private static String join(List<String> l) {
        return (l == null || l.isEmpty()) ? null : String.join(",", l);
    }

    /** Önceki CSV ile yeni liste (küçük harf, sıra bağımsız) farklı mı. */
    private static boolean setChanged(String prevCsv, List<String> now) {
        Set<String> prev = new LinkedHashSet<>();
        if (prevCsv != null) for (String s : prevCsv.split(",")) { s = s.trim().toLowerCase(Locale.ROOT); if (!s.isEmpty()) prev.add(s); }
        Set<String> cur = now == null ? Set.of() : now.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (prev.isEmpty() || cur.isEmpty()) return false;   // veri eksikse "değişti" deme (gürültü önleme)
        return !prev.equals(cur);
    }

    private static String summary(Map<String, Object> out) {
        StringBuilder sb = new StringBuilder();
        sb.append("source=").append(out.get("source"));
        if (out.get("expiry_date") != null) sb.append(" expiry=").append(out.get("expiry_date"));
        if (out.get("registrar") != null) sb.append(" registrar=").append(out.get("registrar"));
        List<String> sc = asList(out.get("status_codes"));
        if (!sc.isEmpty()) sb.append(" status=").append(String.join("|", sc));
        return sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
    }
}
