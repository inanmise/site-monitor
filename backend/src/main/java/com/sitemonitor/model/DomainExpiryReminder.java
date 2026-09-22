package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alan adı süre-bitişi HATIRLATMASI kaydı (2026-09-22, denetim madde E / bulgu F1).
 *
 * <p>{@code DomainMonitor.thresholdsCsv} ("hatırlatma eşikleri", vars. 60,30,14,7,3,1) formda ve rehberde
 * "hangi günlerde hatırlatma e-postası gideceği" diye anlatılıyordu ama backend alanı hiç okumuyordu: alarm yalnız
 * uyarı eşiğinde bir kez açılıyor, sonra sessizlik. Bu tablo eşik başına <b>bir kez</b> gönderilen hatırlatmanın
 * izidir; anahtar (monitor, expiry_date, threshold_days) — alan adı yenilenip bitiş ileri gidince aynı eşik yeni
 * bitiş için yeniden gönderilebilir (yeni satır), aynı bitiş için asla ikinci kez gönderilmez.
 *
 * <p>Tekilleştirme uygulama katmanında (sweep tek ipliklidir); ddl-auto'nun dolu tabloda @UniqueConstraint'i
 * SESSİZCE oluşturmadığı biliniyor — bilerek unique kısıt yok, {@code existsBy…} kapısı var.
 */
@Entity
@Table(name = "domain_expiry_reminders", indexes = {
    @Index(name = "idx_der_monitor", columnList = "monitor_id"),
    @Index(name = "idx_der_sent_at", columnList = "sent_at")
})
@Data
@NoArgsConstructor
public class DomainExpiryReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Hatırlatmanın ait olduğu bitiş tarihi (kontrolün döndürdüğü ham ISO). Yenilemede değişir → yeni seri. */
    @Column(name = "expiry_date", nullable = false)
    private String expiryDate;

    /** Aşılan eşik (gün). */
    @Column(name = "threshold_days", nullable = false)
    private Integer thresholdDays;

    /** Gönderim anındaki kalan gün. */
    @Column(name = "days_remaining")
    private Integer daysRemaining;

    /** SENT (e-posta/push gitti) | COVERED (aynı turda daha sıkı bir eşik gönderildi, bu eşik ayrıca gönderilmedi)
     *  | SKIPPED_NO_RECIPIENT | SKIPPED_CHANNELS_OFF. */
    @Column(nullable = false, length = 24)
    private String status;

    /** Alıcı e-postalar (virgülle) — denetim izi. */
    @Column(columnDefinition = "TEXT")
    private String recipients;

    /** Push kuyruğuna giren kişi sayısı (kanal kapalıysa null). */
    @Column(name = "push_queued")
    private Integer pushQueued;

    @Column(name = "sent_at", nullable = false)
    private String sentAt;
}
