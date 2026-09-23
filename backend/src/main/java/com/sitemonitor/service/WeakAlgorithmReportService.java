package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.service.CertificateHealthRules.CipherTier;
import com.sitemonitor.service.CertificateHealthRules.Status;
import com.sitemonitor.util.Csv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Zayıf Algoritma Raporu — zengin gövde (2026-09-12, kullanıcı: "sayfa çok uzun süredir boş; neyi
 * gösteriyor, ne bekliyor, ne yok — zenginleştirelim").
 *
 * <p>Eski sözleşme ({@code data/total/critical/high}) AYNEN korunur; üstüne şu bölümler eklenir:
 * <ul>
 *   <li>{@code scan}     — tarama kapsamı: aktif alan, 24 saatte kontrol edilen, kontrol edilemeyen/hiç edilmeyen</li>
 *   <li>{@code rules}    — kural kataloğu + her kuralın şu an eşleşen sayısı ("neye bakıyorum")</li>
 *   <li>{@code distribution} — imza / anahtar / TLS sürümü / şifre kademesi kırılımı</li>
 *   <li>{@code outlook}  — 2030 eşiği (NIST SP 800-57: RSA/DSA ≥ 3072) yükselince etkilenecek alanlar</li>
 *   <li>{@code tls}      — protokol / şifre / PFS bulguları (sertifika algoritmasından bağımsız)</li>
 *   <li>{@code chain}    — zincir / güven / iptal / OCSP-CRL / ara sertifika bulguları</li>
 *   <li>{@code teams}    — takım başına zayıf/toplam + sahipsiz zayıf alanlar</li>
 *   <li>{@code trend}    — son 30 gün: gün başına zayıf alan sayısı, tespit/temizlenme olayları</li>
 *   <li>{@code exceptions} — kabul edilmiş / planlı yenileme istisnaları (satırlara da işlenir)</li>
 * </ul>
 *
 * <p>Hükümler {@link CertificateHealthRules}'a devredilir: aynı sertifika bir ekranda "zayıf" diğerinde
 * "temiz" görünmesin. Tüm anahtarlar ({@code rule}, {@code finding}) İNGİLİZCE sabit — çeviri ön yüzde.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeakAlgorithmReportService {

    /** Kurum saat dilimi — gün sınırı bu zona göre (proje konvansiyonu). */
    private static final java.time.ZoneId ORG_ZONE = java.time.ZoneId.of("Europe/Istanbul");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int TREND_DAYS = 30;
    /** NIST SP 800-57 2030 sonrası asgari: RSA/DSA 3072 bit (112-bit güvenlik sınıfı biter). */
    static final int RSA_2030_MIN_BITS = 3072;
    /** NIST SP 800-131A Rev.2: 112-bit güvenlik sınıfı (RSA 2048) 2030-12-31 sonrası KABUL EDİLMEZ. */
    static final LocalDate RSA_2048_SUNSET = LocalDate.of(2030, 12, 31);
    static final int INTERMEDIATE_WARN_DAYS = 30;

    /** Kural kataloğu — sıra arayüz sırasıdır. */
    static final List<String[]> RULES = List.of(
            // key, severity
            // Şiddetler CertificateHealthRules.classifyWeakness ile AYNI (SHA-1 HIGH; RSA ≤1024 CRITICAL,
            // 1025–2047 HIGH; EC <192 CRITICAL, 192–255 HIGH) — kural kartı ayrı bir hüküm vermez.
            new String[] {"sig.md5",      "CRITICAL"},
            new String[] {"sig.sha1",     "HIGH"},
            new String[] {"key.rsa1024",  "CRITICAL"},
            new String[] {"key.rsa2048",  "HIGH"},
            new String[] {"key.ec192",    "CRITICAL"},
            new String[] {"key.ec256",    "HIGH"},
            new String[] {"tls.legacy",   "CRITICAL"},
            new String[] {"cipher.weak",  "CRITICAL"},
            new String[] {"cipher.cbc",   "MEDIUM"},
            new String[] {"pfs.none",     "HIGH"},
            new String[] {"chain.broken", "HIGH"},
            new String[] {"trust.untrusted", "HIGH"},
            new String[] {"revocation.revoked", "CRITICAL"},
            new String[] {"revocation.nourl", "MEDIUM"},
            new String[] {"intermediate.expiring", "MEDIUM"});

    private final LatestCheckRepository latestCheckRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final CertificateCheckRepository certificateCheckRepo;
    private final WeakAlgorithmExceptionRepository exceptionRepo;

    // ── Rapor gövdesi ──────────────────────────────────────────────────────────────────────

    public Map<String, Object> build() {
        Instant now = Instant.now();
        List<CertificateInventory> active = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        Map<String, CertificateInventory> invByDomain = new LinkedHashMap<>();
        for (CertificateInventory i : active) invByDomain.putIfAbsent(i.getDomain(), i);

        Map<String, LatestCheck> lcByDomain = new HashMap<>();
        for (LatestCheck lc : latestCheckRepo.findAll())
            if (lc.getDomain() != null && invByDomain.containsKey(lc.getDomain())) lcByDomain.put(lc.getDomain(), lc);

        Map<Long, Team> teams = teamRepo.findAll().stream()
                .collect(Collectors.toMap(Team::getId, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, WeakAlgorithmException> exceptions = exceptionRepo.findAll().stream()
                .collect(Collectors.toMap(WeakAlgorithmException::getDomain, e -> e, (a, b) -> a));
        // Kurum saatiyle "bugün": UTC kullanmak gece 00:00-03:00 arasında süresi IST'ye göre
        // dolmuş bir istisnayı 3 saat daha "kabul edildi" gösteriyor ve gerçek bir zayıf-kripto
        // bulgusunu gizliyordu. Kardeş yüzeyler (yenileme tahmini, sertifika kartı) IST kullanıyor.
        String today = LocalDate.now(ORG_ZONE).toString();

        // ── Satırlar (eski sözleşme) + tls / chain bulguları ─────────────────────────────
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> tlsRows = new ArrayList<>();
        List<Map<String, Object>> chainRows = new ArrayList<>();
        Map<String, Integer> ruleHits = new LinkedHashMap<>();
        for (String[] r : RULES) ruleHits.put(r[0], 0);

        Map<String, Integer> sigDist = new TreeMap<>(), keyDist = new TreeMap<>(),
                             tlsDist = new TreeMap<>(), cipherDist = new LinkedHashMap<>();
        for (CipherTier t : CipherTier.values()) cipherDist.put(t.name(), 0);
        List<Map<String, Object>> outlook = new ArrayList<>();

        int checked24h = 0, errorCount = 0, neverChecked = 0;
        String latestCheckedAt = null;
        Instant dayAgo = now.minus(24, ChronoUnit.HOURS);

        Map<Long, int[]> teamCounts = new LinkedHashMap<>();   // [total, weak, tls, chain]
        int[] unowned = new int[4];

        for (CertificateInventory inv : active) {
            LatestCheck lc = lcByDomain.get(inv.getDomain());
            int[] tc = inv.getTeamId() == null ? unowned : teamCounts.computeIfAbsent(inv.getTeamId(), k -> new int[4]);
            tc[0]++;
            if (lc == null || lc.getCheckedAt() == null) { neverChecked++; continue; }
            Instant at = parse(lc.getCheckedAt());
            if (at != null && at.isAfter(dayAgo)) checked24h++;
            if (latestCheckedAt == null || lc.getCheckedAt().compareTo(latestCheckedAt) > 0) latestCheckedAt = lc.getCheckedAt();
            if ("error".equals(lc.getStatus())) errorCount++;

            // Dağılım
            bump(sigDist, blankTo(lc.getSignatureAlgorithm(), "—"));
            bump(keyDist, keyLabel(lc.getPublicKeyAlgorithm(), lc.getPublicKeySize()));
            bump(tlsDist, blankTo(lc.getTlsVersion(), "—"));
            bump(cipherDist, CertificateHealthRules.cipherTier(lc.getCipherSuite()).name());

            // 2030 görünümü: bugün OK ama 3072 altı RSA/DSA
            if (isRsaLike(lc.getPublicKeyAlgorithm()) && lc.getPublicKeySize() != null
                    && lc.getPublicKeySize() >= 2048 && lc.getPublicKeySize() < RSA_2030_MIN_BITS) {
                Map<String, Object> o = baseRow(lc, inv, teams);
                o.put("reason", "key.rsa3072");
                // Beklenen eylem (2026-09-12, kullanıcı: "domainlerden bekleneni daha net aktaralım"):
                //   renew   → sertifika 2030 sonundan ÖNCE zaten dolacak: sıradaki yenilemede CSR'ı 3072+/EC üret
                //   reissue → sertifika 2030'u AŞIYOR: yenilemeyi beklemek yetmez, erken yeniden düzenleme gerekir
                //   unknown → bitiş tarihi bilinmiyor
                LocalDate notAfter = parseDate(lc.getNotAfter());
                String action = notAfter == null ? "unknown" : notAfter.isAfter(RSA_2048_SUNSET) ? "reissue" : "renew";
                o.put("action", action);
                o.put("renewal_by", notAfter == null ? null : (notAfter.isAfter(RSA_2048_SUNSET) ? RSA_2048_SUNSET : notAfter).toString());
                o.put("target", "RSA 3072 / ECDSA P-256");
                o.put("exception", exceptions.get(inv.getDomain()) == null ? null : exceptionJson(exceptions.get(inv.getDomain()), today));
                if (o.get("exception") == null) o.remove("exception");
                outlook.add(o);
            }

            // Sertifika algoritması (eski rapor)
            List<String> weaknesses = new ArrayList<>();
            String severity = CertificateHealthRules.classifyWeakness(
                    lc.getSignatureAlgorithm(), lc.getPublicKeyAlgorithm(), lc.getPublicKeySize(), weaknesses);
            if (severity != null) {
                Map<String, Object> row = baseRow(lc, inv, teams);
                row.put("weaknesses", weaknesses);
                row.put("severity", severity);
                List<String> keys = certRuleKeys(lc);
                row.put("rule_keys", keys);
                keys.forEach(k -> ruleHits.merge(k, 1, Integer::sum));
                attachException(row, exceptions.get(inv.getDomain()), today);
                rows.add(row);
                tc[1]++;
            }

            // TLS bulguları
            List<String> tlsFindings = new ArrayList<>();
            if (CertificateHealthRules.protocolStatus(lc.getTlsVersion()) == Status.FAIL) tlsFindings.add("tls.legacy");
            CipherTier tier = CertificateHealthRules.cipherTier(lc.getCipherSuite());
            if (tier == CipherTier.WEAK) tlsFindings.add("cipher.weak");
            else if (tier == CipherTier.ACCEPTABLE) tlsFindings.add("cipher.cbc");
            if (CertificateHealthRules.pfsStatus(lc.getTlsVersion(), lc.getCipherSuite()) == Status.FAIL) tlsFindings.add("pfs.none");
            if (!tlsFindings.isEmpty()) {
                Map<String, Object> row = baseRow(lc, inv, teams);
                row.put("findings", tlsFindings);
                row.put("severity", worstOf(tlsFindings));
                tlsFindings.forEach(k -> ruleHits.merge(k, 1, Integer::sum));
                attachException(row, exceptions.get(inv.getDomain()), today);
                tlsRows.add(row);
                tc[2]++;
            }

            // Zincir / güven bulguları
            List<String> chainFindings = new ArrayList<>();
            if (CertificateHealthRules.fromStatusLabel(lc.getChainStatus(), "VALID", "BROKEN") == Status.FAIL) chainFindings.add("chain.broken");
            if (CertificateHealthRules.fromStatusLabel(lc.getTrustStatus(), "TRUSTED", "UNTRUSTED") == Status.FAIL) chainFindings.add("trust.untrusted");
            if (CertificateHealthRules.fromStatusLabel(lc.getRevocationStatus(), "VALID", "REVOKED") == Status.FAIL) chainFindings.add("revocation.revoked");
            if (!"error".equals(lc.getStatus()) && isBlank(lc.getOcspUrl()) && isBlank(lc.getCrlUrl())
                    && !isBlank(lc.getSignatureAlgorithm())) chainFindings.add("revocation.nourl");
            Integer idays = lc.getIntermediateDaysRemaining();
            if (idays != null && idays <= INTERMEDIATE_WARN_DAYS) chainFindings.add("intermediate.expiring");
            if (!chainFindings.isEmpty()) {
                Map<String, Object> row = baseRow(lc, inv, teams);
                row.put("findings", chainFindings);
                row.put("severity", worstOf(chainFindings));
                row.put("intermediate_days", idays);
                chainFindings.forEach(k -> ruleHits.merge(k, 1, Integer::sum));
                attachException(row, exceptions.get(inv.getDomain()), today);
                chainRows.add(row);
                tc[3]++;
            }
        }

        rows.sort(bySeverity());
        tlsRows.sort(bySeverity());
        chainRows.sort(bySeverity());

        long critical = rows.stream().filter(r -> "CRITICAL".equals(r.get("severity"))).count();
        long high     = rows.stream().filter(r -> "HIGH".equals(r.get("severity"))).count();
        long excepted = rows.stream().filter(r -> r.get("exception") != null).count();

        // ── Bölümler ─────────────────────────────────────────────────────────────────────
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("active_domains", active.size());
        scan.put("checked", active.size() - neverChecked);
        scan.put("checked_24h", checked24h);
        scan.put("never_checked", neverChecked);
        scan.put("error", errorCount);
        scan.put("latest_checked_at", latestCheckedAt);
        scan.put("generated_at", ISO.format(now));

        List<Map<String, Object>> rules = new ArrayList<>();
        for (String[] r : RULES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r[0]); m.put("severity", r[1]); m.put("matched", ruleHits.get(r[0]));
            rules.add(m);
        }

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("signature", sortedDesc(sigDist));
        distribution.put("key", sortedDesc(keyDist));
        distribution.put("tls", sortedDesc(tlsDist));
        distribution.put("cipher_tier", sortedDesc(cipherDist));

        Map<String, Object> outlookMap = new LinkedHashMap<>();
        outlookMap.put("year", 2030);
        outlookMap.put("sunset", RSA_2048_SUNSET.toString());
        outlookMap.put("rsa_min_bits", RSA_2030_MIN_BITS);
        outlookMap.put("affected", outlook.size());
        // Risk özeti: filo payı, eylem kırılımı, takım kırılımı, en erken/en geç bitiş, kalan gün.
        int rsaFleet = 0;
        for (LatestCheck lc : lcByDomain.values()) if (isRsaLike(lc.getPublicKeyAlgorithm())) rsaFleet++;
        int checkedCount = active.size() - neverChecked;
        long reissue = outlook.stream().filter(m -> "reissue".equals(m.get("action"))).count();
        long renew   = outlook.stream().filter(m -> "renew".equals(m.get("action"))).count();
        long unknown = outlook.stream().filter(m -> "unknown".equals(m.get("action"))).count();
        Map<String, Integer> byTeam = new LinkedHashMap<>();
        for (Map<String, Object> m : outlook) byTeam.merge(m.get("team_name") == null ? "—" : String.valueOf(m.get("team_name")), 1, Integer::sum);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", checkedCount);
        summary.put("rsa_fleet", rsaFleet);
        summary.put("pct_of_checked", checkedCount == 0 ? 0 : Math.round(100.0 * outlook.size() / checkedCount));
        summary.put("pct_of_rsa", rsaFleet == 0 ? 0 : Math.round(100.0 * outlook.size() / rsaFleet));
        summary.put("reissue", reissue);
        summary.put("renew", renew);
        summary.put("unknown", unknown);
        summary.put("days_to_sunset", ChronoUnit.DAYS.between(now.atZone(ZoneOffset.UTC).toLocalDate(), RSA_2048_SUNSET));
        summary.put("by_team", sortedDesc(byTeam));
        outlookMap.put("summary", summary);
        // Erken eylem gerekenler (reissue) üstte, sonra en yakın yenileme tarihi
        outlook.sort((a, b) -> {
            int c = Integer.compare(actionRank((String) a.get("action")), actionRank((String) b.get("action")));
            if (c != 0) return c;
            String ra = (String) a.get("renewal_by"), rb = (String) b.get("renewal_by");
            if (ra == null || rb == null) return ra == null ? (rb == null ? 0 : 1) : -1;
            c = ra.compareTo(rb);
            return c != 0 ? c : String.valueOf(a.get("domain")).compareTo(String.valueOf(b.get("domain")));
        });
        outlookMap.put("rows", outlook);

        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : teamCounts.entrySet()) {
            Team t = teams.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", e.getKey());
            m.put("team_name", t == null ? "#" + e.getKey() : t.getName());
            m.put("total", e.getValue()[0]); m.put("weak", e.getValue()[1]);
            m.put("tls", e.getValue()[2]); m.put("chain", e.getValue()[3]);
            teamRows.add(m);
        }
        teamRows.sort((a, b) -> {
            int c = Integer.compare((int) b.get("weak") + (int) b.get("tls") + (int) b.get("chain"),
                                    (int) a.get("weak") + (int) a.get("tls") + (int) a.get("chain"));
            return c != 0 ? c : String.valueOf(a.get("team_name")).compareToIgnoreCase(String.valueOf(b.get("team_name")));
        });
        Map<String, Object> teamsMap = new LinkedHashMap<>();
        teamsMap.put("rows", teamRows);
        Map<String, Object> un = new LinkedHashMap<>();
        un.put("total", unowned[0]); un.put("weak", unowned[1]); un.put("tls", unowned[2]); un.put("chain", unowned[3]);
        teamsMap.put("unowned", un);

        List<Map<String, Object>> exceptionRows = exceptions.values().stream()
                .sorted(Comparator.comparing(WeakAlgorithmException::getDomain))
                .map(e -> exceptionJson(e, today)).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", rows);
        body.put("total", rows.size());
        body.put("critical", critical);
        body.put("high", high);
        body.put("excepted", excepted);
        body.put("scan", scan);
        body.put("rules", rules);
        body.put("distribution", distribution);
        body.put("outlook", outlookMap);
        body.put("tls", Map.of("total", tlsRows.size(), "rows", tlsRows));
        body.put("chain", Map.of("total", chainRows.size(), "rows", chainRows));
        body.put("teams", teamsMap);
        body.put("trend", trend(now, rows.stream().map(r -> (String) r.get("domain")).collect(Collectors.toSet())));
        body.put("exceptions", exceptionRows);
        return body;
    }

    /**
     * Takım için haftalık e-posta satırı: {zayıf, taranan}. Haftalık servis bu sınıfı DEĞİL depoları
     * enjekte eder (dairesel referans); bu yardımcı yine de tek kaynak olsun diye statik.
     */
    public static int[] weakAndScannedFor(Collection<String> teamDomains, List<LatestCheck> weakCandidates,
                                          Map<String, LatestCheck> latestByDomain) {
        Set<String> set = new HashSet<>(teamDomains);
        int weak = 0;
        for (LatestCheck lc : weakCandidates) {
            if (lc.getDomain() == null || !set.contains(lc.getDomain())) continue;
            if (CertificateHealthRules.classifyWeakness(lc.getSignatureAlgorithm(), lc.getPublicKeyAlgorithm(),
                    lc.getPublicKeySize(), new ArrayList<>()) != null) weak++;
        }
        int scanned = 0;
        for (String d : set) { LatestCheck lc = latestByDomain.get(d); if (lc != null && lc.getCheckedAt() != null) scanned++; }
        return new int[] {weak, scanned};
    }

    // ── Trend ──────────────────────────────────────────────────────────────────────────────

    /**
     * Son 30 gün: gün başına ZAYIF görülen ayrık alan sayısı (certificate_checks'ten yalnız zayıf
     * satırlar); {@code detected} = pencere içinde ilk kez görülen alan, {@code resolved} = son zayıf
     * gözlemi bugün olmayan ve şu an zayıf listesinde OLMAYAN alan (yenilenip temizlendi).
     */
    Map<String, Object> trend(Instant now, Set<String> weakNow) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate from = today.minusDays(TREND_DAYS - 1L);
        Map<String, Integer> perDay = new LinkedHashMap<>();
        for (int i = 0; i < TREND_DAYS; i++) perDay.put(from.plusDays(i).toString(), 0);

        Map<String, TreeSet<String>> daysByDomain = new HashMap<>();
        try {
            for (Object[] o : certificateCheckRepo.weakObservationsSince(from.toString() + "T00:00:00")) {
                String domain = (String) o[0], day = (String) o[1];
                if (domain == null || day == null || !perDay.containsKey(day)) continue;
                perDay.merge(day, 1, Integer::sum);
                daysByDomain.computeIfAbsent(domain, k -> new TreeSet<>()).add(day);
            }
        } catch (Exception e) {
            log.warn("Zayıf algoritma trendi hesaplanamadı (rapor trendsiz döner): {}", e.toString());
        }
        List<Map<String, Object>> detected = new ArrayList<>(), resolved = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : daysByDomain.entrySet()) {
            String first = e.getValue().first(), last = e.getValue().last();
            if (!first.equals(from.toString())) detected.add(Map.of("domain", e.getKey(), "day", first));
            if (!weakNow.contains(e.getKey()) && !last.equals(today.toString()))
                resolved.add(Map.of("domain", e.getKey(), "day", last));
        }
        detected.sort(Comparator.comparing(m -> (String) m.get("day")));
        resolved.sort(Comparator.comparing(m -> (String) m.get("day")));

        List<Map<String, Object>> series = new ArrayList<>();
        perDay.forEach((d, n) -> series.add(Map.of("day", d, "weak", n)));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("days", TREND_DAYS);
        t.put("series", series);
        t.put("detected", detected);
        t.put("resolved", resolved);
        return t;
    }

    // ── İstisnalar ─────────────────────────────────────────────────────────────────────────

    public WeakAlgorithmException setException(String domain, String reason, String until, String actor) {
        if (isBlank(domain)) throw new IllegalArgumentException("domain");
        if (isBlank(until)) throw new IllegalArgumentException("until");
        LocalDate u;
        try { u = LocalDate.parse(until.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("until_format"); }
        if (u.isBefore(LocalDate.now(ORG_ZONE))) throw new IllegalArgumentException("until_past");   // kurum saati (bkz. build())
        WeakAlgorithmException e = exceptionRepo.findByDomain(domain.trim()).orElseGet(WeakAlgorithmException::new);
        e.setDomain(domain.trim());
        e.setReason(reason == null ? null : reason.trim());
        e.setUntil(u.toString());
        e.setCreatedBy(actor);
        e.setCreatedAt(ISO.format(Instant.now()));
        return exceptionRepo.save(e);
    }

    public boolean clearException(String domain) {
        Optional<WeakAlgorithmException> e = exceptionRepo.findByDomain(domain);
        if (e.isEmpty()) return false;
        exceptionRepo.deleteById(e.get().getId());
        return true;
    }

    // ── CSV ────────────────────────────────────────────────────────────────────────────────

    /** Üç bulgu kümesi tek dosyada: kaynak sütunu (certificate / tls / chain). */
    public String toCsv(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row("source", "severity", "domain", "team", "owner", "signature_algorithm",
                "public_key", "tls_version", "cipher_suite", "findings", "not_after", "days_remaining",
                "status", "checked_at", "exception_until", "exception_reason"));
        appendCsv(sb, "certificate", listOf(body.get("data")));
        appendCsv(sb, "tls", listOf(((Map<?, ?>) body.get("tls")).get("rows")));
        appendCsv(sb, "chain", listOf(((Map<?, ?>) body.get("chain")).get("rows")));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static void appendCsv(StringBuilder sb, String source, List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            Object findings = r.get("findings") != null ? r.get("findings") : r.get("weaknesses");
            Map<?, ?> ex = r.get("exception") instanceof Map<?, ?> m ? m : null;
            sb.append(Csv.row(source, r.get("severity"), r.get("domain"), r.get("team_name"), r.get("owner"),
                    r.get("signature_algorithm"),
                    keyLabel((String) r.get("public_key_algorithm"), (Integer) r.get("public_key_size")),
                    r.get("tls_version"), r.get("cipher_suite"),
                    findings instanceof List<?> l ? String.join(" | ", l.stream().map(String::valueOf).toList()) : "",
                    r.get("not_after"), r.get("days_remaining"), r.get("status"), r.get("checked_at"),
                    ex == null ? "" : ex.get("until"), ex == null ? "" : ex.get("reason")));
        }
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> baseRow(LatestCheck lc, CertificateInventory inv, Map<Long, Team> teams) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("domain",               lc.getDomain());
        row.put("subject",              lc.getSubject());
        row.put("issuer",               lc.getIssuer());
        row.put("signature_algorithm",  lc.getSignatureAlgorithm());
        row.put("public_key_algorithm", lc.getPublicKeyAlgorithm());
        row.put("public_key_size",      lc.getPublicKeySize());
        row.put("tls_version",          lc.getTlsVersion());
        row.put("cipher_suite",         lc.getCipherSuite());
        row.put("not_after",            lc.getNotAfter());
        row.put("days_remaining",       lc.getDaysRemaining());
        row.put("status",               lc.getStatus());
        row.put("checked_at",           lc.getCheckedAt());
        row.put("owner",       inv.getOwner());
        row.put("description", inv.getDescription());
        Long tid = inv.getTeamId();
        row.put("team_id",     tid);
        Team t = tid == null ? null : teams.get(tid);
        if (t != null) { row.put("team_name", t.getName()); row.put("team_email", t.getEmail()); }
        return row;
    }

    private static void attachException(Map<String, Object> row, WeakAlgorithmException e, String today) {
        if (e == null) return;
        row.put("exception", exceptionJson(e, today));
    }

    private static Map<String, Object> exceptionJson(WeakAlgorithmException e, String today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("domain", e.getDomain());
        m.put("reason", e.getReason());
        m.put("until", e.getUntil());
        m.put("created_by", e.getCreatedBy());
        m.put("created_at", e.getCreatedAt());
        m.put("expired", e.getUntil() != null && e.getUntil().compareTo(today) < 0);
        return m;
    }

    static List<String> certRuleKeys(LatestCheck lc) {
        List<String> keys = new ArrayList<>();
        String sig = lc.getSignatureAlgorithm() == null ? "" : lc.getSignatureAlgorithm().toUpperCase(Locale.ROOT);
        if (sig.contains("MD2") || sig.contains("MD5")) keys.add("sig.md5");
        if (sig.contains("SHA1") || sig.contains("SHA-1")) keys.add("sig.sha1");
        String key = lc.getPublicKeyAlgorithm() == null ? "" : lc.getPublicKeyAlgorithm().toUpperCase(Locale.ROOT);
        Integer size = lc.getPublicKeySize();
        if (size != null) {
            if ((key.contains("RSA") || key.contains("DSA")) && size < 2048) keys.add(size <= 1024 ? "key.rsa1024" : "key.rsa2048");
            else if (key.contains("EC") && size < 256) keys.add(size < 192 ? "key.ec192" : "key.ec256");
        }
        return keys;
    }

    private static String worstOf(List<String> keys) {
        String worst = "MEDIUM";
        for (String k : keys) {
            String sev = RULES.stream().filter(r -> r[0].equals(k)).map(r -> r[1]).findFirst().orElse("MEDIUM");
            if (rank(sev) > rank(worst)) worst = sev;
        }
        return worst;
    }

    static int rank(String s) {
        return switch (s == null ? "" : s) { case "CRITICAL" -> 3; case "HIGH" -> 2; case "MEDIUM" -> 1; default -> 0; };
    }

    private static Comparator<Map<String, Object>> bySeverity() {
        return (a, b) -> {
            int c = rank((String) b.get("severity")) - rank((String) a.get("severity"));
            return c != 0 ? c : String.valueOf(a.get("domain")).compareTo(String.valueOf(b.get("domain")));
        };
    }

    private static List<Map<String, Object>> sortedDesc(Map<String, Integer> m) {
        return m.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> { int c = b.getValue() - a.getValue(); return c != 0 ? c : a.getKey().compareTo(b.getKey()); })
                .map(e -> { Map<String, Object> x = new LinkedHashMap<>(); x.put("label", e.getKey()); x.put("count", e.getValue()); return x; })
                .toList();
    }

    private static void bump(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    static String keyLabel(String alg, Integer size) {
        if (isBlank(alg)) return "—";
        return size == null || size <= 0 ? alg : alg + " " + size;
    }

    private static boolean isRsaLike(String alg) {
        if (isBlank(alg)) return false;
        String up = alg.toUpperCase(Locale.ROOT);
        return up.contains("RSA") || up.contains("DSA");
    }

    private static int actionRank(String a) {
        return switch (a == null ? "" : a) { case "reissue" -> 0; case "renew" -> 1; default -> 2; };
    }

    private static LocalDate parseDate(String iso) {
        if (isBlank(iso)) return null;
        try { return LocalDate.parse(iso.trim().substring(0, Math.min(10, iso.trim().length()))); }
        catch (Exception e) { return null; }
    }

    private static String blankTo(String s, String d) { return isBlank(s) ? d : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static Instant parse(String iso) {
        if (isBlank(iso)) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); }
        catch (Exception e) { return null; }
    }
}
