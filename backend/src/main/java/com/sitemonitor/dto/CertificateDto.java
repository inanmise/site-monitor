package com.sitemonitor.dto;

import com.sitemonitor.model.LatestCheck;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CertificateDto {

    private String domain;
    private String subject;
    private String issuer;

    @JsonProperty("issuer_cn")
    private String issuerCn;

    @JsonProperty("not_before")
    private String notBefore;

    @JsonProperty("not_after")
    private String notAfter;

    @JsonProperty("days_remaining")
    private Integer daysRemaining;

    private Boolean warning;
    private String status;
    private String error;

    @JsonProperty("alert_level")
    private String alertLevel;
    private List<String> san;

    @JsonProperty("checked_at")
    private String checkedAt;

    private String fingerprint;

    @JsonProperty("chain_status")
    private String chainStatus;

    @JsonProperty("deployment_status")
    private String deploymentStatus;

    @JsonProperty("intermediate_expiry")
    private String intermediateExpiry;

    @JsonProperty("intermediate_days_remaining")
    private Integer intermediateDaysRemaining;

    @JsonProperty("revocation_status")
    private String revocationStatus;

    @JsonProperty("trust_status")
    private String trustStatus;

    /**
     * Güvenlik kusuru bayrakları — {@code HOSTNAME_MISMATCH} / {@code UNTRUSTED_CA}.
     *
     * <p>Süreden BAĞIMSIZ: bir sertifika 1775 gün geçerli olup yine de bu host için kabul
     * edilemez olabilir (tarayıcının reddettiği durum). Rozet katmanı buna bakar; süre
     * merdiveni ve süre süzgeçleri DEĞİŞMEZ.
     */
    @JsonProperty("security_flags")
    private java.util.List<String> securityFlags;

    /** {@code security_flags} boş mu — arayuz "güvensiz mi" sorusunu tersine çevirmek zorunda kalmasın. */
    private Boolean secure;

    // Extended certificate metadata
    @JsonProperty("serial_number")
    private String serialNumber;

    @JsonProperty("signature_algorithm")
    private String signatureAlgorithm;

    @JsonProperty("public_key_algorithm")
    private String publicKeyAlgorithm;

    @JsonProperty("public_key_size")
    private Integer publicKeySize;

    @JsonProperty("subject_dn")
    private String subjectDn;

    @JsonProperty("issuer_dn")
    private String issuerDn;

    @JsonProperty("key_usage")
    private List<String> keyUsage;

    @JsonProperty("ext_key_usage")
    private List<String> extKeyUsage;

    @JsonProperty("is_ca")
    private Boolean isCa;

    @JsonProperty("ocsp_url")
    private String ocspUrl;

    @JsonProperty("crl_url")
    private String crlUrl;

    /** Check transport path the final attempt used: "proxy" | "direct" (null on pre-v18.53 rows) */
    private String via;

    @JsonProperty("tls_mode_used")
    private String tlsModeUsed;

    /**
     * Kontrolün el sıkışmasındaki TLS sürümü ve şifre takımı (2026-09-28, sertifika penceresi → Sertifika Detayları).
     * Veritabanında vardı ({@code certificate_checks.tls_version/cipher_suite}), yanıta hiç girmiyordu.
     *
     * <p><b>YALNIZ /history yolunda dolar</b> ({@link #applyTls} — {@code CertificateService.toDtoFromCheck}); {@link #from}
     * doldurmaz. Pano / Tüm Sertifikalar / Uyarılar listeleri bu alanları kullanmıyor; {@code NON_NULL} sayesinde o
     * yanıtlara tek bayt eklenmez (1000 satırlık listede aksi hâlde ~60 KB).
     */
    @JsonProperty("tls_version")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String tlsVersion;

    @JsonProperty("cipher_suite")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String cipherSuite;

    /**
     * Protokol / şifre hükmü — {@code check-preview}'ın {@code assessment} alanıyla AYNI anahtarlar ve değerler
     * ({@code protocol}, {@code protocol_latest}, {@code cipher}; {@code OK|WARN|FAIL|UNKNOWN}). Kural TEK yerde:
     * {@link com.sitemonitor.service.CertificateHealthRules} (Sağlık sekmesi ve Zayıf Algoritma Raporu ile aynı). Arayüz
     * zayıf protokol / şifre rozetini buradan okur, ikinci bir kural yazmaz. İki değer de boşsa null → yazılmaz.
     */
    @JsonProperty("tls_assessment")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String, Object> tlsAssessment;

    /** TLS sürümü + şifre takımı + hükmü yazar (yalnız /history yolu). Boş değer null kalır. */
    public CertificateDto applyTls(String version, String cipher) {
        this.tlsVersion = version == null || version.isBlank() ? null : version.trim();
        this.cipherSuite = cipher == null || cipher.isBlank() ? null : cipher.trim();
        if (this.tlsVersion == null && this.cipherSuite == null) {
            this.tlsAssessment = null;
            return this;
        }
        java.util.Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("protocol", com.sitemonitor.service.CertificateHealthRules.protocolStatus(this.tlsVersion).name());
        a.put("protocol_latest", com.sitemonitor.service.CertificateHealthRules.isLatestProtocol(this.tlsVersion));
        a.put("cipher", com.sitemonitor.service.CertificateHealthRules.cipherStatus(this.cipherSuite).name());
        this.tlsAssessment = a;
        return this;
    }

    /** Criticality tier from inventory (1–4, null = unclassified) */
    private Integer tier;

    /** Envanterdeki TLS portu (443 dışı hedefler için; null = envantersiz). */
    private Integer port;

    /** Sorumlu (SY) takım — envanterden (null = takımsız/envantersiz). Dashboard kart etiketi + filtre. */
    @JsonProperty("team_id")
    private Long teamId;

    @JsonProperty("team_name")
    private String teamName;

    /** Envanter grubu + etiketler (2026-09-18): Genel Bakış grup/etiket filtresi. null = envantersiz/atanmamış. */
    @JsonProperty("group_name")
    private String groupName;

    private String tags;
    /** Sitenin koştuğu platform + ayrıntı (envanterden; 2026-09-22) — kart/tablo "nereye kurulacak" bilgisi */
    private String platform;
    private String platformDetail;
    private String platformName;   // katalog adı (kod pasife alınmış/katalog yoksa null → arayüz kodu gösterir)

    /**
     * Envanterdeki alan-başına kontrol sıklığı (saat; null = genel süpürme). Tablo "bayat" rozeti
     * için: son kontrol bu sıklığın iki katından eskiyse kullanıcı sayıya güvenmemeli.
     */
    @JsonProperty("check_interval_hours")
    private Integer checkIntervalHours;

    /**
     * Envanter kaydının "7/24 izleme ekibine bildir" anahtarı (2026-09-28) — Genel Bakış / Uyarılar sertifika satırındaki
     * 7/24 göstergesi (açık / açık · iletilmiyor / kapalı). Envanter satırını zaten okuyan listeler doldurur
     * ({@code getAllLatest}: Pano + Tüm Sertifikalar; {@code getWarnings}: Uyarılar — aynı tek envanter okumasından).
     * Envanter birleşimi OLMAYAN satırda (kontrol geçmişi {@code getHistory}, önizleme) null → YAZILMAZ: arayüz
     * "bilinmiyor"u "kapalı" sanmasın (alan yoksa gösterge çizilmez).
     */
    @JsonProperty("noc_notify")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean nocNotify;

    /**
     * Kaydın seçtiği 7/24 grup kimlikleri (2026-09-28, gösterge açıklamasındaki "Alıcı gruplar") — izleme listelerinin
     * {@code noc_group_ids} biçimiyle AYNI: boş liste = varsayılan gruplar ({@code NocGroupIds.parse}). {@link #nocNotify}
     * ile aynı yerde ve aynı envanter okumasından dolar; null → yazılmaz (satır taşımıyor → arayüz alıcı grup yazmaz).
     */
    @JsonProperty("noc_group_ids")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Long> nocGroupIds;

    /**
     * Org geneli görünürlük (2026-09-26): çağıran bu satırın kaydını DEĞİŞTİREBİLİR mi
     * ({@code SessionScope.canWriteInventory}). Yalnız Tüm Sertifikalar listesi doldurur — ve yalnız
     * {@link #withCanManage} KOPYASINA: {@code getAllLatest} sonucu önbellekte PAYLAŞILIYOR, aynı nesneye
     * yazmak bir kullanıcının bayrağını ötekine sızdırırdı. Pano ({@code /certificates}) doldurmaz → null → yazılmaz.
     */
    @JsonProperty("can_manage")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean canManage;

    /**
     * Elle yüklenen sertifika (2026-10-06): {@code "MANUAL"}; ağdan kontrol edilen satırda null → YAZILMAZ (yanıt
     * bugünküyle bayt bayt aynı kalır). Envanter satırını okuyan listeler ({@code getAllLatest}) doldurur.
     */
    @JsonProperty("cert_source")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String certSource;

    /** İzlenen (geçerli) sürüm numarası — yalnız manuel satırda. */
    @JsonProperty("manual_version")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer manualVersion;

    /** Geçerli sürümün yüklenme anı — yalnız manuel satırda. */
    @JsonProperty("manual_uploaded_at")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String manualUploadedAt;

    /** Sığ kopya + {@code can_manage}; önbellekteki paylaşılan nesneye DOKUNMAZ. */
    public CertificateDto withCanManage(boolean value) {
        CertificateDto copy = new CertificateDto();
        org.springframework.beans.BeanUtils.copyProperties(this, copy);
        copy.canManage = value;
        return copy;
    }

    public static CertificateDto from(LatestCheck c, List<String> sanList,
                                      List<String> keyUsageList, List<String> extKeyUsageList) {
        CertificateDto dto = new CertificateDto();
        dto.domain = c.getDomain();
        dto.subject = c.getSubject();
        dto.issuer = c.getIssuer();
        dto.issuerCn = c.getIssuerCn();
        dto.notBefore = c.getNotBefore();
        dto.notAfter = c.getNotAfter();
        dto.daysRemaining = c.getDaysRemaining();
        dto.warning = c.getWarning();
        dto.status = c.getStatus();
        dto.error = c.getError();
        dto.san = sanList;
        dto.checkedAt = c.getCheckedAt();
        dto.fingerprint = c.getFingerprint();
        dto.chainStatus = c.getChainStatus();
        dto.deploymentStatus = c.getDeploymentStatus();
        dto.intermediateExpiry = c.getIntermediateExpiry();
        dto.intermediateDaysRemaining = c.getIntermediateDaysRemaining();
        dto.revocationStatus = c.getRevocationStatus();
        dto.trustStatus = c.getTrustStatus();
        dto.serialNumber = c.getSerialNumber();
        dto.signatureAlgorithm = c.getSignatureAlgorithm();
        dto.publicKeyAlgorithm = c.getPublicKeyAlgorithm();
        dto.publicKeySize = c.getPublicKeySize();
        dto.subjectDn = c.getSubjectDn();
        dto.issuerDn = c.getIssuerDn();
        dto.keyUsage = keyUsageList;
        dto.extKeyUsage = extKeyUsageList;
        dto.isCa = c.getIsCa();
        dto.ocspUrl = c.getOcspUrl();
        dto.crlUrl = c.getCrlUrl();
        dto.via = c.getVia();
        dto.tlsModeUsed = c.getTlsModeUsed();
        // TEK hüküm çekirdeği: aynı kural sağlık satırlarını, rozeti ve alarmı besler.
        // Elle yüklenen sertifikada (via=upload, 2026-10-06) takip adı bir host adı değildir → ad kapsaması sorulmaz.
        dto.securityFlags = com.sitemonitor.service.CertificateHealthRules.securityFlags(
                c.getDomain(), sanList, c.getTrustStatus(),
                com.sitemonitor.service.CertificateHealthRules.VIA_UPLOAD.equals(c.getVia()));
        dto.secure = dto.securityFlags.isEmpty();
        return dto;
    }
}
