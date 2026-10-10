package com.sitemonitor.service.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.service.CertificateHealthRules;
import com.sitemonitor.service.crypto.CryptoClassifier.Category;
import com.sitemonitor.service.crypto.CryptoClassifier.KeyBucket;
import com.sitemonitor.service.crypto.CryptoClassifier.PqcStatus;
import com.sitemonitor.service.crypto.CryptoClassifier.SigHash;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Kripto envanteri ve kuantum sonrası (PQC) hazırlık raporu (2026-10-10, kullanıcı isteği: "Anahtar algoritması ve boyu
 * dağılımını, SHA-1 kalıntılarını ve takım bazlı geçiş listesini gösterir. Düzenleyici raporlama için hazır olur.").
 *
 * <p><b>Yeni veri yok.</b> Ağ uç noktaları {@code latest_checks}'ten (yaprak anahtar/imza + {@code chain_details}
 * içindeki ara sertifika imzaları + TLS sürümü/şifre), yüklenen (manuel) sertifikalar geçerli sürümün KAYITLI
 * alanlarından ({@code manual_certificate_versions}: anahtar algoritması/boyu, imza, bitiş) okunur — anahtar deposundaki
 * sertifika ağda görünmediği için onun kaynağı yüklenen dosyadır. Sınıflandırma {@link CryptoClassifier}, öncelik
 * {@link PqcMigrationPriority} — ikisi de saf ve tek kaynak.
 *
 * <p><b>Kapsam</b> Zayıf Algoritma raporuyla BİREBİR: {@code viewTeamIds == null} → tüm takımlar (global admin /
 * AUDIT), dolu liste → yalnız SY ya da UG takımı kapsamda olan aktif kayıtlar; sahipsiz kayıt kapsamlı kullanıcıya
 * görünmez. Satır alan adı başına BİR (ilk envanter kaydı — Zayıf Algoritma raporuyla aynı tekilleştirme).
 *
 * <p><b>Maliyet:</b> tek geçiş; sabit sayıda sorgu (envanter, son kontroller — kapsamlıda 500'lük parçalarla —, takımlar,
 * manuel sürümler 500'lük parçalarla, istisnalar). Satır başına sorgu YOK. Sonuç görüş kapsamı başına
 * {@code site.monitor.crypto-inventory.cache-ms} (120 sn) saklanır; {@code fresh} belleği en fazla 5 sn'de bir atlar.
 *
 * <p>Kullanıcı kimliği taşımaz: {@code owner} envanterin serbest metin sahip alanıdır, yükleyen kullanıcı adı
 * (manuel sürümün {@code uploaded_by}) BİLEREK dışarıda bırakılır.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CryptoInventoryService {

    static final long FRESH_MIN_MS = 5_000L;
    static final int IN_CHUNK = 500;
    /** NIST SP 800-131A Rev.2: 112-bit güvenlik (RSA 2048) 2030-12-31 sonrası kabul edilmez. */
    static final LocalDate RSA_2048_SUNSET = LocalDate.of(2030, 12, 31);
    static final int SIG_LABEL_LIMIT = 20;
    static final int WEAK_INTERMEDIATE_LIMIT = 5;
    private static final ZoneId ORG_ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TeamRepository teamRepo;
    private final ManualCertificateVersionRepository manualVersionRepo;
    private final WeakAlgorithmExceptionRepository exceptionRepo;

    @Value("${site.monitor.crypto-inventory.cache-ms:120000}")
    long cacheMs;
    private final TtlMemo<Map<String, Object>> memo = new TtlMemo<>(500);

    // ── Genel giriş ──────────────────────────────────────────────────────────────────────

    public Map<String, Object> build(List<Long> viewTeamIds) {
        return build(viewTeamIds, false);
    }

    /**
     * @param viewTeamIds {@code null} → tüm takımlar; liste → yalnız o takımlar (SY ya da UG)
     * @param fresh       belleği atla (en fazla {@link #FRESH_MIN_MS}'de bir yeniden hesaplanır)
     */
    public Map<String, Object> build(List<Long> viewTeamIds, boolean fresh) {
        long ttl = fresh && cacheMs > 0 ? Math.min(cacheMs, FRESH_MIN_MS) : cacheMs;
        Map<String, Object> m = memo.get(TtlMemo.scopeKey(viewTeamIds == null, viewTeamIds), ttl, false,
                () -> buildUncached(viewTeamIds, Instant.now()));
        return m == null ? null : new LinkedHashMap<>(m);   // üst düzey kopya: paylaşılan bellek değişmesin
    }

    /**
     * Başka raporların (ör. aylık yönetici özeti) yeniden kullanması için KÜÇÜK özet: kategori / PQC / bant sayıları,
     * imza kalıntıları ve öncelik sırasındaki ilk {@code topN} uç nokta. Aynı bellekten gelir (ek sorgu yok).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary(List<Long> viewTeamIds, int topN) {
        Map<String, Object> body = build(viewTeamIds);
        Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) body.get("summary"));
        s.put("generated_at", body.get("generated_at"));
        s.put("data_as_of", body.get("data_as_of"));
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) body.get("rows")) {
            if (top.size() >= Math.max(0, topN)) break;
            Map<String, Object> p = (Map<String, Object>) r.get("priority");
            if ("DONE".equals(p.get("band"))) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            for (String k : List.of("rank", "domain", "source", "team_id", "team_name", "tier", "key_bucket", "category",
                    "pqc", "days_remaining", "migrate_by")) t.put(k, r.get(k));
            t.put("score", p.get("score"));
            t.put("band", p.get("band"));
            top.add(t);
        }
        s.put("top", top);
        return s;
    }

    // ── Hesap ────────────────────────────────────────────────────────────────────────────

    Map<String, Object> buildUncached(List<Long> viewTeamIds, Instant now) {
        List<CertificateInventory> active = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        if (viewTeamIds != null) {
            Set<Long> scope = new HashSet<>(viewTeamIds);
            // Sahipsiz kayıt kapsamlı kullanıcıya GÖRÜNMEZ (Zayıf Algoritma raporuyla aynı süzgeç)
            active = active.stream()
                    .filter(i -> (i.getTeamId() != null && scope.contains(i.getTeamId()))
                              || (i.getUgTeamId() != null && scope.contains(i.getUgTeamId())))
                    .toList();
        }
        Map<String, CertificateInventory> invByDomain = new LinkedHashMap<>();
        for (CertificateInventory i : active) if (i.getDomain() != null) invByDomain.putIfAbsent(i.getDomain(), i);

        Map<String, LatestCheck> lcByDomain = latestChecks(invByDomain.keySet(), viewTeamIds == null);
        Map<Long, ManualCertificateVersion> versions = currentVersions(invByDomain.values());
        Map<Long, Team> teams = new HashMap<>();
        for (Team t : teamRepo.findAll()) if (t.getId() != null) teams.putIfAbsent(t.getId(), t);
        Map<String, WeakAlgorithmException> exceptions = exceptions();
        String today = LocalDate.now(ORG_ZONE).toString();

        List<Map<String, Object>> rows = new ArrayList<>(invByDomain.size());
        int[] remnants = new int[6];   // md5Leaf, sha1Leaf, md5Int, sha1Int, sha1Root, chainsExamined
        String asOf = null, oldest = null;
        for (CertificateInventory inv : invByDomain.values()) {
            LatestCheck lc = lcByDomain.get(inv.getDomain());
            ManualCertificateVersion v = inv.isManual() && inv.getId() != null ? versions.get(inv.getId()) : null;
            Map<String, Object> row = row(inv, lc, v, teams, exceptions.get(inv.getDomain()), today, now, remnants);
            String at = (String) row.get("checked_at");
            if (at != null) {
                if (asOf == null || at.compareTo(asOf) > 0) asOf = at;
                if (oldest == null || at.compareTo(oldest) < 0) oldest = at;
            }
            rows.add(row);
        }
        rows.sort(PRIORITY_ORDER);
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generated_at", ISO.format(now));
        body.put("data_as_of", asOf);
        body.put("oldest_check", oldest);
        body.put("scope", scopeBlock(viewTeamIds, teams));
        body.put("summary", summaryBlock(rows, remnants));
        body.put("algorithms", algorithmBlock(rows));
        body.put("signatures", signatureBlock(rows));
        body.put("signature_algorithms", signatureLabels(rows));
        Map<String, Object> teamBlock = teamBlock(rows);
        body.put("teams", teamBlock.get("rows"));
        body.put("unowned", teamBlock.get("unowned"));
        body.put("rows", rows);
        body.put("rule", PqcMigrationPriority.ruleTable());
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("rsa_2030_min_bits", CryptoClassifier.RSA_2030_MIN_BITS);
        thresholds.put("sunset", RSA_2048_SUNSET.toString());
        // TLS anahtar değişimi grubu (ör. X25519MLKEM768) saklanmıyor → hibrit anahtar değişimi gözlenemez
        thresholds.put("kex_observable", false);
        body.put("thresholds", thresholds);
        return body;
    }

    /** Tek satır — sınıflandırma + öncelik. {@code remnants} sayaçları burada artar (tek geçiş). */
    Map<String, Object> row(CertificateInventory inv, LatestCheck lc, ManualCertificateVersion v, Map<Long, Team> teams,
                            WeakAlgorithmException ex, String today, Instant now, int[] remnants) {
        boolean manual = inv.isManual();
        String keyAlg = null, sigAlg = null, notAfter = null, dataSource = "NONE";
        Integer keySize = null, days = null;
        if (v != null && !isBlank(v.getKeyAlg())) {
            // Yüklenen sertifika: kayıtlı dosya esastır
            keyAlg = v.getKeyAlg(); keySize = v.getKeySize(); sigAlg = v.getSignatureAlgorithm(); notAfter = v.getNotAfter();
            dataSource = "UPLOAD";
        } else if (lc != null && !isBlank(lc.getPublicKeyAlgorithm())) {
            keyAlg = lc.getPublicKeyAlgorithm(); keySize = lc.getPublicKeySize(); sigAlg = lc.getSignatureAlgorithm();
            notAfter = lc.getNotAfter();
            dataSource = manual ? "UPLOAD" : "CHECK";
        } else if (lc != null) {
            notAfter = lc.getNotAfter();
        }
        days = daysUntil(notAfter, now);
        if (days == null && lc != null && Objects.equals(notAfter, lc.getNotAfter())) days = lc.getDaysRemaining();

        KeyBucket bucket = CryptoClassifier.keyBucket(keyAlg, keySize);
        SigHash leafSig = CryptoClassifier.sigHash(sigAlg);

        // Ara sertifikalar (kök hariç): chain_details JSON'u — ağ kontrolü ve manuel değerlendirme aynı biçimi yazar
        List<String> rowRemnants = new ArrayList<>();
        if (leafSig == SigHash.MD5) { rowRemnants.add("MD5_LEAF"); remnants[0]++; }
        if (leafSig == SigHash.SHA1) { rowRemnants.add("SHA1_LEAF"); remnants[1]++; }
        List<Map<String, Object>> weakIntermediates = new ArrayList<>();
        int intermediates = 0;
        boolean md5Int = false, sha1Int = false;
        List<JsonNode> chain = chain(lc);
        if (!chain.isEmpty()) remnants[5]++;
        for (JsonNode n : chain) {
            if (n.path("is_leaf").asBoolean(false) || n.path("position").asInt(-1) == 0) continue;
            SigHash h = CryptoClassifier.sigHash(n.path("signature_algorithm").asText(null));
            if (n.path("is_root").asBoolean(false)) {
                if (h == SigHash.SHA1) remnants[4]++;   // bilgi: güven çapası imzasıyla doğrulanmaz, risk değil
                continue;
            }
            intermediates++;
            if (h.weak()) {
                if (h == SigHash.MD5) md5Int = true; else sha1Int = true;
                if (weakIntermediates.size() < WEAK_INTERMEDIATE_LIMIT) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("subject", cn(n.path("subject").asText(null)));
                    w.put("signature_algorithm", n.path("signature_algorithm").asText(null));
                    weakIntermediates.add(w);
                }
            }
        }
        if (md5Int) { rowRemnants.add("MD5_INTERMEDIATE"); remnants[2]++; }
        if (sha1Int) { rowRemnants.add("SHA1_INTERMEDIATE"); remnants[3]++; }

        PqcStatus pqc = CryptoClassifier.pqcStatus(bucket, leafSig);
        Category category = CryptoClassifier.category(bucket, keySize, leafSig, md5Int || sha1Int);

        String tlsVersion = manual || lc == null ? null : lc.getTlsVersion();
        String cipher = manual || lc == null ? null : lc.getCipherSuite();
        Boolean pfs = null;
        if (!manual && lc != null) {
            CertificateHealthRules.Status st = CertificateHealthRules.pfsStatus(tlsVersion, cipher);
            pfs = st == CertificateHealthRules.Status.OK ? Boolean.TRUE : st == CertificateHealthRules.Status.FAIL ? Boolean.FALSE : null;
        }

        PqcMigrationPriority.Score score = PqcMigrationPriority.score(new PqcMigrationPriority.Input(
                inv.getTier(), category, days, !manual, inv.getInternalCert(), pfs));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("domain", inv.getDomain());
        row.put("port", manual ? null : inv.getPort());
        row.put("source", manual ? "MANUAL" : "NETWORK");
        if (manual) row.put("manual_version", v != null ? v.getVersion() : inv.getManualVersion());
        Long tid = inv.getTeamId();
        row.put("team_id", tid);
        row.put("team_name", teamName(teams, tid));
        row.put("ug_team_id", inv.getUgTeamId());
        row.put("ug_team_name", teamName(teams, inv.getUgTeamId()));
        row.put("group_name", blankToNull(inv.getGroupName()));
        row.put("owner", blankToNull(inv.getOwner()));
        row.put("tier", inv.getTier());
        row.put("internal", Boolean.TRUE.equals(inv.getInternalCert()));
        row.put("ssl_pinning", Boolean.TRUE.equals(inv.getSslPinning()));
        row.put("jks_keystore", Boolean.TRUE.equals(inv.getJksKeystore()));
        row.put("openshift", Boolean.TRUE.equals(inv.getOpenshift()));
        row.put("external_vendor", Boolean.TRUE.equals(inv.getExternalVendor()));
        row.put("key_algorithm", blankToNull(keyAlg));
        row.put("key_size", keySize != null && keySize > 0 ? keySize : null);
        row.put("key_bucket", bucket.name());
        row.put("key_family", bucket.family.name());
        row.put("signature_algorithm", blankToNull(sigAlg));
        row.put("sig_hash", leafSig.name());
        row.put("remnants", rowRemnants);
        row.put("weak_intermediates", weakIntermediates);
        row.put("intermediate_count", intermediates);
        row.put("tls_version", blankToNull(tlsVersion));
        row.put("cipher_suite", blankToNull(cipher));
        row.put("pfs", pfs);
        row.put("not_after", blankToNull(notAfter));
        row.put("days_remaining", days);
        row.put("checked_at", lc != null ? lc.getCheckedAt() : (v != null ? v.getUploadedAt() : null));
        row.put("status", lc != null ? lc.getStatus() : null);
        row.put("data_source", dataSource);
        row.put("pqc", pqc.name());
        row.put("category", category.name());
        String[] action = action(category, notAfter, today);
        row.put("action", action[0]);
        row.put("migrate_by", action[1]);
        row.put("priority", score.toMap());
        if (ex != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("until", ex.getUntil());
            e.put("expired", ex.getUntil() != null && ex.getUntil().compareTo(today) < 0);
            row.put("exception", e);
        }
        return row;
    }

    /**
     * Beklenen eylem + tarihi. {@code replace} bugün; {@code renew} sıradaki yenilemede (bitiş 2030 sonundan önce);
     * {@code reissue} bitiş 2030 sonunu aşıyor → o tarihten önce yeniden düzenle; {@code pqc_plan} sıradaki yenilemede
     * hibrit/PQC değerlendir; {@code collect} veri topla; {@code none} işlem yok.
     */
    static String[] action(Category c, String notAfter, String today) {
        LocalDate na = parseDate(notAfter);
        return switch (c) {
            case BROKEN -> new String[] {"replace", today};
            case LEGACY -> na == null ? new String[] {"renew", RSA_2048_SUNSET.toString()}
                    : na.isAfter(RSA_2048_SUNSET) ? new String[] {"reissue", RSA_2048_SUNSET.toString()}
                    : new String[] {"renew", na.toString()};
            case MODERN -> new String[] {"pqc_plan", na == null ? null : na.toString()};
            case PQC_READY -> new String[] {"none", null};
            case UNKNOWN -> new String[] {"collect", null};
        };
    }

    /** Öncelik sırası: puan ↓, kalan gün ↑ (bilinmeyen sonda), katman ↑ (katmansız sonda), alan adı ↑. */
    @SuppressWarnings("unchecked")
    static final Comparator<Map<String, Object>> PRIORITY_ORDER = (a, b) -> {
        int c = Integer.compare((int) ((Map<String, Object>) b.get("priority")).get("score"),
                                (int) ((Map<String, Object>) a.get("priority")).get("score"));
        if (c != 0) return c;
        c = nullsLast((Integer) a.get("days_remaining"), (Integer) b.get("days_remaining"));
        if (c != 0) return c;
        c = nullsLast((Integer) a.get("tier"), (Integer) b.get("tier"));
        if (c != 0) return c;
        return String.valueOf(a.get("domain")).compareTo(String.valueOf(b.get("domain")));
    };

    private static int nullsLast(Integer a, Integer b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
        return Integer.compare(a, b);
    }

    // ── Bölümler ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> summaryBlock(List<Map<String, Object>> rows, int[] remnants) {
        Map<String, Integer> byPqc = zeroMap(PqcStatus.values());
        Map<String, Integer> byCat = zeroMap(Category.values());
        Map<String, Integer> byBand = new LinkedHashMap<>();
        for (String b : List.of("P1", "P2", "P3", "P4", "DONE")) byBand.put(b, 0);
        int checked = 0, manual = 0, expiring90 = 0, reissue = 0;
        for (Map<String, Object> r : rows) {
            byPqc.merge((String) r.get("pqc"), 1, Integer::sum);
            byCat.merge((String) r.get("category"), 1, Integer::sum);
            byBand.merge(band(r), 1, Integer::sum);
            if (!"NONE".equals(r.get("data_source"))) checked++;
            if ("MANUAL".equals(r.get("source"))) manual++;
            Integer d = (Integer) r.get("days_remaining");
            if ("VULNERABLE".equals(r.get("pqc")) && d != null && d <= 90) expiring90++;
            if ("reissue".equals(r.get("action"))) reissue++;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", rows.size());
        s.put("checked", checked);
        s.put("unchecked", rows.size() - checked);
        s.put("network", rows.size() - manual);
        s.put("manual", manual);
        s.put("by_pqc", byPqc);
        s.put("by_category", byCat);
        s.put("by_band", byBand);
        Map<String, Object> rem = new LinkedHashMap<>();
        rem.put("md5_leaf", remnants[0]);
        rem.put("sha1_leaf", remnants[1]);
        rem.put("md5_intermediate", remnants[2]);
        rem.put("sha1_intermediate", remnants[3]);
        rem.put("sha1_root", remnants[4]);
        rem.put("chains_examined", remnants[5]);
        rem.put("affected", (int) rows.stream().filter(r -> !((List<?>) r.get("remnants")).isEmpty()).count());
        s.put("remnants", rem);
        s.put("vulnerable_expiring_90d", expiring90);
        s.put("legacy_reissue", reissue);
        return s;
    }

    private static List<Map<String, Object>> algorithmBlock(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = zeroMap(KeyBucket.values());
        for (Map<String, Object> r : rows) counts.merge((String) r.get("key_bucket"), 1, Integer::sum);
        List<Map<String, Object>> out = new ArrayList<>();
        for (KeyBucket b : KeyBucket.values()) {
            int n = counts.get(b.name());
            if (n == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", b.name()); m.put("family", b.family.name()); m.put("count", n); m.put("share", share(n, rows.size()));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> signatureBlock(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = zeroMap(SigHash.values());
        for (Map<String, Object> r : rows) counts.merge((String) r.get("sig_hash"), 1, Integer::sum);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SigHash h : SigHash.values()) {
            int n = counts.get(h.name());
            if (n == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hash", h.name()); m.put("count", n); m.put("share", share(n, rows.size())); m.put("weak", h.weak());
            out.add(m);
        }
        return out;
    }

    /** Ham imza algoritması adları (ör. SHA256withRSA) — en sık {@link #SIG_LABEL_LIMIT}. */
    private static List<Map<String, Object>> signatureLabels(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String s = (String) r.get("signature_algorithm");
            if (s != null) counts.merge(s, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> { int c = b.getValue() - a.getValue(); return c != 0 ? c : a.getKey().compareTo(b.getKey()); })
                .limit(SIG_LABEL_LIMIT)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", e.getKey()); m.put("hash", CryptoClassifier.sigHash(e.getKey()).name()); m.put("count", e.getValue());
                    return m;
                }).toList();
    }

    /** Takım bazlı geçiş özeti — birincil (SY) takım; takımsız kayıtlar {@code unowned}. */
    private static Map<String, Object> teamBlock(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> byTeam = new LinkedHashMap<>();
        Map<String, Object> unowned = emptyTeam(null, null);
        for (Map<String, Object> r : rows) {   // satırlar zaten öncelik sırasında → ilk görülen en yüksek puan
            Long tid = (Long) r.get("team_id");
            Map<String, Object> t = tid == null ? unowned : byTeam.computeIfAbsent(tid, k -> emptyTeam(k, (String) r.get("team_name")));
            addToTeam(t, r);
        }
        List<Map<String, Object>> list = new ArrayList<>(byTeam.values());
        list.sort((a, b) -> {
            int c = Integer.compare((int) b.get("top_score"), (int) a.get("top_score"));
            if (c != 0) return c;
            c = Integer.compare(bandCount(b, "P1"), bandCount(a, "P1"));
            if (c != 0) return c;
            return String.valueOf(a.get("team_name")).compareToIgnoreCase(String.valueOf(b.get("team_name")));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", list);
        out.put("unowned", unowned);
        return out;
    }

    private static Map<String, Object> emptyTeam(Long id, String name) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("team_id", id);
        t.put("team_name", name);
        t.put("total", 0);
        t.put("vulnerable", 0);
        t.put("remnants", 0);
        t.put("by_category", zeroMap(Category.values()));
        Map<String, Integer> bands = new LinkedHashMap<>();
        for (String b : List.of("P1", "P2", "P3", "P4", "DONE")) bands.put(b, 0);
        t.put("by_band", bands);
        t.put("top_score", 0);
        t.put("next_migrate_by", null);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static void addToTeam(Map<String, Object> t, Map<String, Object> r) {
        t.merge("total", 1, (a, b) -> (int) a + (int) b);
        if ("VULNERABLE".equals(r.get("pqc"))) t.merge("vulnerable", 1, (a, b) -> (int) a + (int) b);
        if (!((List<?>) r.get("remnants")).isEmpty()) t.merge("remnants", 1, (a, b) -> (int) a + (int) b);
        ((Map<String, Integer>) t.get("by_category")).merge((String) r.get("category"), 1, Integer::sum);
        ((Map<String, Integer>) t.get("by_band")).merge(band(r), 1, Integer::sum);
        int score = (int) ((Map<String, Object>) r.get("priority")).get("score");
        if (score > (int) t.get("top_score")) t.put("top_score", score);
        String by = (String) r.get("migrate_by");
        String cur = (String) t.get("next_migrate_by");
        if (by != null && !"none".equals(r.get("action")) && (cur == null || by.compareTo(cur) < 0)) t.put("next_migrate_by", by);
    }

    @SuppressWarnings("unchecked")
    private static int bandCount(Map<String, Object> t, String band) {
        return ((Map<String, Integer>) t.get("by_band")).getOrDefault(band, 0);
    }

    @SuppressWarnings("unchecked")
    private static String band(Map<String, Object> r) {
        return (String) ((Map<String, Object>) r.get("priority")).get("band");
    }

    private static Map<String, Object> scopeBlock(List<Long> viewTeamIds, Map<Long, Team> teams) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("all", viewTeamIds == null);
        if (viewTeamIds != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Long id : new TreeSet<>(viewTeamIds.stream().filter(Objects::nonNull).toList())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", teamName(teams, id));
                list.add(m);
            }
            s.put("teams", list);
        }
        return s;
    }

    // ── Veri yükleme (sabit sayıda sorgu) ────────────────────────────────────────────────

    private Map<String, LatestCheck> latestChecks(Set<String> domains, boolean all) {
        Map<String, LatestCheck> out = new HashMap<>();
        if (domains.isEmpty()) return out;
        if (all) {
            for (LatestCheck lc : latestCheckRepo.findAll())
                if (lc.getDomain() != null && domains.contains(lc.getDomain())) out.put(lc.getDomain(), lc);
            return out;
        }
        List<String> list = new ArrayList<>(domains);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (LatestCheck lc : latestCheckRepo.findByDomainIn(list.subList(i, Math.min(list.size(), i + IN_CHUNK))))
                if (lc.getDomain() != null) out.put(lc.getDomain(), lc);
        }
        return out;
    }

    /** Manuel kayıtların GEÇERLİ sürümleri — kayıt başına sorgu yok (500'lük parçalar). */
    private Map<Long, ManualCertificateVersion> currentVersions(Collection<CertificateInventory> invs) {
        List<Long> ids = invs.stream().filter(CertificateInventory::isManual).map(CertificateInventory::getId)
                .filter(Objects::nonNull).toList();
        Map<Long, ManualCertificateVersion> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try {
            for (int i = 0; i < ids.size(); i += IN_CHUNK) {
                for (ManualCertificateVersion v : manualVersionRepo.findByInventoryIdInAndCurrentTrue(
                        ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))) {
                    ManualCertificateVersion prev = out.get(v.getInventoryId());
                    if (prev == null || (v.getVersion() != null && (prev.getVersion() == null || v.getVersion() > prev.getVersion())))
                        out.put(v.getInventoryId(), v);
                }
            }
        } catch (Exception e) {
            // Sürüm tablosu okunamazsa manuel satırlar son değerlendirme sonucuna (latest_checks) düşer
            log.warn("Kripto envanteri: manuel sertifika sürümleri okunamadı (son kontrol verisi kullanılıyor): {}", e.toString());
        }
        return out;
    }

    private Map<String, WeakAlgorithmException> exceptions() {
        Map<String, WeakAlgorithmException> out = new HashMap<>();
        try {
            for (WeakAlgorithmException e : exceptionRepo.findAll()) if (e.getDomain() != null) out.putIfAbsent(e.getDomain(), e);
        } catch (Exception e) {
            log.warn("Kripto envanteri: zayıf algoritma istisnaları okunamadı (istisnasız döner): {}", e.toString());
        }
        return out;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────

    private static List<JsonNode> chain(LatestCheck lc) {
        if (lc == null || isBlank(lc.getChainDetails())) return List.of();
        try {
            JsonNode n = JSON.readTree(lc.getChainDetails());
            if (n == null || !n.isArray()) return List.of();
            List<JsonNode> out = new ArrayList<>(n.size());
            n.forEach(out::add);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** DN'den CN (yoksa DN'nin kendisi, 200 karakterle sınırlı). */
    static String cn(String dn) {
        if (isBlank(dn)) return null;
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3).trim();
        }
        return dn.length() > 200 ? dn.substring(0, 200) : dn;
    }

    static Integer daysUntil(String notAfter, Instant now) {
        Instant at = parseInstant(notAfter);
        if (at == null) return null;
        return (int) Math.floorDiv(at.toEpochMilli() - now.toEpochMilli(), 86_400_000L);
    }

    private static Instant parseInstant(String iso) {
        if (isBlank(iso)) return null;
        String s = iso.trim();
        try { return Instant.parse(s.endsWith("Z") ? s : s + "Z"); } catch (Exception ignored) { /* sıradaki biçim */ }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { /* sıradaki biçim */ }
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay().toInstant(ZoneOffset.UTC); }
        catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(String iso) {
        if (isBlank(iso)) return null;
        try { return LocalDate.parse(iso.trim().substring(0, Math.min(10, iso.trim().length()))); }
        catch (Exception e) { return null; }
    }

    private static String teamName(Map<Long, Team> teams, Long id) {
        if (id == null) return null;
        Team t = teams.get(id);
        return t == null ? "#" + id : t.getName();
    }

    private static <E extends Enum<E>> Map<String, Integer> zeroMap(E[] values) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (E e : values) m.put(e.name(), 0);
        return m;
    }

    private static double share(int n, int total) {
        return total == 0 ? 0.0 : Math.round(1000.0 * n / total) / 10.0;
    }

    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
