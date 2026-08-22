package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * İzleme YAPILANDIRMASI değişiklik geçmişi — "kim, ne zaman, hangi IP'den, neyi değiştirdi".
 *
 * <p><b>Neden audit_log'dan AYRI bir tablo.</b> İkisi farklı işler yapar ve birini diğerinin
 * yerine koymak her ikisini de bozar:
 * <ul>
 *   <li><b>audit_log güvenlik kaydıdır:</b> hash zinciriyle kurcalanamaz, bilinçli olarak
 *       admin/AUDIT kapısında ({@code audit_log.read}) ve kendi retention'ı var (365 gün, silmeden
 *       önce JSONL arşivi). Bu tabloyu takım kullanıcısına açmak o politikayı delerdi.</li>
 *   <li><b>Bu tablo ÜRÜN geçmişidir:</b> takım üyesi kendi izlemesinin geçmişini görebilmeli.
 *       Kapsam sorgusu burada doğal — satır izlemenin KENDİ {@link #teamId}'sini taşır; audit'te
 *       yalnız AKTÖRÜN takımı vardır ve her okumada monitör tablosuna join gerekirdi.</li>
 * </ul>
 * Yazım ÇİFTTİR: audit çağrıları olduğu gibi kalır, bunun yanına bu satır eklenir.
 *
 * <p><b>Append-only.</b> Satırlar güncellenmez; "geri döndürme" bile eski satırı EZMEZ, yeni bir
 * {@code RESTORE} satırı ekler (scripted sürüm geçmişindeki aynı karar). Monitör silinse de
 * geçmiş satırları KALIR — silinen bir izlemenin neye benzediği denetim değeri taşır; öksüz
 * satırlar retention ile temizlenir.
 *
 * <p><b>Sınır:</b> burası yalnız YAPILANDIRMA yaşam döngüsüdür. Kontrol sonuçları, manuel
 * tetiklemeler ve alarm olayları buraya YAZILMAZ — onların yeri {@code ActivityLog} ve
 * {@code *Check} tablolarıdır. Karışırsa "neyi değiştirdik" sinyali kontrol gürültüsünde boğulur.
 */
@Entity
@Table(
    name = "monitor_change_log",
    indexes = {
        @Index(name = "idx_mchg_resource", columnList = "resourceKind,resourceId"),
        @Index(name = "idx_mchg_team",     columnList = "teamId"),
        @Index(name = "idx_mchg_created",  columnList = "createdAt")
    }
)
@Data
@NoArgsConstructor
public class MonitorChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PORT/DNS/KEYWORD/HTTP/PAGE/SCRIPTED/DOMAIN/PING/INVENTORY/GROUP/MAINTENANCE. */
    @Column(name = "resource_kind", nullable = false, length = 20)
    private String resourceKind;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** Olay ANINDAKİ ad — kaynak silinse ya da yeniden adlandırılsa da geçmiş okunabilsin. */
    @Column(name = "resource_name")
    private String resourceName;

    /**
     * Kaynak başına 0'dan artan sıra; <b>0 = CREATE</b>.
     *
     * <p>Best-effort: eşzamanlı iki güncelleme aynı numarayı alabilir. Bilinçli — bunun için
     * kilit almak yazma yolunu yavaşlatırdı ve sıra zaten {@code createdAt}+{@code id} ile
     * kesin. {@code seq} yalnız okunabilirlik ve "ilk kayıt hangisi" sorusu içindir.
     */
    @Column(nullable = false)
    private Integer seq;

    /** CREATE/UPDATE/DELETE/RESTORE/GROUP_RENAME/TRANSFER/AUDIT_BACKFILL. */
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    /**
     * Olay ANINDAKİ takım. Takım değişiminde YENİ değer yazılır; eski takım {@link #changes}
     * içinde görünür. Kapsam sorgusu (kim bu satırı görebilir) bu kolona dayanır.
     */
    @Column(name = "team_id")
    private Long teamId;

    @Column(length = 100)
    private String actor;

    @Column(name = "actor_id")
    private Long actorId;

    /** Ad-soyad (UserBadge sicil yerine bunu gösterir); çözülemezse null. */
    @Column(name = "actor_name")
    private String actorName;

    /** Gerçek istemci IP'si — {@code ClientIpResolver} ile (X-Forwarded-For politikası tek yerde). */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /** HAM saklanır; kısaltma bir SUNUM kararıdır ve arayüzde yapılır (veri kaybı olmasın). */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /** {@code AuditDiff.diff} JSON'u: {@code {"alan":{"from":x,"to":y}}}. CREATE'te null olabilir. */
    @Column(columnDefinition = "TEXT")
    private String changes;

    /**
     * Olaydan SONRAKİ tam durum ({@code AuditDiff.snapshot} JSON'u).
     *
     * <p>Her olayda tutulur: "şu tarihte bu izleme nasıldı" sorusu tek satırdan cevaplanır ve
     * geri döndürme diff'leri baştan oynatmayı gerektirmez. Satır başı ~1-2 KB; yapılandırma
     * değişiklikleri kontrol kayıtları gibi akmadığı için hacim düşük kalır.
     */
    @Column(columnDefinition = "TEXT")
    private String snapshot;

    /** Kullanıcının yazdığı opsiyonel "değişiklik nedeni". Boşsa arayüzde hiç gösterilmez. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;
}
