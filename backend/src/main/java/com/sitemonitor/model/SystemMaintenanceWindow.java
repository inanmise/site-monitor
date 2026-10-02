package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sistem Bakım Modu penceresi (2026-10-02, kullanıcı kararı: "global yönetici SiteMonitor'ün KENDİSİNİ bakıma alır").
 * İzleme hedeflerinin bakım pencereleriyle ({@link MaintenanceWindow}, {@code maintenance_windows}) KARIŞTIRILMAZ — bu
 * kayıt uygulamanın kendisini kapatır: bakım süresince yalnız global yöneticiler giriş yapar ve içeride kalır.
 *
 * <p><b>Durum ZAMANDAN türetilir</b> ({@code SystemMaintenanceService.phaseOf}): satırda yalnız başlangıç/bitiş, iptal
 * damgası ve ayarlar saklanır; "planlandı → duyuru → uyarı → aktif → bitti" geçişi için ayrı bir "başlat" işi YOKTUR. Böylece
 * tüm pod'lar aynı kuralı aynı veriden uygular (çok replika), saat gelince giriş kapısı kendiliğinden kapanır/açılır.
 * "Uzat" bitişi ileri, "Hemen bitir" bitişi şimdiye çeker; "İptal" yalnız başlamadan önce.
 *
 * <p><b>Yazar sahipliği (eşzamanlılık):</b> yönetici eylemleri varlığı kaydeder; SAYAÇLAR (kapatılan oturum, engellenen
 * giriş, susturulan bildirim) ve YAN İŞ damgaları (duyuru/düzeltme e-postası, başlangıç/bitiş denetimi, telafi) yalnız
 * hedefli {@code UPDATE} ile yazılır ve varlıkta {@code updatable = false} işaretlidir — yöneticinin bayat varlık kaydı
 * bir pod'un o arada artırdığı sayacı ya da iş damgasını EZEMEZ.
 *
 * <p>Zaman damgaları UTC, {@code yyyy-MM-dd'T'HH:mm:ss} (alarm/denetim tablolarıyla aynı biçim; sözlük sırası = zaman
 * sırası). Saklama: silinmez (RetentionCatalog {@code system-maintenance-windows}, BOUNDED) — yılda birkaç satır; bakım
 * geçmişi ve denetimin tek kaynağı. Tablo {@code SchedulerService.applySchemaPatches}'te de açıkça kurulur.
 */
@Entity
@Table(name = "system_maintenance_windows")
@Data
@NoArgsConstructor
public class SystemMaintenanceWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Etkin başlangıç (UTC). "Hemen bakıma al"da tıklama + geri sayım. */
    @Column(name = "start_at", nullable = false, length = 30)
    private String startAt;

    /** Etkin bitiş (UTC) — "Uzat" ileri, "Hemen bitir" şimdiye çeker. */
    @Column(name = "end_at", nullable = false, length = 30)
    private String endAt;

    /** Planlanan başlangıç/bitiş (başlamadan önceki SON plan) — geçmişte "plan / gerçek" karşılaştırması. */
    @Column(name = "planned_start_at", length = 30)
    private String plannedStartAt;

    @Column(name = "planned_end_at", length = 30)
    private String plannedEndAt;

    /** Başlangıçtan kaç dk önce içerideki kullanıcılara geri sayımlı şerit (5/10/15/30). */
    @Column(name = "warn_minutes")
    private Integer warnMinutes;

    /** Başlangıçtan kaç saat önce duyuru şeridi + giriş/durum sayfası notu (0 = kapalı; 1/6/24/48). */
    @Column(name = "announce_hours")
    private Integer announceHours;

    /** "Bildirimler bakım boyunca sussun" — varsayılan KAPALI (her şey devam eder). */
    @Column(name = "mute_notifications")
    private Boolean muteNotifications;

    /** "Hemen bakıma al" ile mi başlatıldı (planlı değil). */
    @Column(name = "immediate")
    private Boolean immediate;

    /** Yöneticinin bakım nedeni / açıklaması (TR ve EN ayrı; boşsa varsayılan metin). */
    @Column(name = "message_tr", length = 1000)
    private String messageTr;

    @Column(name = "message_en", length = 1000)
    private String messageEn;

    /** İsteğe bağlı iletişim bilgisi (ör. "BT Destek · dahili 1234"). */
    @Column(name = "contact", length = 300)
    private String contact;

    // ── E-posta duyurusu (isteğe bağlı) ─────────────────────────────────────────────────────────────

    @Column(name = "email_all_users")
    private Boolean emailAllUsers;

    /** Seçili takımların kimlikleri (virgüllü) — takım e-posta adreslerine duyuru. */
    @Column(name = "email_team_ids", length = 2000)
    private String emailTeamIds;

    /** Saat değişirse / iptal edilirse düzeltme e-postası gönderilsin mi. */
    @Column(name = "email_corrections")
    private Boolean emailCorrections;

    /**
     * "Bakım bitince de e-posta gönder" (2026-10-02, kullanıcı isteği: "planlı bakım sonlandığında kullanıcılara bir uyarı
     * daha gönderilsin") — varsayılan AÇIK; null (yama öncesi satır) = açık. Yalnız e-posta alıcısı seçilmişse anlamlı.
     */
    @Column(name = "email_on_end")
    private Boolean emailOnEnd;

    /** Zaman değişikliği / iptal sürümü — her saat değişikliğinde ve iptalde +1 (düzeltme e-postasının tetiği). */
    @Column(name = "revision")
    private Integer revision;

    // ── Yönetici izleri ─────────────────────────────────────────────────────────────────────────────

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /** "Hemen bakıma al"ı basan yönetici (planlı bakımda null — saat gelince sistem başlatır). */
    @Column(name = "started_by", length = 120)
    private String startedBy;

    @Column(name = "extended_count")
    private Integer extendedCount;

    @Column(name = "extended_by", length = 120)
    private String extendedBy;

    /** "Hemen bitir"i basan yönetici (saatinde biten bakımda null). */
    @Column(name = "ended_by", length = 120)
    private String endedBy;

    @Column(name = "cancelled_at", length = 30)
    private String cancelledAt;

    @Column(name = "cancelled_by", length = 120)
    private String cancelledBy;

    // ── Yan iş damgaları (YALNIZ hedefli UPDATE yazar) ───────────────────────────────────────────────

    @Column(name = "announce_mail_at", length = 30, updatable = false)
    private String announceMailAt;

    @Column(name = "announce_mail_status", length = 200, updatable = false)
    private String announceMailStatus;

    @Column(name = "announce_mail_count", updatable = false)
    private Integer announceMailCount;

    /** Son gönderilen duyuru/düzeltme e-postasının kapsadığı {@link #revision}. */
    @Column(name = "mailed_revision", updatable = false)
    private Integer mailedRevision;

    @Column(name = "correction_mail_at", length = 30, updatable = false)
    private String correctionMailAt;

    @Column(name = "correction_mail_count", updatable = false)
    private Integer correctionMailCount;

    /** "Bakım tamamlandı" e-postası (2026-10-02) — bitişte tek pod, tek kez sahiplenilir; durum + alıcı sayısı. */
    @Column(name = "end_mail_at", length = 30, updatable = false)
    private String endMailAt;

    @Column(name = "end_mail_status", length = 200, updatable = false)
    private String endMailStatus;

    @Column(name = "end_mail_count", updatable = false)
    private Integer endMailCount;

    /** Başlangıç / bitiş denetim kaydı yazıldı mı (tek pod, tek kez). */
    @Column(name = "start_logged_at", length = 30, updatable = false)
    private String startLoggedAt;

    @Column(name = "end_logged_at", length = 30, updatable = false)
    private String endLoggedAt;

    /** Bildirim telafisi yapıldı mı (yalnız {@link #muteNotifications} açıksa anlamlı). */
    @Column(name = "catch_up_at", length = 30, updatable = false)
    private String catchUpAt;

    @Column(name = "caught_up_count", updatable = false)
    private Integer caughtUpCount;

    /** Yan işlerin tamamı bitti (bitmiş/iptal + e-posta + telafi) — iş turu bu satırı artık okumaz. */
    @Column(name = "jobs_done", updatable = false)
    private Boolean jobsDone;

    // ── Sayaçlar (YALNIZ atomik artırım yazar) ──────────────────────────────────────────────────────

    @Column(name = "sessions_ended", updatable = false)
    private Integer sessionsEnded;

    @Column(name = "logins_blocked", updatable = false)
    private Integer loginsBlocked;

    @Column(name = "notifications_suppressed", updatable = false)
    private Integer notificationsSuppressed;
}
