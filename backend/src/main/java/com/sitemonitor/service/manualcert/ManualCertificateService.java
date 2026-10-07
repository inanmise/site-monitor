package com.sitemonitor.service.manualcert;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.CertificateFacts;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.manualcert.CertificateFileParser.Warning;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Elle yüklenen sertifikaların SÜRÜM işleri + okuma görünümleri (2026-10-06).
 *
 * <p>Sürüm satırı yalnız AÇIK veriyi taşır (zincir PEM, künye). Yenileme: yeni sürüm geçerli olur, önceki
 * {@code current=false} + {@code superseded_*} ile KALIR; tek işlemde, önce eskisi düşürülüp kalıcılaştırılır
 * (PostgreSQL'deki "kayıt başına tek geçerli sürüm" kısmi tekil indeksi ekleme sırasında ihlal edilmesin).
 */
@Slf4j
@Service
public class ManualCertificateService {

    public static final int NOTE_MAX = 500;

    private final ManualCertificateVersionRepository versionRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final CertificateService certService;
    private final ObjectMapper objectMapper;

    public ManualCertificateService(ManualCertificateVersionRepository versionRepo, LatestCheckRepository latestCheckRepo,
                                    CertificateService certService, ObjectMapper objectMapper) {
        this.versionRepo = versionRepo;
        this.latestCheckRepo = latestCheckRepo;
        this.certService = certService;
        this.objectMapper = objectMapper;
    }

    /** Yenilemenin reddi — kodlu (409) ya da doğrulama (400). */
    public static class RenewRejected extends RuntimeException {
        private final String code;
        private final Map<String, Object> extra;
        public RenewRejected(String code, String message, Map<String, Object> extra) {
            super(message);
            this.code = code;
            this.extra = extra;
        }
        public String code() { return code; }
        public Map<String, Object> extra() { return extra; }
    }

    /**
     * Yenileme sonucu. {@code sameCertificate}: güncel sürümle AYNI sertifika "yine de yükle" ile yeni sürüm olarak
     * kaydedildi (2026-10-07) — bitiş tarihi değişmedi.
     */
    public record RenewOutcome(ManualCertificateVersion created, ManualCertificateVersion previous, List<Warning> warnings,
                               boolean sameCertificate) {
        public RenewOutcome(ManualCertificateVersion created, ManualCertificateVersion previous, List<Warning> warnings) {
            this(created, previous, warnings, false);
        }
    }

    // ── Sürüm kurma ──────────────────────────────────────────────────────────

    /** Analiz girdisinden sürüm satırı (kalıcılaştırılmamış). Parola/özel anahtar bu nesneye HİÇ ulaşmaz. */
    public ManualCertificateVersion buildVersion(Long inventoryId, int version, ManualCertificateAnalyzer.Entry entry,
                                                 String fileName, String fileFormat, String username,
                                                 String displayName, String note) {
        X509Certificate c = entry.cert;
        List<X509Certificate> full = new ArrayList<>(entry.chain.size() + 1);
        full.add(c);
        full.addAll(entry.chain);
        ManualCertificateVersion v = new ManualCertificateVersion();
        v.setInventoryId(inventoryId);
        v.setVersion(version);
        v.setCurrent(true);
        v.setFingerprint(entry.ref);
        v.setSubject(entry.cn());
        v.setSubjectDn(c.getSubjectX500Principal().getName());
        v.setIssuer(entry.issuerName());
        v.setIssuerDn(c.getIssuerX500Principal().getName());
        v.setSerialNumber(CertificateFacts.serialHex(c));
        v.setNotBefore(CertificateFacts.iso(c.getNotBefore().toInstant()));
        v.setNotAfter(CertificateFacts.iso(c.getNotAfter().toInstant()));
        v.setSan(toJson(CertificateFacts.extractSan(c)));
        v.setKeyAlg(c.getPublicKey().getAlgorithm());
        int ks = CertificateFacts.publicKeySize(c.getPublicKey());
        v.setKeySize(ks > 0 ? ks : null);
        v.setSignatureAlgorithm(c.getSigAlgName());
        v.setPublicKeySha256(ManualCertificateChains.publicKeySha256(c));
        v.setChainPem(CertificateFileParser.toPem(full));
        v.setChainCount(full.size());
        v.setFileName(CertificateFileParser.sanitizeFileName(fileName));
        v.setFileFormat(fileFormat);
        v.setSourceAlias(entry.alias == null ? null : truncate(entry.alias, 255));
        v.setUploadedBy(truncate(username, 100));
        v.setUploadedByName(displayName);
        v.setUploadedAt(CertificateFacts.iso(Instant.now()));
        v.setNote(cleanNote(note));
        return v;
    }

    /** İlk sürüm (oluşturma) — çağıranın işleminde kalıcılaştırılır. */
    public ManualCertificateVersion saveFirstVersion(ManualCertificateVersion v) {
        v.setVersion(1);
        v.setCurrent(true);
        return versionRepo.save(v);
    }

    /**
     * Yeni sürüm. Aynı parmak izi → {@code SAME_CERTIFICATE}; bitiş geçerli sürümden önce/aynı ve onay yok →
     * {@code OLDER_THAN_CURRENT}. Önceki sürüm silinmez.
     */
    @Transactional
    public RenewOutcome renew(CertificateInventory inv, ManualCertificateAnalyzer.Entry entry, String fileName,
                              String fileFormat, String username, String displayName, String note, boolean confirm) {
        return renew(inv, entry, fileName, fileFormat, username, displayName, note, confirm, false);
    }

    /**
     * @param allowSame "yine de yükle" (2026-10-07, kullanıcı isteği): güncel sürümle AYNI sertifika reddedilmez, yeni sürüm
     *                  olarak kaydedilir (yeni dosya adı / biçim / not / yükleyen / zaman; önceki sürüm her zamanki gibi
     *                  düşürülür). Bitiş aynı olduğundan {@code OLDER_THAN_CURRENT} AYNI sertifikada sorulmaz — ama FARKLI
     *                  bir sertifikanın eski bitişini asla geçmez (orada yine {@code confirm} gerekir).
     */
    @Transactional
    public RenewOutcome renew(CertificateInventory inv, ManualCertificateAnalyzer.Entry entry, String fileName,
                              String fileFormat, String username, String displayName, String note, boolean confirm,
                              boolean allowSame) {
        ManualCertificateVersion current = versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(inv.getId())
                .orElse(null);
        boolean same = current != null && entry.ref.equalsIgnoreCase(current.getFingerprint());
        if (same && !allowSame) {
            throw new RenewRejected("SAME_CERTIFICATE", com.sitemonitor.util.Msg.t(
                    "Yüklenen sertifika zaten güncel sürüm.", "The uploaded certificate is already the current version."), null);
        }
        String newNotAfter = CertificateFacts.iso(entry.cert.getNotAfter().toInstant());
        if (!same && current != null && !confirm && current.getNotAfter() != null && newNotAfter.compareTo(current.getNotAfter()) <= 0) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("current_date", current.getNotAfter());
            extra.put("new_date", newNotAfter);
            extra.put("warnings", List.of(new Warning("OLDER_THAN_CURRENT", CertificateFileParser.SEV_WARN,
                    Map.of("current_date", current.getNotAfter(), "new_date", newNotAfter)).toJson()));
            throw new RenewRejected("OLDER_THAN_CURRENT", com.sitemonitor.util.Msg.t(
                    "Yüklenen sertifika güncel sürümden önce ya da aynı gün bitiyor — yine de yüklemek için onaylayın.",
                    "The uploaded certificate expires before or on the same day as the current version — confirm to upload it anyway."),
                    extra);
        }
        Integer max = versionRepo.findMaxVersion(inv.getId());
        int next = (max == null ? 0 : max) + 1;
        String now = CertificateFacts.iso(Instant.now());
        if (current != null) {
            current.setCurrent(false);
            current.setSupersededAt(now);
            current.setSupersededBy(truncate(username, 100));
            versionRepo.saveAndFlush(current);   // önce düşür: kısmi tekil indeks (tek geçerli sürüm) eklemeyi reddetmesin
        }
        ManualCertificateVersion created = buildVersion(inv.getId(), next, entry, fileName, fileFormat, username, displayName, note);
        created = versionRepo.save(created);
        return new RenewOutcome(created, current, renewWarnings(current, created), same);
    }

    // ── Eski sürümü kalıcı silme (2026-10-07, kullanıcı isteği) ──────────────

    /** Sürüm silmenin reddi — kodlu (404 / 409). */
    public static class VersionDeleteRejected extends RuntimeException {
        private final String code;
        private final int status;
        public VersionDeleteRejected(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }
        public String code() { return code; }
        public int status() { return status; }
    }

    /**
     * GÜNCEL OLMAYAN bir sürümü kalıcı siler. Kalan sürümlerin numaraları DEĞİŞMEZ; fark ({@code key_changed},
     * {@code san_*}) okunurken her sürüm kalan bir sonraki ESKİ sürümle karşılaştırılır ({@link #detail}). Değerlendirme
     * geçmişi ({@code certificate_checks}) silinmez. Koşullu silme: sürüm bu arada güncel olduysa satır silinmez.
     *
     * @return silinen sürüm
     * @throws VersionDeleteRejected {@code NOT_FOUND} (404: sürüm bu kaydın değil) / {@code CURRENT_VERSION} (409)
     */
    @Transactional
    public ManualCertificateVersion deleteVersion(CertificateInventory inv, Long versionId) {
        ManualCertificateVersion v = versionId == null ? null : versionRepo.findById(versionId).orElse(null);
        if (v == null || inv == null || !inv.getId().equals(v.getInventoryId())) {
            throw new VersionDeleteRejected("NOT_FOUND", 404, com.sitemonitor.util.Msg.t(
                    "Sertifika sürümü bulunamadı.", "Certificate version not found."));
        }
        if (Boolean.TRUE.equals(v.getCurrent())) throw currentVersionRejected();
        long n = versionRepo.deleteByIdAndInventoryIdAndCurrentFalse(v.getId(), inv.getId());
        if (n == 0) {
            // Yarış: bu arada silinmiş ya da güncel olmuş
            ManualCertificateVersion again = versionRepo.findById(v.getId()).orElse(null);
            if (again != null && Boolean.TRUE.equals(again.getCurrent())) throw currentVersionRejected();
            throw new VersionDeleteRejected("NOT_FOUND", 404, com.sitemonitor.util.Msg.t(
                    "Sertifika sürümü bulunamadı.", "Certificate version not found."));
        }
        return v;
    }

    private static VersionDeleteRejected currentVersionRejected() {
        return new VersionDeleteRejected("CURRENT_VERSION", 409, com.sitemonitor.util.Msg.t(
                "Güncel sürüm silinemez — takip bu sürümle sürüyor. Takibi bırakmak için kaydı silin.",
                "The current version can't be deleted — tracking runs on it. Delete the record to stop tracking."));
    }

    /** Kaydın kalan sürüm sayısı. */
    public long versionCount(Long inventoryId) {
        long n = 0;
        for (Object[] r : versionRepo.countByInventoryIds(List.of(inventoryId))) {
            if (r != null && r.length >= 2 && r[1] instanceof Number c) n += c.longValue();
        }
        return n;
    }

    /** Yenileme farkları: anahtar değişti/aynı, konu değişti, SAN değişti. */
    public List<Warning> renewWarnings(ManualCertificateVersion previous, ManualCertificateVersion created) {
        List<Warning> out = new ArrayList<>();
        if (previous == null || created == null) return out;
        if (previous.getPublicKeySha256() != null && created.getPublicKeySha256() != null) {
            boolean same = previous.getPublicKeySha256().equalsIgnoreCase(created.getPublicKeySha256());
            out.add(new Warning(same ? "KEY_SAME" : "KEY_CHANGED", CertificateFileParser.SEV_INFO, Map.of()));
        }
        if (previous.getSubjectDn() != null && created.getSubjectDn() != null
                && !previous.getSubjectDn().equals(created.getSubjectDn())) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("old", previous.getSubjectDn());
            p.put("new", created.getSubjectDn());
            out.add(new Warning("SUBJECT_CHANGED", CertificateFileParser.SEV_WARN, p));
        }
        List<String>[] diff = sanDiff(previous, created);
        if (!diff[0].isEmpty() || !diff[1].isEmpty()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("added", String.join(", ", diff[0]));
            p.put("removed", String.join(", ", diff[1]));
            out.add(new Warning("SAN_CHANGED", CertificateFileParser.SEV_INFO, p));
        }
        return out;
    }

    /**
     * Yenileme kullanıcının açık eylemidir — sertifika değişimi "planlı mıydı?" diye ayrıca sorulmaz: sabitlenen parmak
     * izi yeni sürümse onay kaydı yazılır (sağlık listesinde "planlı yenileme onaylandı").
     */
    public void acknowledgeRenewal(String domain, String fingerprint, String username) {
        if (domain == null || fingerprint == null) return;
        try {
            LatestCheck lc = latestCheckRepo.findById(domain).orElse(null);
            if (lc == null || lc.getPinnedFingerprint() == null || !fingerprint.equalsIgnoreCase(lc.getPinnedFingerprint())
                    || lc.getFingerprintChangedAt() == null) return;
            lc.setFingerprintAckAt(CertificateFacts.iso(Instant.now()));
            lc.setFingerprintAckBy(truncate(username, 100));
            lc.setFingerprintAckFingerprint(lc.getPinnedFingerprint());
            latestCheckRepo.save(lc);
        } catch (Exception e) {
            log.debug("Manuel yenileme onayı yazılamadı ({}): {}", domain, e.toString());
        }
    }

    // ── Okuma görünümleri ────────────────────────────────────────────────────

    /**
     * Liste satırları — sürüm/sayaç/son kontrol TOPLU okunur (satır başına sorgu yok).
     *
     * @param canManage satırın takımına yazabilir mi ({@code SessionScope.inventoryWriteTest})
     * @param teamNames takım adı haritası
     */
    public List<Map<String, Object>> listRows(List<CertificateInventory> rows, Predicate<Long> canManage,
                                              Map<Long, String> teamNames) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(CertificateInventory::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, ManualCertificateVersion> current = new HashMap<>();
        for (ManualCertificateVersion v : versionRepo.findByInventoryIdInAndCurrentTrue(ids)) {
            current.merge(v.getInventoryId(), v, (a, b) -> a.getVersion() >= b.getVersion() ? a : b);
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] r : versionRepo.countByInventoryIds(ids)) {
            if (r != null && r.length >= 2 && r[0] instanceof Number id && r[1] instanceof Number n) {
                counts.put(id.longValue(), n.longValue());
            }
        }
        Set<String> domains = new LinkedHashSet<>();
        for (CertificateInventory inv : rows) if (inv.getDomain() != null) domains.add(inv.getDomain());
        Map<String, LatestCheck> latest = new HashMap<>();
        for (LatestCheck lc : latestCheckRepo.findAllById(domains)) if (lc.getDomain() != null) latest.put(lc.getDomain(), lc);
        Map<String, String> alertLevels = alertLevels();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (CertificateInventory inv : rows) {
            Map<String, Object> m = summary(inv, latest.get(inv.getDomain()), alertLevels.get(inv.getDomain()), teamNames);
            ManualCertificateVersion v = current.get(inv.getId());
            m.put("current_version", v == null ? null : currentVersionSummary(v));
            m.put("versions_count", counts.getOrDefault(inv.getId(), 0L));
            m.put("can_manage", canManage.test(inv.getTeamId()));
            out.add(m);
        }
        return out;
    }

    /** Tekil görünüm: özet + tüm sürümler (en yeni üstte) ve önceki sürüme göre fark. */
    public Map<String, Object> detail(CertificateInventory inv, boolean canManage, Map<Long, String> teamNames) {
        LatestCheck lc = inv.getDomain() == null ? null : latestCheckRepo.findById(inv.getDomain()).orElse(null);
        Map<String, Object> m = summary(inv, lc, alertLevels().get(inv.getDomain()), teamNames);
        List<ManualCertificateVersion> versions = versionRepo.findByInventoryIdOrderByVersionDesc(inv.getId());
        ManualCertificateVersion current = versions.stream().filter(v -> Boolean.TRUE.equals(v.getCurrent())).findFirst().orElse(null);
        m.put("current_version", current == null ? null : currentVersionSummary(current));
        m.put("versions_count", (long) versions.size());
        m.put("can_manage", canManage);
        List<Map<String, Object>> list = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            ManualCertificateVersion prev = i + 1 < versions.size() ? versions.get(i + 1) : null;
            list.add(versionDto(versions.get(i), prev));
        }
        m.put("versions", list);
        return m;
    }

    private Map<String, Object> summary(CertificateInventory inv, LatestCheck lc, String alertLevel, Map<Long, String> teamNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inventory_id", inv.getId());
        m.put("domain", inv.getDomain());
        m.put("cert_source", CertificateInventory.SOURCE_MANUAL);
        m.put("team_id", inv.getTeamId());
        m.put("team_name", inv.getTeamId() == null ? null : teamNames.get(inv.getTeamId()));
        m.put("ug_team_id", inv.getUgTeamId());
        m.put("tier", inv.getTier());
        m.put("group_name", inv.getGroupName());
        m.put("tags", inv.getTags());
        m.put("active", inv.getActive());
        m.put("status", lc == null ? null : lc.getStatus());
        m.put("alert_level", alertLevel);
        m.put("days_remaining", lc == null ? null : lc.getDaysRemaining());
        m.put("not_after", lc == null ? null : lc.getNotAfter());
        m.put("issuer", lc == null ? null : (lc.getIssuerCn() != null ? lc.getIssuerCn() : lc.getIssuer()));
        m.put("subject", lc == null ? null : lc.getSubject());
        m.put("checked_at", lc == null ? null : lc.getCheckedAt());
        return m;
    }

    private static Map<String, Object> currentVersionSummary(ManualCertificateVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());   // liste satırından doğrudan PEM indirme (ayrıntıyı çekmeden)
        m.put("version", v.getVersion());
        m.put("fingerprint", v.getFingerprint());
        m.put("uploaded_at", v.getUploadedAt());
        m.put("uploaded_by_name", v.getUploadedByName() != null ? v.getUploadedByName() : v.getUploadedBy());
        m.put("file_name", v.getFileName());
        m.put("file_format", v.getFileFormat());
        return m;
    }

    /** Sürüm görünümü (sözleşme VersionDto) + önceki sürüme göre fark. */
    public Map<String, Object> versionDto(ManualCertificateVersion v, ManualCertificateVersion previous) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("version", v.getVersion());
        m.put("current", Boolean.TRUE.equals(v.getCurrent()));
        m.put("fingerprint", v.getFingerprint());
        m.put("subject", v.getSubject());
        m.put("subject_dn", v.getSubjectDn());
        m.put("issuer", v.getIssuer());
        m.put("issuer_dn", v.getIssuerDn());
        m.put("serial_number", v.getSerialNumber());
        m.put("not_before", v.getNotBefore());
        m.put("not_after", v.getNotAfter());
        m.put("san", sanList(v));
        m.put("key_alg", v.getKeyAlg());
        m.put("key_size", v.getKeySize());
        m.put("signature_algorithm", v.getSignatureAlgorithm());
        m.put("chain_count", v.getChainCount());
        m.put("file_name", v.getFileName());
        m.put("file_format", v.getFileFormat());
        m.put("source_alias", v.getSourceAlias());
        m.put("uploaded_by_name", v.getUploadedByName() != null ? v.getUploadedByName() : v.getUploadedBy());
        m.put("uploaded_at", v.getUploadedAt());
        m.put("superseded_at", v.getSupersededAt());
        m.put("superseded_by", v.getSupersededBy());
        m.put("note", v.getNote());
        // "Yine de yükle" ile aynı sertifika yeniden yüklendi (2026-10-07): önceki (kalan) sürümle aynı parmak izi
        m.put("same_as_previous", previous != null && v.getFingerprint() != null
                && v.getFingerprint().equalsIgnoreCase(previous.getFingerprint()));
        if (previous == null) {
            m.put("key_changed", null);
            m.put("san_added", List.of());
            m.put("san_removed", List.of());
        } else {
            m.put("key_changed", previous.getPublicKeySha256() == null || v.getPublicKeySha256() == null ? null
                    : !previous.getPublicKeySha256().equalsIgnoreCase(v.getPublicKeySha256()));
            List<String>[] d = sanDiff(previous, v);
            m.put("san_added", d[0]);
            m.put("san_removed", d[1]);
        }
        return m;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** [eklenen, çıkarılan] — harf duyarsız, sıra korunur. */
    @SuppressWarnings("unchecked")
    List<String>[] sanDiff(ManualCertificateVersion previous, ManualCertificateVersion current) {
        List<String> before = sanList(previous);
        List<String> after = sanList(current);
        Set<String> b = lower(before);
        Set<String> a = lower(after);
        List<String> added = new ArrayList<>();
        for (String s : after) if (!b.contains(s.toLowerCase(Locale.ROOT))) added.add(s);
        List<String> removed = new ArrayList<>();
        for (String s : before) if (!a.contains(s.toLowerCase(Locale.ROOT))) removed.add(s);
        return new List[] { added, removed };
    }

    private static Set<String> lower(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) if (s != null) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    public List<String> sanList(ManualCertificateVersion v) {
        if (v == null || v.getSan() == null || v.getSan().isBlank()) return List.of();
        try {
            List<String> l = objectMapper.readValue(v.getSan(), new TypeReference<List<String>>() { });
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Alan adı → alarm seviyesi (Genel Bakış kartıyla AYNI hüküm; önbellekli liste). */
    private Map<String, String> alertLevels() {
        Map<String, String> out = new HashMap<>();
        try {
            for (CertificateDto d : certService.getAllLatest()) {
                if (d.getDomain() != null && d.getAlertLevel() != null) out.put(d.getDomain(), d.getAlertLevel());
            }
        } catch (Exception e) {
            log.debug("Alarm seviyeleri okunamadı: {}", e.toString());
        }
        return out;
    }

    static String cleanNote(String note) {
        if (note == null) return null;
        String n = note.strip();
        if (n.isEmpty()) return null;
        return n.length() > NOTE_MAX ? n.substring(0, NOTE_MAX) : n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
