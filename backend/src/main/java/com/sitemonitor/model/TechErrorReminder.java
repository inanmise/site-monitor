package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Teknik hata raporu hatırlatma TALEBİ (2026-10-17, 12. sürüm — C9 / C10). Kanal × dönem × tür başına TEK satır:
 * {@code PERIOD} = "dönem raporu açılmadı / gönderilmedi" (takım postası + push), {@code APPROVAL} = "onay bekleyen
 * rapor" (onaylayıcılara kısa posta). Hatırlatma gönderilmeden ÖNCE bu satır koşullu olarak sahiplenilir: ilk kez
 * INSERT (benzersizlik kısıtı: ikinci pod ihlal alır), sonra {@code claim_rev} eşleşmesiyle UPDATE — iki pod aynı gün aynı
 * hatırlatmayı iki kez göndermez (en çok bir kez). Hiçbir kanaldan ulaşılamayan hatırlatmada talep geri bırakılır
 * (ertesi gün yeniden denenir).
 *
 * <p>{@code ref_key}: APPROVAL için onay isteğinin zamanı (epoch ms) — yeni bir istek bekleme süresini sıfırlar.
 * {@code last_sent_on}: son hatırlatmanın İstanbul günü ({@code yyyy-MM-dd}); null = talep bırakıldı. Kişisel veri yok.
 *
 * <p>Kapı satırları (inceleme 2026-10-18): {@code SKIP:<sebep>} (dönem) / {@code ASKIP:<sebep>} (onay) — modül takıma
 * kapalı, pasif takım, onay akışı kaldırıldı. Kapı TALEPTEN ÖNCE değerlendirilir; reddinde yalnız bu satır yazılır (sebebin
 * SKIPPED günlük satırı kanal × dönem × sebep başına bir kez) ve {@code PERIOD} / {@code APPROVAL} talebi BOŞ kalır — kapı
 * sonradan açılınca hatırlatma normal gider. İş her koşuda 6 aydır GÜNCELLENMEMİŞ satırları siler (satır yaşı; dönem yaşı
 * değil); kanalı silinen satırı saklama kuralı temizler.
 */
@Entity
@Table(name = "tech_error_reminders",
        uniqueConstraints = @UniqueConstraint(name = "uk_te_reminder", columnNames = {"channel_id", "report_year", "report_month", "kind"}),
        indexes = @Index(name = "idx_te_reminder_channel", columnList = "channel_id"))
@Data
@NoArgsConstructor
public class TechErrorReminder {

    public static final String KIND_PERIOD = "PERIOD";
    public static final String KIND_APPROVAL = "APPROVAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "report_month", nullable = false)
    private Integer reportMonth;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "ref_key", length = 40)
    private String refKey;

    @Column(name = "last_sent_on", length = 10)
    private String lastSentOn;

    /** Sahiplenilen hatırlatma sayısı (bilgi). */
    @Column(name = "send_count", nullable = false)
    private int sendCount;

    /** Koşullu güncelleme sürümü: her talep ve bırakma +1 (iki pod aynı satırı aynı anda sahiplenemez). */
    @Column(name = "claim_rev", nullable = false)
    private int claimRev;

    /**
     * Bırakılan turun durumu (inceleme 2026-10-18): {@code <ref>#<send_count>#<deneme>:<virgüllü kimlikler>} — bu turda
     * hatırlatmanın ULAŞTIĞI onaylayıcılar (posta SENT / QUEUED ya da push kuyrukta) ve deneme sayısı. Aynı turun yeniden
     * denemesi (aynı ref + send_count) bu kişilere posta / push göndermez; {@code TechErrorReminderService.MAX_TRIES}
     * denemeden sonra o turda vazgeçilir. YALNIZ koşullu bırakma sorgusu yazar ({@code updatable=false}: varlık kaydı
     * geri saramaz); başka bir turdaki değer yok sayılır. Kişisel veri yok (yalnız kullanıcı kimlikleri).
     */
    @Column(name = "reached_ids", length = 4000, updatable = false)
    private String reachedIds;

    @Column(name = "created_at", length = 32)
    private String createdAt;

    @Column(name = "updated_at", length = 32)
    private String updatedAt;
}
