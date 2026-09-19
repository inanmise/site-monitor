package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.service.CertificateHealthRules.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sertifika sağlık kontrol listesinin TEK değerlendirme çekirdeği.
 *
 * <p><b>Metin kurmaz, HÜKÜM verir.</b> Her satır bir i18n ANAHTARI döndürür; cümleyi arayüz kurar.
 * Böylece aynı çekirdek ileride haftalık rapora ve e-postaya da servis verebilir — orada Türkçe
 * cümle, burada karar. Backend'de cümle kurmak, aynı hükmü iki dilde iki kez yazmak demekti.
 *
 * <p><b>Frontend'de İKİNCİ bir sağlık mantığı yazılmaz.</b> Arayüz yalnız sunar: rozet rengi
 * {@code status}'tan, metin {@code *Key}'lerden gelir. Aksi halde iki taraf zamanla ayrışır ve
 * kullanıcı hangisine inanacağını bilemez.
 *
 * <p><b>UNKNOWN ayrı bir durumdur.</b> Kurumsal proxy arkasında OCSP/CRL erişilemez, eski
 * kayıtlarda cipher/protokol boştur, sayfa çekilemeyebilir. Bunları kırmızıya boyamak yanlış alarm
 * üretir; kullanıcı bir süre sonra kırmızıları görmezden gelmeye başlar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateHealthService {

    /** Satır grubu — liste uzun, başlıklarla okunur hâle gelir. */
    public static final String GROUP_CERTIFICATE = "certificate";
    public static final String GROUP_TRANSPORT = "transport";
    public static final String GROUP_APPLICATION = "application";

    /**
     * Kanonik satır anahtarları — SIRA da sözleşmenin parçası (ekran bu sırayı çizer).
     * Uç yanıtı bu listeyle karşılaştırılır: bilinmeyen bir anahtar üretilirse build kırılır.
     *
     * <p>Her anahtar HER değerlendirmede üretilir — satırın "uygulanamaz" hâli yoktur.
     */
    public static final List<String> ROW_KEYS = List.of(
            "expiry", "revocation", "chain", "trust", "signature", "keySize", "intermediate",
            "sanMatch", "pinnedFingerprint", "protocol", "cipher", "pfs", "hsts", "mixedContent");


    /** Sertifika değişimi bu kadar gün "yeni" sayılır; sonra satır kendiliğinden yeşile döner. */
    private static final int CHANGE_NOTICE_DAYS = 7;

    private final com.sitemonitor.repository.AlertThresholdRepository thresholdRepo;

    /**
     * Tek satırlık hüküm.
     *
     * @param key         kanonik satır anahtarı (i18n: {@code hlth.<key>.title/desc})
     * @param group       başlık grubu
     * @param status      OK / WARN / FAIL / UNKNOWN / NA
     * @param valueKey    değer rozeti metni ({@code hlth.val.<valueKey>})
     * @param valueArgs   rozet içine giren değerler (ör. kalan gün, TLS sürümü)
     * @param actionKey   aksiyon metni ({@code hlth.act.<actionKey>}); {@code none} = işlem gerekmez
     * @param actionArgs  aksiyon metnine giren değerler
     * @param evidence    satır açılınca gösterilen kanıt (OCSP URL, cipher adı, SAN listesi…)
     */
    public record HealthRow(String key, String group, Status status,
                            String valueKey, List<Object> valueArgs,
                            String actionKey, List<Object> actionArgs,
                            Map<String, Object> evidence) { }

    /** Değerlendirme sonucu — satırlar + özet sayaç (K7). */
    public record HealthResult(List<HealthRow> rows, int okCount, int evaluatedCount) {
        /** UNKNOWN ve NA sayıma girmez: "8/9 temiz" derken doğrulanamayanı başarısız gibi göstermeyiz. */
        public boolean allClear() { return evaluatedCount > 0 && okCount == evaluatedCount; }
    }

    public HealthResult evaluate(LatestCheck lc, CertificateInventory inv, boolean hasPageMonitor) {
        int[] d = thresholdDays();
        return evaluate(lc, inv, hasPageMonitor, d[0], d[1]);
    }

    /** Aktif alarm eşiği {uyarı, kritik} gün — toplu değerlendirmede (Sizin için — bugün) BİR kez okunur. */
    public int[] thresholdDays() {
        AlertThreshold th = safeThreshold();
        return new int[]{th.getWarningDays() != null ? th.getWarningDays() : 30,
                         th.getCriticalDays() != null ? th.getCriticalDays() : 7};
    }

    /** Eşikleri çağıranın verdiği değerlendirme — alan başına ek sorgu yok (2026-09-19). */
    public HealthResult evaluate(LatestCheck lc, CertificateInventory inv, boolean hasPageMonitor, int warn, int crit) {
        List<HealthRow> rows = new ArrayList<>();
        if (lc == null) {
            // Hiç kontrol edilmemiş domain: tek satırlık dürüst cevap, uydurma OK üretme.
            rows.add(new HealthRow("expiry", GROUP_CERTIFICATE, Status.UNKNOWN,
                    "unknown", List.of(), "runCheck", List.of(), Map.of()));
            return summarise(rows);
        }

        rows.add(expiryRow(lc, warn, crit));
        rows.add(revocationRow(lc));
        rows.add(chainRow(lc));
        rows.add(trustRow(lc));
        rows.add(signatureRow(lc));
        rows.add(keySizeRow(lc));
        rows.add(intermediateRow(lc, warn, crit));
        rows.add(sanMatchRow(lc));
        rows.add(certificateChangeRow(lc));
        rows.add(protocolRow(lc));
        rows.add(cipherRow(lc));
        rows.add(pfsRow(lc));
        rows.add(hstsRow(lc));
        rows.add(mixedContentRow(lc, hasPageMonitor));
        return summarise(rows);
    }

    private HealthResult summarise(List<HealthRow> rows) {
        int ok = 0, evaluated = 0;
        for (HealthRow r : rows) {
            if (r.status() == Status.UNKNOWN || r.status() == Status.NA) continue;
            evaluated++;
            if (r.status() == Status.OK) ok++;
        }
        return new HealthResult(List.copyOf(rows), ok, evaluated);
    }

    private AlertThreshold safeThreshold() {
        try {
            AlertThreshold t = thresholdRepo.findFirstByActiveTrue().orElse(null);
            if (t != null) return t;
        } catch (Exception e) {
            log.debug("Alarm eşiği okunamadı, varsayılana düşülüyor: {}", e.toString());
        }
        AlertThreshold fallback = new AlertThreshold();
        fallback.setWarningDays(30);
        fallback.setCriticalDays(7);
        return fallback;
    }

    // ── Sertifika grubu ─────────────────────────────────────────────────────

    private HealthRow expiryRow(LatestCheck lc, int warn, int crit) {
        Integer days = lc.getDaysRemaining();
        Status st = CertificateHealthRules.expiryStatus(days, warn, crit);
        Map<String, Object> ev = ev("not_before", lc.getNotBefore(), "not_after", lc.getNotAfter());

        // Aksiyon EŞİKLERLE konuşur: "17 gün kaldı" tek başına bir hüküm değil; uyarı eşiğinin
        // altındaysa yenileme planlanmalı, kritik eşikteyse iş acildir.
        if (st == Status.FAIL && days != null && days < 0) {
            return new HealthRow("expiry", GROUP_CERTIFICATE, st, "expired", List.of(Math.abs(days)),
                    "renewNow", List.of(), ev);
        }
        if (st == Status.FAIL) {
            return new HealthRow("expiry", GROUP_CERTIFICATE, st, "daysLeft", List.of(days),
                    "renewUrgent", List.of(days, crit), ev);
        }
        if (st == Status.WARN) {
            return new HealthRow("expiry", GROUP_CERTIFICATE, st, "daysLeft", List.of(days),
                    "renewPlan", List.of(days, warn), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("expiry", GROUP_CERTIFICATE, st, "unknown", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("expiry", GROUP_CERTIFICATE, st, "daysLeft", List.of(days), "none", List.of(), ev);
    }

    private HealthRow revocationRow(LatestCheck lc) {
        Status st = CertificateHealthRules.fromStatusLabel(lc.getRevocationStatus(), "VALID", "REVOKED");
        Map<String, Object> ev = ev("ocsp_url", lc.getOcspUrl(), "crl_url", lc.getCrlUrl(),
                "raw", lc.getRevocationStatus());
        if (st == Status.FAIL) {
            return new HealthRow("revocation", GROUP_CERTIFICATE, st, "revoked", List.of(), "replaceNow", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            // Kurumsal proxy arkasında OCSP/CRL sık sık erişilemez — bu bir sertifika sorunu DEĞİL.
            return new HealthRow("revocation", GROUP_CERTIFICATE, st, "unverified", List.of(),
                    "checkNetworkAccess", List.of(), ev);
        }
        return new HealthRow("revocation", GROUP_CERTIFICATE, st, "valid", List.of(), "none", List.of(), ev);
    }

    private HealthRow chainRow(LatestCheck lc) {
        Status st = CertificateHealthRules.fromStatusLabel(lc.getChainStatus(), "VALID", "BROKEN");
        Map<String, Object> ev = ev("chain_details", lc.getChainDetails(), "raw", lc.getChainStatus());
        if (st == Status.FAIL) {
            return new HealthRow("chain", GROUP_CERTIFICATE, st, "broken", List.of(), "fixChain", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("chain", GROUP_CERTIFICATE, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("chain", GROUP_CERTIFICATE, st, "valid", List.of(), "none", List.of(), ev);
    }

    private HealthRow trustRow(LatestCheck lc) {
        Status st = CertificateHealthRules.fromStatusLabel(lc.getTrustStatus(), "TRUSTED", "UNTRUSTED");
        Map<String, Object> ev = ev("raw", lc.getTrustStatus(), "issuer", lc.getIssuerCn());
        if (st == Status.FAIL) {
            return new HealthRow("trust", GROUP_CERTIFICATE, st, "untrusted", List.of(), "checkCa", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("trust", GROUP_CERTIFICATE, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("trust", GROUP_CERTIFICATE, st, "trusted", List.of(), "none", List.of(), ev);
    }

    private HealthRow signatureRow(LatestCheck lc) {
        Status st = CertificateHealthRules.signatureStatus(lc.getSignatureAlgorithm());
        Map<String, Object> ev = ev("algorithm", lc.getSignatureAlgorithm());
        if (st == Status.FAIL) {
            return new HealthRow("signature", GROUP_CERTIFICATE, st, "weakAlgorithm",
                    List.of(str(lc.getSignatureAlgorithm())), "reissueStrongerHash", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("signature", GROUP_CERTIFICATE, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("signature", GROUP_CERTIFICATE, st, "modernAlgorithm",
                List.of(str(lc.getSignatureAlgorithm())), "none", List.of(), ev);
    }

    private HealthRow keySizeRow(LatestCheck lc) {
        Status st = CertificateHealthRules.keySizeStatus(lc.getPublicKeyAlgorithm(), lc.getPublicKeySize());
        Map<String, Object> ev = ev("algorithm", lc.getPublicKeyAlgorithm(), "size", lc.getPublicKeySize());
        List<Object> args = List.of(str(lc.getPublicKeyAlgorithm()), lc.getPublicKeySize() == null ? 0 : lc.getPublicKeySize());
        if (st == Status.FAIL) {
            return new HealthRow("keySize", GROUP_CERTIFICATE, st, "shortKey", args, "reissueLongerKey", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("keySize", GROUP_CERTIFICATE, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("keySize", GROUP_CERTIFICATE, st, "strongKey", args, "none", List.of(), ev);
    }

    private HealthRow intermediateRow(LatestCheck lc, int warn, int crit) {
        Integer days = lc.getIntermediateDaysRemaining();
        Map<String, Object> ev = ev("expiry", lc.getIntermediateExpiry(), "days", days);
        if (days == null) {
            // Ara sertifika bilgisi her zincirde çıkmaz — yokluğu bir kusur değildir.
            return new HealthRow("intermediate", GROUP_CERTIFICATE, Status.UNKNOWN, "unverified",
                    List.of(), "runCheck", List.of(), ev);
        }
        Status st = CertificateHealthRules.expiryStatus(days, warn, crit);
        if (st == Status.FAIL) {
            return new HealthRow("intermediate", GROUP_CERTIFICATE, st, "daysLeft", List.of(days),
                    "contactCaIntermediate", List.of(days), ev);
        }
        if (st == Status.WARN) {
            return new HealthRow("intermediate", GROUP_CERTIFICATE, st, "daysLeft", List.of(days),
                    "watchIntermediate", List.of(days), ev);
        }
        return new HealthRow("intermediate", GROUP_CERTIFICATE, st, "daysLeft", List.of(days), "none", List.of(), ev);
    }

    /**
     * Alan adı sertifikanın SAN listesinde mi — GERÇEK kapsama kontrolü.
     *
     * <p>İlk sürüm bu satırı {@code deploymentStatus}'a bağlamıştı; o alan SAN kapsamasını DEĞİL,
     * beklenen parmak izi pinini anlatıyor (ayrı satır olarak aşağıda). Üstelik eşleme de yanlıştı
     * ("COMPLETE" bekleniyordu, üretilen değer "OK"), o yüzden sağlıklı her sertifika
     * "Doğrulanamadı" görünüyordu (2026-08-23 kullanıcı bildirimi).
     */
    private HealthRow sanMatchRow(LatestCheck lc) {
        List<String> san = parseSan(lc.getSan());
        Status st = CertificateHealthRules.sanCoverage(lc.getDomain(), san);
        Map<String, Object> ev = ev("domain", lc.getDomain(), "san", String.join(", ", san));
        if (st == Status.FAIL) {
            return new HealthRow("sanMatch", GROUP_CERTIFICATE, st, "incomplete", List.of(),
                    "fixDeployment", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("sanMatch", GROUP_CERTIFICATE, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("sanMatch", GROUP_CERTIFICATE, st, "complete", List.of(), "none", List.of(), ev);
    }

    /**
     * Sertifika sessizce değişti mi — OTOMATİK pin (TOFU) ile.
     *
     * <p><b>Kullanıcıdan aksiyon istemez.</b> İlk kontrolde parmak izi kendiliğinden sabitlenir;
     * sonraki her kontrolde sunulanla karşılaştırılır. Değişim görülünce yenisi hemen sabitlenir
     * ({@code CertificateService.applyAutoPin}) ve satır bunu bildirir. İlk sürüm elle
     * sabitlenmiş bir pin bekliyordu; hiç kimse pin girmediği için satır kalıcı olarak gri
     * kalıyordu (kullanıcı bildirimi 2026-08-23).
     *
     * <p><b>Değişim UYARIDIR, hata değil.</b> Sertifika yenilemesi normal ve beklenen bir olaydır;
     * 90 günde bir kırmızı yakmak kullanıcıyı kırmızıları görmezden gelmeye alıştırır. Satır
     * "doğrula: planlı yenileme miydi" der ve pencere geçince kendiliğinden yeşile döner.
     */
    private HealthRow certificateChangeRow(LatestCheck lc) {
        Map<String, Object> ev = ev("pinned", lc.getPinnedFingerprint(), "pinned_at", lc.getPinnedAt(),
                "previous", lc.getPreviousFingerprint(), "changed_at", lc.getFingerprintChangedAt(),
                "served", lc.getFingerprint());

        if (lc.getPinnedFingerprint() == null || lc.getPinnedFingerprint().isBlank()) {
            // Henüz hiç parmak izi görülmemiş (erişilemeyen domain) — hüküm verecek veri yok.
            return new HealthRow("pinnedFingerprint", GROUP_CERTIFICATE, Status.UNKNOWN, "unverified",
                    List.of(), "runCheck", List.of(), ev);
        }

        // Sunulan ≠ sabitlenen: pin, güven kapısı yüzünden KORUNMUŞ demektir
        // ({@code CertificateService.applyAutoPin}) — sunulan sertifika ya bu adı kapsamıyor ya da
        // güvenilmiyor. Kapı olmasaydı burası sessizce yeniden sabitlenir ve araya giren taraf
        // pini kendi lehine yazdırırdı; kapıyla birlikte durum GÖRÜNÜR olmalı, yoksa yalnızca
        // sessizleşmiş olurduk. Araya girme/DNS yönlendirme imzası olduğu için WARN değil FAIL.
        String served = lc.getFingerprint();
        if (served != null && !served.isBlank()
                && !lc.getPinnedFingerprint().equalsIgnoreCase(served)) {
            return new HealthRow("pinnedFingerprint", GROUP_CERTIFICATE, Status.FAIL, "certPinMismatch",
                    List.of(), "investigateInterception", List.of(), ev);
        }

        String changedAt = lc.getFingerprintChangedAt();
        if (changedAt != null && isRecent(changedAt)) {
            // Kullanıcı "planlı yenilemeydi" dediyse satır yeşile döner ve kimin/ne zaman onayladığını
            // taşır (2026-09-11 kullanıcı bildirimi: uyarı 7 gün sarı kalıyor, onaylayacak yer yoktu).
            // Onay SABİTLENEN parmak izine bağlıdır — pin yeniden değişirse eski onay o değişimi
            // kapsamaz, satır yeniden uyarır (aksi hâlde tek onay sonsuza dek susturur).
            if (isRenewalConfirmed(lc)) {
                ev.put("confirmed_by", lc.getFingerprintAckBy());
                ev.put("confirmed_at", lc.getFingerprintAckAt());
                return new HealthRow("pinnedFingerprint", GROUP_CERTIFICATE, Status.OK, "certRenewalConfirmed",
                        List.of(shortDate(lc.getFingerprintAckAt())), "renewalConfirmed", List.of(), ev);
            }
            return new HealthRow("pinnedFingerprint", GROUP_CERTIFICATE, Status.WARN, "certChanged",
                    List.of(shortDate(changedAt)), "confirmRenewal", List.of(), ev);
        }
        return new HealthRow("pinnedFingerprint", GROUP_CERTIFICATE, Status.OK, "certStable",
                List.of(), "none", List.of(), ev);
    }

    /** Onay, ŞU AN sabitlenen parmak izi için mi verilmiş? (büyük/küçük harf duyarsız; eski onay sayılmaz) */
    static boolean isRenewalConfirmed(LatestCheck lc) {
        String ack = lc.getFingerprintAckFingerprint();
        return ack != null && !ack.isBlank() && lc.getFingerprintAckAt() != null
                && ack.equalsIgnoreCase(lc.getPinnedFingerprint());
    }

    /**
     * Değişim "yeni" mi — {@value #CHANGE_NOTICE_DAYS} günlük pencere.
     *
     * <p>Pencere olmadan iki seçenek kalırdı: değişimi hiç göstermemek (sinyal kaybolur) ya da
     * sonsuza dek göstermek (satır kalıcı sarı olur). Ayrıştırılamayan tarih "yeni değil" sayılır —
     * biçim hatası uyarı üretmemeli.
     */
    private static boolean isRecent(String iso) {
        try {
            var t = java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC);
            return t.isAfter(java.time.Instant.now().minus(CHANGE_NOTICE_DAYS, java.time.temporal.ChronoUnit.DAYS));
        } catch (Exception e) {
            return false;
        }
    }

    /** ISO → "23.08.2026" (rozet kısa kalsın; tam değer kanıtta). */
    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return String.valueOf(iso);
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4);
    }

    /** SAN JSON dizisi → liste. Bozuk/boş içerik boş liste döner (satır UNKNOWN olur, çökmez). */
    private static List<String> parseSan(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.debug("SAN listesi ayrıştırılamadı: {}", e.toString());
            return List.of();
        }
    }

    // ── Protokol & şifreleme grubu ──────────────────────────────────────────

    private HealthRow protocolRow(LatestCheck lc) {
        String tls = lc.getTlsVersion();
        Status st = CertificateHealthRules.protocolStatus(tls);
        // TLS_MODE=browser: istemci 1.2'ye sabitlenmiş olabilir → sunucunun MAKSİMUMU bu olmayabilir.
        // Ek el sıkışma yapmıyoruz (K6a); arayüz bu notu satır altında gösterir.
        Map<String, Object> ev = ev("tls_mode_used", lc.getTlsModeUsed(), "raw", tls);
        if (st == Status.FAIL) {
            return new HealthRow("protocol", GROUP_TRANSPORT, st, "outdatedProtocol", List.of(str(tls)),
                    "disableOldProtocols", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("protocol", GROUP_TRANSPORT, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        String valueKey = CertificateHealthRules.isLatestProtocol(tls) ? "currentProtocol" : "acceptedProtocol";
        String action = CertificateHealthRules.isLatestProtocol(tls) ? "none" : "considerTls13";
        return new HealthRow("protocol", GROUP_TRANSPORT, st, valueKey, List.of(str(tls)), action, List.of(), ev);
    }

    private HealthRow cipherRow(LatestCheck lc) {
        String suite = lc.getCipherSuite();
        Status st = CertificateHealthRules.cipherStatus(suite);
        var tier = CertificateHealthRules.cipherTier(suite);
        Map<String, Object> ev = ev("cipher_suite", suite);
        String action = switch (tier) {
            case WEAK -> "replaceCipher";
            case ACCEPTABLE -> "preferAead";
            case UNKNOWN -> "runCheck";
            case STRONG -> "none";
        };
        String valueKey = switch (tier) {
            case STRONG -> "cipherStrong";
            case ACCEPTABLE -> "cipherAcceptable";
            case WEAK -> "cipherWeak";
            case UNKNOWN -> "unverified";
        };
        return new HealthRow("cipher", GROUP_TRANSPORT, st, valueKey,
                tier == CertificateHealthRules.CipherTier.UNKNOWN ? List.of() : List.of(str(suite)),
                action, List.of(), ev);
    }

    private HealthRow pfsRow(LatestCheck lc) {
        Status st = CertificateHealthRules.pfsStatus(lc.getTlsVersion(), lc.getCipherSuite());
        Map<String, Object> ev = ev("tls_version", lc.getTlsVersion(), "cipher_suite", lc.getCipherSuite());
        if (st == Status.FAIL) {
            return new HealthRow("pfs", GROUP_TRANSPORT, st, "disabled", List.of(), "enablePfs", List.of(), ev);
        }
        if (st == Status.UNKNOWN) {
            return new HealthRow("pfs", GROUP_TRANSPORT, st, "unverified", List.of(), "runCheck", List.of(), ev);
        }
        return new HealthRow("pfs", GROUP_TRANSPORT, st, "enabled", List.of(), "none", List.of(), ev);
    }

    // ── Uygulama katmanı grubu ──────────────────────────────────────────────

    private HealthRow hstsRow(LatestCheck lc) {
        String status = lc.getHstsStatus();
        // Gerekçe kanıta girer: "Doğrulanamadı" deyip nedenini söylememek, kullanıcıyı
        // ekranı bırakıp koda bakmaya zorlayan şeydi (vekil kararı sapması vakası).
        Map<String, Object> ev = ev("raw", status, "checked_at", lc.getHstsAt(),
                "note", lc.getHstsNote());
        if (status == null || status.isBlank()) {
            // Hiç bakılmamış: kullanıcı isteğiyle koşar (K4) — otomatik başlık çekmiyoruz.
            return new HealthRow("hsts", GROUP_APPLICATION, Status.UNKNOWN, "notChecked", List.of(),
                    "checkOnDemand", List.of(), ev);
        }
        if ("ENABLED".equalsIgnoreCase(status)) {
            return new HealthRow("hsts", GROUP_APPLICATION, Status.OK, "enabled", List.of(), "none", List.of(), ev);
        }
        if ("MISSING".equalsIgnoreCase(status)) {
            return new HealthRow("hsts", GROUP_APPLICATION, Status.WARN, "missing", List.of(), "enableHsts", List.of(), ev);
        }
        return new HealthRow("hsts", GROUP_APPLICATION, Status.UNKNOWN, "unverified", List.of(),
                "checkOnDemand", List.of(), ev);
    }

    /**
     * Karışık içerik satırı — HSTS ile AYNI ayrımı yapar: "hiç bakılmadı" ile "bakıldı ama
     * belirlenemedi" farklı şeylerdir.
     *
     * <p><b>Neden değişti.</b> Eskiden her iki durum da {@code notChecked} ("Kontrol edilmedi")
     * etiketiyle çıkıyor ve önerilen eylem yine "kontrol edin" oluyordu. Kullanıcı "Şimdi kontrol
     * et"e basınca kontrol GERÇEKTEN koşuyor (zaman damgası ve kaynak kaydediliyor) ama satır
     * hiç değişmiyordu — ekran, az önce yapılan şeyi öneren kapalı bir döngüye giriyordu.
     * En sık sebebi API uçları: {@code GET /} 401/403/404 dönünce taranacak HTML yoktur.
     * Artık o durum "Doğrulanamadı" olarak çıkar, gerekçesi kanıtta görünür ve "Sayfa İzleme
     * ekleyin" önerisi anlam kazanır.
     */
    private HealthRow mixedContentRow(LatestCheck lc, boolean hasPageMonitor) {
        String status = lc.getMixedContentStatus();
        Map<String, Object> ev = ev("raw", status, "checked_at", lc.getMixedContentAt(),
                "source", hasPageMonitor ? "pageMonitor" : "onDemand",
                "note", lc.getMixedContentNote());

        if ("CLEAN".equalsIgnoreCase(status)) {
            return new HealthRow("mixedContent", GROUP_APPLICATION, Status.OK, "noMixedContent",
                    List.of(), "none", List.of(), ev);
        }
        if ("MIXED".equalsIgnoreCase(status)) {
            return new HealthRow("mixedContent", GROUP_APPLICATION, Status.FAIL, "mixedFound",
                    List.of(), hasPageMonitor ? "fixMixedSeePageMonitor" : "fixMixed", List.of(), ev);
        }
        if (status == null || status.isBlank()) {
            // HİÇ bakılmamış: kullanıcıya ne yapabileceği söylenir.
            return new HealthRow("mixedContent", GROUP_APPLICATION, Status.UNKNOWN, "notChecked",
                    List.of(), hasPageMonitor ? "checkOnDemand" : "checkOnDemandOrAddPageMonitor",
                    List.of(), ev);
        }
        // Bakıldı ama belirlenemedi (UNKNOWN): tekrar "kontrol edin" demek işe yaramaz —
        // sayfa çekilemediği için sonuç yine aynı olur. Kalıcı çözüm Sayfa İzleme'dir.
        return new HealthRow("mixedContent", GROUP_APPLICATION, Status.UNKNOWN, "unverified",
                List.of(), hasPageMonitor ? "checkOnDemand" : "addPageMonitor", List.of(), ev);
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────

    /** null değerleri ELEYEN kanıt haritası — arayüzde boş satır çizilmesin. */
    private static Map<String, Object> ev(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null && !(v instanceof String s && s.isBlank())) m.put(String.valueOf(kv[i]), v);
        }
        return m;
    }

    private static String str(String s) { return s == null ? "" : s; }
}
