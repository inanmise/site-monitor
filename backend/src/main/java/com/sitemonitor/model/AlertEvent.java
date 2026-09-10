package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "alert_events",
    indexes = {
        @Index(name = "idx_ae_domain",           columnList = "domain"),
        @Index(name = "idx_ae_resolved",         columnList = "resolved"),
        @Index(name = "idx_ae_alert_level",      columnList = "alertLevel"),
        @Index(name = "idx_ae_domain_type_open", columnList = "domain,alertType,resolved"),
        @Index(name = "idx_ae_storm_id",         columnList = "stormId")
    }
)
@Data
@NoArgsConstructor
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    /** WARNING, HIGH, CRITICAL */
    @Column(nullable = false)
    private String alertLevel;

    /** EXPIRY, CHAIN_BROKEN, REVOKED, MISMATCH */
    @Column(nullable = false)
    private String alertType;

    @Column(columnDefinition = "TEXT")
    private String message;

    private Integer daysRemaining;

    /**
     * Sertifika alarmlarında sertifikanın GERÇEK son geçerlilik anı (UTC, {@code yyyy-MM-ddTHH:mm:ss}),
     * alarm açılırken damgalanır; eskalasyon/re-alert sonuç taşıyorsa güncellenir. Eskiden arayüz
     * {@code created_at + days_remaining} ile YENİDEN HESAPLIYORDU: days_remaining sonradan
     * güncellenip created_at sabit kaldığından kapalı alarm kartı 18 gün erken, saati hep ":00"
     * bir tarih gösteriyordu (2026-09-10). Eski satırlarda NULL — AdminController.enrichAlerts
     * LatestCheck'ten geri doldurur. Diğer izleme türlerinde null.
     */
    @Column(name = "not_after")
    private String notAfter;

    /** Sorumlu takım — özellikle serbest-form izleme (keyword/ping) çözüm bildiriminde alıcıyı
     *  (yalnız takım) buradan bulmak için (url/host envanterde olmadığından). Oluşturulurken doldurulur. */
    private Long teamId;

    @Column(columnDefinition = "TEXT")
    private String notifiedContacts;

    /** Alarm anı context snapshot'ı (JSON) — özellikle keyword/ping çözüldü e-postasında
     *  hangi kelime/koşul/ne bulundu detayını göstermek için (url/host envanterde yok). */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(nullable = false)
    private Boolean acknowledged = false;

    private String acknowledgedBy;
    private String acknowledgedAt;

    /**
     * Alarmın NEDEN onaylandığı — kullanıcının yazdığı gerekçe.
     *
     * <p>Onay eskiden yalnız "kim" ve "ne zaman" bırakıyordu; haftalar sonra bakan kişi sorunun
     * çözülüp çözülmediğini mi yoksa yalnız susturulduğunu mu bilemiyordu. Manuel onayda zorunlu
     * (en az 3 kelime, sunucuda da doğrulanır); ONAY ÖNCESİ kayıtlarda ve otomatik yollarda null.
     */
    @Column(columnDefinition = "TEXT")
    private String acknowledgedNote;

    @Column(nullable = false)
    private Boolean resolved = false;

    private String resolvedAt;
    private String resolvedBy;

    /** Alarmın NASIL çözüldüğü. Manuel çözümde zorunlu; KENDİLİĞİNDEN kurtarmada null kalır. */
    @Column(columnDefinition = "TEXT")
    private String resolvedNote;

    private String createdAt;
    private String lastReAlertAt;

    /** Kaç kez re-alert gönderildi (ilk alarm hariç) — e-postadaki "Bu Hatırlatma (#N)" için. */
    @Column(name = "realert_count")
    private Integer realertCount = 0;

    /** Alarm fırtınası (storm) bağı — bu incident bir toplu storm'un parçasıysa storm id'si; değilse null.
     *  Set edildiğinde bireysel bildirim bastırılır (toplu alarm/recovery storm üzerinden gider). */
    private Long stormId;

    /** Monitörün grubu (group_name) — per-group storm scoping için oluşturulurken damgalanır (yalnız
     *  per-group modu açıkken StormService tarafından çözülür); cert-envanteri/grupsuz izlemede null. */
    @Column(name = "group_name")
    private String groupName;

    // ── Transient enrichment (populated by AdminController, not persisted) ──
    @Transient private String  syTeamName;
    @Transient private String  ugTeamName;
    /** Takım rozeti (tıklanabilir) için kimlik — adla değil id ile modal açılsın. */
    @Transient private Long    syTeamId;
    @Transient private Long    ugTeamId;
    @Transient private Integer certTier;
    @Transient private Long    emailSentCount;
    @Transient private Long    emailFailedCount;
    /**
     * Aynı domain + alarm tipi için son 30 gündeki TOPLAM alarm sayısı (bu alarm dahil).
     *
     * <p>Tekrar eden sorunu tekil olandan ayırır: "bu ay 4. kez" rozeti, aynı sertifikanın ya da
     * aynı DNS kaydının sürekli alarm ürettiğini tek bakışta gösterir — düz listede bu ancak
     * geçmişi tarayarak fark edilirdi.
     */
    @Transient private Long    repeatCount;
    /** Sertifikanın GÜNCEL son geçerlilik anı (LatestCheck) — alarm anındakinden farklıysa yenilenmiştir. */
    @Transient private String  currentNotAfter;

    /**
     * Alarm ACILIRKEN damgalanan Bildirim Grubu ({@code teamId} emsali).
     *
     * <p>Neden damga, neden canli cozum degil: alarm surerken monitorun grubu degisirse
     * ilk bildirim bir gruba, cozum bildirimi baska bir gruba giderdi — alarmi acan ekip
     * kapandigini HIC ogrenemezdi. Damga uc bildirim yolunun (ilk / cozum / yeniden
     * gonderim) ayni aliciya gitmesini garantiler.
     *
     * <p>Damgali grup silinmis/pasifse cozumleme zincirin kalanina duser.
     */
    @jakarta.persistence.Column(name = "notification_group_id")
    private Long notificationGroupId;
}
