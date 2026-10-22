package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kişi-bazlı webhook bildirimi — outbox VE teslimat günlüğü tek tabloda.
 *
 * <p>Satır önce PENDING yazılır (DB-outbox), tek-worker gönderir, sonuç aynı satıra işlenir.
 * Bellekte kuyruk YOK: pod yeniden başlasa da PENDING satırlar durur ve sınırsız in-memory
 * retry'ın OOM riski hiç doğmaz.
 *
 * <p><b>Anti-loop DB seviyesinde:</b> {@code UNIQUE(alert_event_id, dedupe_key, username)} —
 * aynı olayın aynı fazı aynı kişiye kod hatasında bile iki kez yazılamaz. {@code dedupeKey}:
 * OPEN/RESOLVE'da tetik adı (olay başına 1×); RE_ALERT'te {@code RE_ALERT:<gün>} (günde 1× —
 * mail'in günlük dedupe aynası); RESEND/TEST'te tetik+batchId (kasıtlı tekrar serbest).
 *
 * <p><b>Gizlilik:</b> sicil ({@code username}) yalnız bu tabloda ve arayüzde yaşar; sunucu
 * logları yalnız ADET yazar. {@code displayName} gönderim ANINDAKİ ad — kişi silinse de günlük
 * okunur kalır.
 */
@Entity
@Table(name = "user_push_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "ux_push_event_phase_user",
                columnNames = {"alert_event_id", "dedupe_key", "username"}),
        indexes = {
                @Index(name = "idx_push_created", columnList = "created_at"),
                @Index(name = "idx_push_username", columnList = "username"),
                @Index(name = "idx_push_team", columnList = "team_id"),
                @Index(name = "idx_push_monitor", columnList = "monitor_type, monitor_id"),
                @Index(name = "idx_push_status", columnList = "status"),
                @Index(name = "idx_push_overflow", columnList = "overflow_summary_id"),
        })
@Data
@NoArgsConstructor
public class UserPushDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TEST satırlarında null (olay yok) — dedupe orada batchId ile. */
    @Column(name = "alert_event_id")
    private Long alertEventId;

    /** OPEN / RE_ALERT / RESOLVE / RESEND / TEST. */
    @Column(name = "push_trigger", nullable = false, length = 40)
    private String trigger;

    /** UNIQUE kısıtın faz bileşeni — sınıf javadoc'undaki kurala göre üretilir. */
    @Column(name = "dedupe_key", nullable = false, length = 60)
    private String dedupeKey;

    @Column(name = "monitor_type", length = 20)
    private String monitorType;

    @Column(name = "monitor_id")
    private Long monitorId;

    @Column(name = "monitor_name", length = 300)
    private String monitorName;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "alert_level", length = 20)
    private String alertLevel;

    /** Alıcının sicili (K1: AppUser.username, N-biçimli). */
    @Column(nullable = false, length = 100)
    private String username;

    /** Gönderim anındaki ad-soyad — kişi silinse de günlük okunur kalsın. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 200)
    private String title;

    /** Gönderilen metin AYNEN (kırpılmışsa kırpılmış hâli). */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** PENDING / SENT / FAILED / SKIPPED_* / RATE_LIMITED / CIRCUIT_OPEN. */
    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Integer attempts = 0;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "sent_at")
    private String sentAt;

    /**
     * Bir sonraki deneme ZAMANI ({@code createdAt} ile aynı UTC biçimi). Yalnız {@code fail()} yeniden deneme
     * planlayınca dolar; null = hemen gönderilebilir (yeni satır). Outbox taraması bu damgadan ÖNCE satırı almaz:
     * backoff eskiden yalnız bellekteki timer'daydı, 60 sn süpürmesi ve her yeni kuyruk satırı bekleyen retry'ı
     * hemen yeniden gönderiyordu (2026-09-28). Sonradan eklenen kolon — NOT NULL YAPILMAZ (dolu tabloya
     * ddl-auto NOT NULL kolon ekleyemez, sessizce atlar).
     */
    @Column(name = "next_attempt_at")
    private String nextAttemptAt;

    /** Aynı toplu isteğin alt satırlarını bağlar (tek istek → tek batchId). */
    @Column(name = "batch_id", length = 40)
    private String batchId;

    /**
     * API yanıtındaki {@code notificationId} — kanıt zinciri: bu push API'ye ulaştı ve API ona
     * bu numarayı verdi; kurum tarafında iz sürerken tek anahtar. Tek toplu istek olduğundan
     * batch'in TÜM alt satırlarına aynı değer yazılır. Ayrıştırılamazsa null kalır — gönderim
     * yine SENT'tir (kimlik alınamadı diye başarı FAILED'a çevrilmez).
     */
    @Column(name = "notification_id", length = 60)
    private String notificationId;

    /** Ham API yanıtının ilk ~500 karakteri — error alanını başarı yolunda kirletmemek için ayrı. */
    @Column(name = "raw_response", length = 600)
    private String rawResponse;
    /**
     * Saat tavanı özeti (2026-10-04, onaylı öneri 2): {@code RATE_LIMITED} satırı bir özet push'una katıldıysa o özet
     * satırının kimliği. Durum {@code RATE_LIMITED} KALIR (günlük anlamı değişmez); kolon yalnız "özetlendi" bağıdır ve
     * aynı satırın iki kez özetlenmesini engeller (koşullu UPDATE ile sahiplenilir). Sonradan eklenen kolon — NOT NULL değil.
     */
    @Column(name = "overflow_summary_id")
    private Long overflowSummaryId;
    /** Mesajın kurulduğu dil ({@code tr} / {@code en}, 2026-10-04); null = eski satır (tr). */
    @Column(name = "push_lang", length = 5)
    private String pushLang;
    /**
     * Aynı kişi kuralı (2026-10-21, {@code PushSamePersonGuard}): durum {@code SKIPPED_SAME_PERSON} ise kararın verildiği aynı
     * kişinin hesabı, {@code SKIPPED_SAME_PERSON_EXCLUDED} ise bildirimden çıkarılan hesabı (kullanıcı adı — e-posta ASLA).
     * Gönderildiğini iddia etmez: seçilen hesabın satırı kendi sonucunu taşır. Diğer satırlarda null. Sonradan eklenen kolon.
     */
    @Column(name = "same_person_username", length = 100)
    private String samePersonUsername;

    /** {@code dedupe_key} kolonunun uzunluğu (VARCHAR(60)). */
    public static final int DEDUPE_KEY_MAX = 60;
    /** Sığdırılmış anahtarın sonundaki SHA-256 özetinin onaltılık hane sayısı. */
    static final int DEDUPE_HASH_HEX = 16;

    /**
     * Tekilleştirme anahtarını {@value #DEDUPE_KEY_MAX} karaktere SIĞDIRIR (2026-10-22, ALERTS-12 / DATA-2). Kolon VARCHAR(60);
     * çağıranın anahtarı daha uzunsa (TLS notu düşüşü: {@code tls-drop:<takım>:<tüm satır kimlikleri>}, zayıf algoritma:
     * {@code WEAK_ALGO:<alan adı>:<gün>}) PostgreSQL INSERT'i 22001 ile düşürüyor, satır "zaten var" sanılıp push SESSİZCE
     * kayboluyordu. Kural belirleyici (her pod aynı sonucu üretir) ve idempotenttir: 60'a sığan anahtar AYNEN döner; uzun
     * anahtar = ilk 43 karakter + {@code #} + TÜM anahtarın SHA-256 özetinin ilk 16 hanesi (önek sorguları — {@code storm:<id>:} —
     * ve günlükte okunabilirlik korunur; farklı uzun anahtarlar farklı özet alır). Yazım VE arama aynı kapıdan geçer
     * ({@code UserPushService} giriş noktaları + {@link #fitColumns}).
     */
    public static String fitDedupeKey(String key) {
        if (key == null || key.length() <= DEDUPE_KEY_MAX) return key;
        int head = DEDUPE_KEY_MAX - 1 - DEDUPE_HASH_HEX;
        if (Character.isHighSurrogate(key.charAt(head - 1))) head--;
        return key.substring(0, head) + "#" + sha256Hex(key).substring(0, DEDUPE_HASH_HEX);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);   // her JRE'de zorunlu algoritma — olamaz
        }
    }

    /**
     * Yazmadan önce VARCHAR kolonlarına sığdırır (2026-10-22, ALERTS-12 — {@code NotificationLog.fitColumns} deseni). Uzun bir
     * görünen ad (AD ad-soyadı 255'e kadar, kolon 200), yönetici başlığı ya da izleme adı satırı düşürüyor ve o kişinin alarm
     * push'u sessizce kayboluyordu. Kimlik taşıyan kolonlara ({@code username}, {@code same_person_username}) ve durum koduna
     * dokunulmaz — kırpılmış sicil başka bir kişiyi gösterirdi.
     */
    @PrePersist
    @PreUpdate
    void fitColumns() {
        dedupeKey = fitDedupeKey(dedupeKey);
        trigger = NotificationLog.fit(trigger, 40);
        monitorType = NotificationLog.fit(monitorType, 20);
        monitorName = NotificationLog.fit(monitorName, 300);
        alertLevel = NotificationLog.fit(alertLevel, 20);
        displayName = NotificationLog.fit(displayName, 200);
        title = NotificationLog.fit(title, 200);
        batchId = NotificationLog.fit(batchId, 40);
        notificationId = NotificationLog.fit(notificationId, 60);
        rawResponse = NotificationLog.fit(rawResponse, 600);
        pushLang = NotificationLog.fit(pushLang, 5);
    }

    /**
     * Sığdırma SONRASI hâlâ kolonuna sığmayan alanların adları (yalnız kimlik / durum kolonları olabilir) — satır yazılamayınca
     * WARN günlüğü hangi kolonun düşürdüğünü söylesin. Boş liste = uzunluk sorunu yok.
     */
    public java.util.List<String> overlongColumns() {
        java.util.List<String> out = new java.util.ArrayList<>();
        over(out, "dedupe_key", dedupeKey, DEDUPE_KEY_MAX);
        over(out, "push_trigger", trigger, 40);
        over(out, "monitor_type", monitorType, 20);
        over(out, "monitor_name", monitorName, 300);
        over(out, "alert_level", alertLevel, 20);
        over(out, "username", username, 100);
        over(out, "display_name", displayName, 200);
        over(out, "title", title, 200);
        over(out, "status", status, 40);
        over(out, "batch_id", batchId, 40);
        over(out, "notification_id", notificationId, 60);
        over(out, "raw_response", rawResponse, 600);
        over(out, "push_lang", pushLang, 5);
        over(out, "same_person_username", samePersonUsername, 100);
        return out;
    }

    private static void over(java.util.List<String> out, String column, String value, int max) {
        if (value != null && value.length() > max) out.add(column + "(" + value.length() + ">" + max + ")");
    }
}
