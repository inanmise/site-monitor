package com.sitemonitor.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "certificate_inventory")
@Data
@NoArgsConstructor
public class CertificateInventory implements NocTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Domain boş olamaz")
    @Size(max = 253, message = "Domain en fazla 253 karakter olabilir")
    @Pattern(
        regexp = "^(?!-)(?!.*--)(?:\\*\\.)?[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
        message = "Geçersiz domain formatı"
    )
    @Column(nullable = false, unique = true)
    private String domain;

    @Min(value = 1,     message = "Port 1 ile 65535 arasında olmalı")
    @Max(value = 65535, message = "Port 1 ile 65535 arasında olmalı")
    @Column(nullable = false)
    private Integer port = 443;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String owner;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "deleted_at")
    private String deletedAt;

    /** Team responsible for this certificate */
    @Column(name = "team_id")
    private Long teamId;

    /** SHA-256 hex of the expected (newly renewed) cert — used for DEPLOYMENT_INCOMPLETE detection */
    private String expectedFingerprint;

    @Column(columnDefinition = "TEXT")
    private String expectedSubject;

    // ── İkinci takım (Uygulama Geliştirici / UG) ──────────────────────────────
    @Column(name = "ug_team_id")
    private Long ugTeamId;

    /** Mantıksal grup (ör. "X Sistemleri") — takım-bazlı; monitoring_groups registry'sine bağlı. */
    @Column(name = "group_name")
    private String groupName;

    // ── Operasyonel boolean alanlar ───────────────────────────────────────────
    @Column(name = "external_vendor")    private Boolean externalVendor;
    @Column(name = "action_required")    private Boolean actionRequired;
    @Column(name = "openshift")          private Boolean openshift;
    @Column(name = "ssl_pinning")        private Boolean sslPinning;
    @Column(name = "internal_cert")      private Boolean internalCert;
    @Column(name = "jks_keystore")       private Boolean jksKeystore;
    @Column(name = "server_update")      private Boolean serverUpdate;
    @Column(name = "netscaler")          private Boolean netscaler;
    @Column(name = "waf_enabled")        private Boolean wafEnabled;
    @Column(name = "in_use")             private Boolean inUse;
    @Column(name = "ev_certificate")     private Boolean evCertificate;
    @Column(name = "transferred_to_sy")  private Boolean transferredToSy;
    @Column(name = "use_proxy")          private Boolean useProxy;

    /**
     * Bu kayda özgü bağlantı zaman aşımı (saniye). {@code null} → global ayar
     * ({@code site.monitor.check-timeout-seconds}, varsayılan 6 sn).
     *
     * <p>Neden kayıt bazlı: yavaş ama ÇALIŞAN bir iç hedef 6 saniyede yetişemiyordu ve
     * kullanıcının tek çaresi TÜM envanteri yavaşlatan global ayarı büyütmekti.
     */
    @Column(name = "timeout_seconds")    private Integer timeoutSeconds;

    /**
     * Alan başına kontrol sıklığı (saat) — 2026-09-12, kullanıcı: "kartın düzenle modalında kontrol
     * sıklığı seçilebilmeli (saatlik / 12 saatlik / günlük / haftalık)". Saatlik süpürme, son kontrolün
     * üzerinden bu kadar saat geçmemişse alanı atlar (stale süpürmesi de aynı süzgeci uygular; yoksa
     * atlanan alanı 5 dk sonra yakalardı). NULL = genel zamanlama (her süpürmede). Elle "Şimdi kontrol et"
     * bu değere bakmaz. Nullable: dolu tabloya NOT NULL eklenmez (sessizce düşer).
     */
    @Column(name = "check_interval_hours") private Integer checkIntervalHours;

    /** Per-domain TLS handshake mode override: null=inherit global setting,
     *  "browser" (TLS 1.2 + ALPN) or "default" (JDK defaults, TLS 1.3). */
    @Column(name = "tls_mode", length = 16)
    private String tlsMode;

    // ── Sorumlu Ekipler ───────────────────────────────────────────────────────
    /**
     * Bu sertifikanın yenilenmesinde kimin ne yapacağını gösteren SERBEST METİN alanları.
     * "Ad Soyad - ad.soyad@example.com", yalnız e-posta ya da yalnız ad — üçü de kabul; üretimdeki
     * kullanım bu üç biçimi de içeriyor, o yüzden yapılandırılmış ad+e-posta çifti YAPILMADI.
     *
     * <p><b>Alarm YÖNLENDİRMESİNE girmez.</b> Alıcıyı {@code teamId}/{@code ugTeamId} ve
     * {@code notificationGroupId} zinciri belirler; bu dört alan yalnız BİLGİLENDİRMEDİR
     * (uyarı e-postasında "Sorumlu Ekipler" kartı + envanter detayında gösterim). Alıcı listesine
     * eklenselerdi, kimin haber alacağı iki ayrı yerden yönetilir ve zamanla sapardı.
     */
    @Column(name = "svc_mgmt_contact", length = 300)
    private String svcMgmtContact;

    @Column(name = "app_dev_contact", length = 300)
    private String appDevContact;

    @Column(name = "iis_admin_contact", length = 300)
    private String iisAdminContact;

    @Column(name = "waf_admin_contact", length = 300)
    private String wafAdminContact;

    // ── Süreç ve açıklama alanları ────────────────────────────────────────────
    @Column(name = "purchased_by", length = 200)
    private String purchasedBy;

    /** Sitenin koştuğu platform (2026-09-22, kullanıcı isteği): IIS | OPENSHIFT | KUBERNETES | LINUX | WINDOWS | CLOUD | OTHER; null = girilmemiş.
     *  Sertifikayı KİM yenileyecek/nereye kuracak sorusunun cevabı — karttan görünür. Serbest ayrıntı (küme/sunucu adı) platformDetail'de. */
    @Column(name = "platform", length = 20)
    private String platform;
    @Column(name = "platform_detail", length = 160)
    private String platformDetail;

    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    /** Criticality tier: 1=Customer-Facing Prod, 2=Internal Prod, 3=UAT/Pre-Prod, 4=Dev/Sandbox, null=unclassified */
    @Min(value = 1, message = "Tier 1 ile 4 arasında olmalı")
    @Max(value = 4, message = "Tier 1 ile 4 arasında olmalı")
    @Column(name = "tier")
    private Integer tier;

    private String createdAt;
    private String updatedAt;

    // ── Alan adı (registrar) süre bitişi — Alan Adı Tanılama aracıyla doldurulur (TLS sertifika bitişinden AYRI). ──
    @Column(name = "domain_expiry", columnDefinition = "TEXT")
    private String domainExpiry;

    @Column(name = "domain_registrar", columnDefinition = "TEXT")
    private String domainRegistrar;

    @Column(name = "domain_expiry_checked_at", columnDefinition = "TEXT")
    private String domainExpiryCheckedAt;

    // ── Liste yanıtı için takım isimleri (DB'de tutulmaz; listInventory doldurur) ──
    // USER rolünde frontend tüm takım listesini çekemediğinden (kendi takımıyla
    // filtreli) isimler burada sunucuda çözülür → her rolde SY/UG takım adı görünür.
    @Transient private String teamName;
    @Transient private String ugTeamName;

    // ── Canlı kontrol özeti (2026-09-12, envanter #3) — listInventory latest_checks'ten doldurur ──
    @Transient private String certStatus;          // valid | warning | critical | error | …
    @Transient private Integer certDaysRemaining;
    @Transient private String certNotAfter;
    @Transient private String certCheckedAt;
    @Transient private String certIssuer;
    @Transient private String certError;

    // ── Org geneli görünürlük (2026-09-26): çağıran bu kaydı DEĞİŞTİREBİLİR mi (SessionScope.canWriteInventory).
    // Yalnız okuma uçları (listInventory / by-domain) doldurur; diğer yanıtlarda null → hiç yazılmaz. İstek
    // gövdesinden gelen değer hiçbir yerde okunmaz (yazma kapıları her seferinde oturumdan yeniden hesaplar).
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean canManage;

    // ── Bildirim grubunun ADI (2026-09-28) — yalnız okuma uçları (listInventory / by-domain) doldurur; DB'de tutulmaz.
    // Yalnız grup GERÇEKTEN uygulanıyorsa (aktif + kaydın takımına ait — NotificationGroupService.overrideFor ile aynı
    // koşul) VE çağıran grubu okuyabiliyorsa (notification.groups/view + takım görüş kapsamı — grup ucunun kapısı) yazılır.
    // Aksi hâlde null → hiç yazılmaz; arayüz kimliği "bulunamadı" açıklamasıyla gösterir (grup ucunun 404 deseni:
    // başka takımın grubunun VAR OLDUĞU bile söylenmez). İstek gövdesinden gelen değer hiçbir yerde okunmaz.
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String notificationGroupName;

    // ── Kimlik künyesi ────────────────────────────────────────────────────────────────────
    // "Bu izlemeyi kim kurdu?" sorusu geçmiş tablosuna gitmeden de cevaplanabilsin (kart künyesi
    // bunu okur). monitor_change_log'dan BAĞIMSIZ: biri retention ile temizlense de diğeri kalır.
    // Eski kayıtlarda null'dır — arayüz o zaman künyeyi hiç göstermez.
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_ip", length = 50)
    private String createdIp;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;


    /**
     * Bu izlemenin alarmlarinin gidecegi Bildirim Grubu — NULL ise zincirin kalani islet:
     * takimin varsayilan grubu, o da yoksa {@code Team.email} (bugunku davranis).
     */
    @jakarta.persistence.Column(name = "notification_group_id")
    private Long notificationGroupId;

    // ── 7/24 İzleme Ekibi (NOC) bildirimi (2026-09-27) — bkz. NocTarget. Kolonlar NULLABLE (null = kapalı /
    // varsayılan gruplar). İstek gövdesi bu varlığa DOĞRUDAN bağlanıyor (snake_case: noc_notify, noc_group_ids);
    // gövdede alan YOKSA mevcut değer korunmalı — aksi hâlde alanı bilmeyen bir istemcinin (eski form, toplu yol)
    // her düzenlemesi 7/24 bildirimini SESSİZCE kapatırdı. Bu yüzden JSON erişimi açık adlı yöntemlerden geçer ve
    // "gövdede geldi mi" bilgisini taşır; updateInventory yalnız gelen alanı yazar.
    @jakarta.persistence.Column(name = "noc_notify")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean nocNotify;

    /** Virgüllü NOC grup kimlikleri; null = varsayılan gruplar. JSON'da LİSTE ({@code noc_group_ids}). */
    @jakarta.persistence.Column(name = "noc_group_ids", length = 500)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String nocGroupIds;

    @Transient @com.fasterxml.jackson.annotation.JsonIgnore
    private transient boolean nocNotifySupplied;

    @Transient @com.fasterxml.jackson.annotation.JsonIgnore
    private transient boolean nocGroupIdsSupplied;

    /** Yanıt: null da {@code false} yazılır (sözleşme: null = kapalı). */
    @com.fasterxml.jackson.annotation.JsonProperty("noc_notify")
    public Boolean nocNotifyForJson() {
        return Boolean.TRUE.equals(nocNotify);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("noc_notify")
    public void nocNotifyFromJson(Boolean v) {
        this.nocNotify = v;
        this.nocNotifySupplied = true;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("noc_group_ids")
    public java.util.List<Long> nocGroupIdsForJson() {
        return com.sitemonitor.service.noc.NocGroupIds.parse(nocGroupIds);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("noc_group_ids")
    public void nocGroupIdsFromJson(java.util.List<Long> ids) {
        this.nocGroupIds = com.sitemonitor.service.noc.NocGroupIds.format(ids);
        this.nocGroupIdsSupplied = true;
    }

    // ── Planlanan yenileme (2026-09-12, vade takvimi #6): operatör "bu tarihte yenileyeceğiz" der; takvimde
    // taralı çizilir, gerçek yenileme (parmak izi değişimi) görülünce sunucu temizler. Hepsi nullable.
    @Column(name = "renewal_planned_at", length = 10)      private String renewalPlannedAt;      // YYYY-MM-DD
    @Column(name = "renewal_planned_by", length = 100)     private String renewalPlannedBy;
    @Column(name = "renewal_planned_by_name")              private String renewalPlannedByName;
    @Column(name = "renewal_planned_note", length = 500)   private String renewalPlannedNote;
}
