package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dns_monitors")
@Data
@NoArgsConstructor
public class DnsMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String domain;

    @Column(name = "record_type", nullable = false)
    private String recordType;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    /** true = kullanıcının DNS sayfasından eklediği, sertifika envanterine bağlı OLMAYAN monitör.
     *  Envanter-skip'i baypas eder (her zaman kontrol edilir). null/false = envanter-türevi (otomatik senkron). */
    @Column(name = "standalone")
    private Boolean standalone = false;

    /** Standalone monitörün sorumlu takımı (alarm yönlendirme + liste kapsamı için).
     *  Envanter-türevi monitörlerde null — takım domain→envanter eşlemesinden gelir. */
    @Column(name = "team_id")
    private Long teamId;

    /** Beklenen-değer kilidi (baseline): kullanıcının sabitlediği bilinen-iyi değer(ler), satır (\n) ayrılmış.
     *  Boş = kilit kapalı. Doluyken canlı sonuçta BEKLENMEYEN (bu sette olmayan) değer çıkarsa DNS_UNEXPECTED
     *  alarmı (esnek/hijack-odaklı: beklenenin alt kümesi = rotasyon, alarm üretmez). */
    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    /** Çoklu-resolver tutarlılık (propagation) kontrolü açık mı? Opt-in: yalnız true olan monitörlerde
     *  domain birden çok public resolver'a sorulup cevaplar karşılaştırılır (CDN gürültüsünü önlemek için). */
    @Column(name = "propagation_check")
    private Boolean propagationCheck = false;

    /** Per-monitor DNS_SLOW eşiği (ms). null = global site.monitor.dns.slow-threshold-ms (varsayılan 1500).
     *  Bu süreyi aşan çözümlemelerde DNS_SLOW alarmı; boşsa genel ayar kullanılır. */
    @Column(name = "slow_threshold_ms")
    private Integer slowThresholdMs;

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form (ping/keyword ile aynı). */
    @Column(name = "group_name")
    private String groupName;

    /** DNS_CHANGED alarmı monitör bazında açık mı? null = açık (mevcut monitörler etkilenmez;
     *  etkin kontrol her yerde !Boolean.FALSE.equals). false = değişiklik alarmı ve günlük RE-ALERT bastırılır;
     *  kontrol/kayıt geçmişi (changed=true satırları) değişmeden tutulmaya devam eder. */
    @Column(name = "dns_change_alert_enabled")
    private Boolean dnsChangeAlertEnabled;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

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
}
