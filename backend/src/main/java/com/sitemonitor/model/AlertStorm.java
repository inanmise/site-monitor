package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm fırtınası (alert storm) — çok sayıda monitör kısa bir pencerede birden düştüğünde
 * bireysel alarmları TEK toplu bildirime indirgeyen agregasyon kaydı. Yalnız BİLDİRİMİ gruplar:
 * altındaki incident'ler (AlertEvent) per-monitör kaydedilmeye devam eder (geçmiş/uptime etkilenmez);
 * bağ {@code AlertEvent.stormId} ile kurulur ("storm X'in parçası").
 *
 * Scope (2026-09-29, takım yalıtımı): TAKIM ({@code scopeKey="TEAM:<takımId>"}) ya da takımın monitör grubu
 * ({@code "TEAM:<takımId>|GROUP:<grup>"}) — ayardaki "Alert storm based on monitor groups" toggle'ı grubu belirler.
 * Eski kuruluş geneli ("ACCOUNT") / takımsız grup kapsamı artık üretilmez; aktif kalanı StormService dağıtır.
 * Aynı scope için AYNI ANDA en fazla bir
 * aktif (resolved=false) storm olabilir; bu, {@code ux_alert_storms_active} kısmi UNIQUE indeksiyle
 * (scope_key WHERE resolved=false) DB seviyesinde garanti edilir → eşzamanlı terfi denemeleri
 * INSERT … ON CONFLICT ile tek kazanana düşer (idempotent, çift alarm yok).
 *
 * Tüm zaman damgaları AlertEvent ile aynı biçimde sabit-genişlik UTC ISO String'tir
 * ("yyyy-MM-dd'T'HH:mm:ss") → sözlüksel (lexicographic) aralık karşılaştırması geçerlidir.
 */
@Entity
@Table(
    name = "alert_storms",
    indexes = {
        @Index(name = "idx_as_resolved", columnList = "resolved"),
        @Index(name = "idx_as_scope",    columnList = "scopeKey,resolved")
    }
)
@Data
@NoArgsConstructor
public class AlertStorm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "TEAM:<takımId>" ya da "TEAM:<takımId>|GROUP:<grup>" (2026-09-29); eski kayıtlarda "ACCOUNT" / grup adı. */
    @Column(nullable = false)
    private String scopeKey;

    /** TEAM | TEAM_GROUP (eski kayıtlarda ACCOUNT | GROUP) */
    private String scopeType;

    @Column(nullable = false)
    private Boolean resolved = false;

    /** Storm'a bağlanmış (bildirimi bastırılmış) toplam monitör sayısı — bilgilendirme/e-posta için. */
    private Integer memberCount;

    /** Ortak kök-neden: tüm üyeler aynı tipteyse o alertType, karışıksa "MIXED". */
    private String rootCause;

    /** Toplu bildirim gönderilen takım adları (audit/insan-okur). */
    @Column(columnDefinition = "TEXT")
    private String notifiedTeams;

    /** Storm'un başladığı (terfi) an — UTC ISO. */
    private String createdAt;

    /** Storm'un çözüldüğü an — UTC ISO (resolved=true iken dolu). */
    private String resolvedAt;

    /** Son toplu (aggregate) bildirim anı — günlük re-alert kadansı için (aynı-UTC-gün kuralı). */
    private String lastReAlertAt;

    /**
     * Üyeleri eski (kuruluş geneli) fırtınadan SESSİZCE taşınarak kurulan takım fırtınasında o eski fırtınanın kimliği
     * (2026-09-29, O-3 geçişi); diğerlerinde null. Takım bu üyelerin açılış push'unu / 7-24 postasını ESKİ fırtınayla
     * aldı: çözüm push'unun "önceden alanlar" listesi ve 7/24 açılış kaydı eski kimliği de sayar (yeniden açılış yok,
     * "düştü"yü alan "düzeldi"yi de alır).
     */
    private Long legacyStormId;

    /**
     * Son üye katılım anı — UTC ISO (2026-09-30, prod olayı: SY takımının fırtınası kalıcı başarısız 3 sentetik test
     * yüzünden HİÇ kapanmıyor, takımın her yeni DOWN alarmı sessizce yutuluyordu). Fırtına bir PATLAMA'dır: bu andan
     * itibaren {@code site.monitor.storm.quiet-minutes} boyunca yeni üye gelmezse fırtına MÜHÜRLENİR — yeni alarm
     * kabul etmez ({@code StormService.evaluate} bireysel gönderir) ve yaşam döngüsü onu kapatır (hâlâ-down üyeler
     * bireysel hatta döner). Eski satırlarda NULL → {@code createdAt} esas alınır.
     */
    private String lastMemberAt;

    // ── Gözlem / analiz anlık görüntüsü (2026-09-30, takım bazlı fırtına ekranı) ─────────────────────────────────
    // Fırtına kapanınca üyelerin storm_id'si sıfırlanır ve eşik/pencere ayarı sonradan değişebilir; "kim, ne zaman,
    // hangi eşikle, kaç hedefle" sorusu ancak açılış anında dondurulan bu kolonlarla cevaplanır. Eski satırlarda NULL.

    /** Sahip takım (scope_key'den; eski ACCOUNT/grup satırlarında NULL). */
    private Long teamId;

    /** Per-group modda grup adı (UNGROUPED dâhil), aksi hâlde NULL. */
    private String groupName;

    /** Açılış anındaki eşik birimi (COUNT | PERCENT). */
    private String thresholdUnit;

    /** Açılış anındaki ayar değeri (adet ya da yüzde). */
    private Integer thresholdValue;

    /** Açılış anında hesaplanan etkin eşik (farklı hedef sayısı). */
    private Integer thresholdEffective;

    /** Açılış anındaki sayım penceresi (dk). */
    private Integer windowMinutes;

    /** Açılış anındaki sessiz pencere (dk). */
    private Integer quietMinutes;

    /** Açılışta penceredeki FARKLI hedef sayısı (eşiği aşan değer). */
    private Integer targetsAtOpen;

    /** Fırtına ömrü boyunca görülen en yüksek eşzamanlı düşük hedef sayısı. */
    private Integer peakTargets;

    /** Eşiği aşmayı tetikleyen alarm (evaluate'i çağıran olay); eski/legacy geçişte NULL. */
    private Long triggerEventId;

    /** Kapanış nedeni: FLOOR (histerezis tabanı) | SEALED (sessiz pencere) | DISABLED (koruma kapatıldı) | LEGACY_RETIRE. */
    private String resolveReason;
}
